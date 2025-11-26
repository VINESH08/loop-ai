package com.loopai.backend.controller;

import com.loopai.backend.service.AIAssistantService;
import com.loopai.backend.service.ElevenLabsTtsService;
import com.loopai.backend.service.SpeechToTextService;
import com.loopai.backend.service.TextToSpeechService;
import com.loopai.backend.service.TwilioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Voice API Controller
 * Handles the complete voice pipeline: Audio → STT → LLM → TTS → Audio
 * Includes out-of-scope detection and human agent forwarding
 */
@RestController
@RequestMapping("/api/voice")
@RequiredArgsConstructor
@Slf4j
public class VoiceController {

    private final SpeechToTextService speechToTextService;
    private final AIAssistantService aiAssistantService;
    private final TextToSpeechService textToSpeechService;
    private final ElevenLabsTtsService elevenLabsTtsService;
    private final TwilioService twilioService;

    @Value("${tts.voice:alloy}")
    private String ttsVoice;

    @Value("${tts.intro.message:Hi, this is Loop AI. How can I assist you today?}")
    private String introMessage;

    /**
     * Main voice conversation endpoint
     * Accepts audio, processes through STT → LLM → TTS pipeline
     * Returns audio response with per-user memory isolation
     */
    @PostMapping(value = "/conversation", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> processVoiceConversation(
            @RequestParam("audio") MultipartFile audioFile,
            @AuthenticationPrincipal OAuth2User principal) {

        long pipelineStart = System.currentTimeMillis();
        long stepStart;

        try {
            // Get user ID for memory isolation (use email or sub claim)
            String odId = principal != null
                    ? principal.getAttribute("email")
                    : "anonymous";

            log.info("========================================");
            log.info("🎙️ VOICE PIPELINE START - User: {}", odId);
            log.info("========================================");

            // Step 1: Speech-to-Text (Deepgram)
            stepStart = System.currentTimeMillis();
            log.info("Step 1: Converting speech to text...");
            String transcribedText = speechToTextService.transcribe(
                    audioFile.getBytes(),
                    audioFile.getContentType());
            long sttTime = System.currentTimeMillis() - stepStart;
            log.info("⏱️ LOG TIME: User Audio → Deepgram STT = {}ms", sttTime);

            if (transcribedText == null || transcribedText.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Could not transcribe audio. Please speak clearly and try again."));
            }

            // Step 2: Process with LLM (GPT-4o-mini via LangChain4j) - with user's memory
            stepStart = System.currentTimeMillis();
            log.info("Step 2: Processing with AI assistant for user: {}...", odId);
            String aiResponse = aiAssistantService.chat(odId, transcribedText);
            long llmTime = System.currentTimeMillis() - stepStart;
            log.info("⏱️ LOG TIME: Deepgram Text → LLM (with RAG) = {}ms", llmTime);

            // Step 2.5: Check for out-of-scope detection (human agent forwarding)
            boolean forwardToHuman = aiResponse.contains(AIAssistantService.FORWARD_MARKER);
            if (forwardToHuman) {
                log.info("🚨 OUT OF SCOPE DETECTED - Forwarding to human agent");
                log.info("   User: {}", odId);
                log.info("   Query: {}", transcribedText);

                // Remove the marker from response (user shouldn't see it)
                aiResponse = aiResponse.replace(AIAssistantService.FORWARD_MARKER, "").trim();

                // Trigger async forwarding to human agent (non-blocking)
                twilioService.forwardToHumanAgent(odId, transcribedText);

                // Clear user's memory since conversation is ending
                aiAssistantService.clearMemory(odId);
            }

            // Step 3: Text-to-Speech (ElevenLabs Turbo if enabled, else OpenAI TTS)
            stepStart = System.currentTimeMillis();
            byte[] audioResponse;
            String ttsProvider;

            if (elevenLabsTtsService.isEnabled()) {
                log.info("Step 3: Converting response to speech with ElevenLabs Turbo...");
                audioResponse = elevenLabsTtsService.synthesize(aiResponse);
                ttsProvider = "ElevenLabs";
            } else {
                log.info("Step 3: Converting response to speech with OpenAI TTS, voice: {}", ttsVoice);
                audioResponse = textToSpeechService.synthesize(aiResponse, ttsVoice, "tts-1");
                ttsProvider = "OpenAI";
            }
            long ttsTime = System.currentTimeMillis() - stepStart;
            log.info("⏱️ LOG TIME: LLM Response → {} TTS = {}ms", ttsProvider, ttsTime);

            long totalTime = System.currentTimeMillis() - pipelineStart;
            log.info("========================================");
            log.info("✅ PIPELINE COMPLETE{}", forwardToHuman ? " (FORWARDED TO HUMAN)" : "");
            log.info("⏱️ TOTAL PIPELINE TIME = {}ms (~{}s)", totalTime, totalTime / 1000);
            log.info("   └─ STT: {}ms", sttTime);
            log.info("   └─ LLM+RAG: {}ms", llmTime);
            log.info("   └─ TTS: {}ms", ttsTime);
            log.info("========================================");

            // Return audio with metadata headers
            // Note: HTTP headers cannot contain CR/LF, so we sanitize the text
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.valueOf("audio/mpeg"));
            headers.set("X-Transcript", sanitizeForHeader(transcribedText));
            headers.set("X-Response-Text", sanitizeForHeader(aiResponse));
            headers.set("X-Time-Taken", String.valueOf(totalTime)); // Time in ms
            headers.set("X-Voice-Id", ttsVoice);
            headers.set("X-Forwarded-To-Human", String.valueOf(forwardToHuman));
            headers.setContentLength(audioResponse.length);

            return new ResponseEntity<>(audioResponse, headers, HttpStatus.OK);

        } catch (Exception e) {
            log.error("Voice processing failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Failed to process voice request: " + e.getMessage()));
        }
    }

    /**
     * Text-only conversation endpoint (for testing or text fallback)
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, String>> processTextChat(
            @RequestBody Map<String, String> request,
            @AuthenticationPrincipal OAuth2User principal) {

        try {
            String userMessage = request.get("message");
            String odId = principal != null
                    ? principal.getAttribute("email")
                    : "anonymous";

            if (userMessage == null || userMessage.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Message is required"));
            }

            log.info("Processing text chat for user {}: {}", odId, userMessage);

            String aiResponse = aiAssistantService.chat(odId, userMessage);

            // Check for out-of-scope (human forwarding)
            boolean forwardToHuman = aiResponse.contains(AIAssistantService.FORWARD_MARKER);
            if (forwardToHuman) {
                aiResponse = aiResponse.replace(AIAssistantService.FORWARD_MARKER, "").trim();
                twilioService.forwardToHumanAgent(odId, userMessage);
                aiAssistantService.clearMemory(odId);
            }

            return ResponseEntity.ok(Map.of(
                    "response", aiResponse,
                    "userMessage", userMessage,
                    "forwardedToHuman", String.valueOf(forwardToHuman)));

        } catch (Exception e) {
            log.error("Chat processing failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Failed to process chat: " + e.getMessage()));
        }
    }

    /**
     * Text-to-Speech only endpoint (for getting audio from text)
     */
    @PostMapping(value = "/synthesize", produces = "audio/mpeg")
    public ResponseEntity<byte[]> synthesizeSpeech(@RequestBody Map<String, String> request) {
        try {
            String text = request.get("text");
            String voice = request.getOrDefault("voice", "alloy");

            if (text == null || text.trim().isEmpty()) {
                return ResponseEntity.badRequest().build();
            }

            byte[] audio = textToSpeechService.synthesize(text, voice, "tts-1");

            return ResponseEntity.ok()
                    .contentType(MediaType.valueOf("audio/mpeg"))
                    .body(audio);

        } catch (Exception e) {
            log.error("Speech synthesis failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * TEST ENDPOINT: Speech-to-Text only (for testing Deepgram)
     * Returns JSON with transcript instead of audio
     */
    @PostMapping(value = "/test-stt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> testSpeechToText(
            @RequestParam("audio") MultipartFile audioFile) {

        log.info("========================================");
        log.info("🧪 TEST STT ENDPOINT CALLED");
        log.info("========================================");

        try {
            log.info("📁 Audio file received:");
            log.info("   - Original filename: {}", audioFile.getOriginalFilename());
            log.info("   - Content type: {}", audioFile.getContentType());
            log.info("   - Size: {} bytes ({} KB)", audioFile.getSize(), audioFile.getSize() / 1024);
            log.info("   - Is empty: {}", audioFile.isEmpty());

            if (audioFile.isEmpty()) {
                log.error("❌ Audio file is empty!");
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "Audio file is empty"));
            }

            // Call Deepgram STT
            log.info("🎤 Calling SpeechToTextService...");
            String transcript = speechToTextService.transcribe(
                    audioFile.getBytes(),
                    audioFile.getContentType());

            log.info("========================================");
            log.info("✅ TEST STT COMPLETE");
            log.info("📝 Final transcript: \"{}\"", transcript);
            log.info("========================================");

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "transcript", transcript != null ? transcript : "",
                    "audioSize", audioFile.getSize(),
                    "contentType", audioFile.getContentType()));

        } catch (Exception e) {
            log.error("========================================");
            log.error("❌ TEST STT FAILED: {}", e.getMessage());
            log.error("========================================", e);

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", e.getMessage()));
        }
    }

    /**
     * Get intro audio for Loop AI greeting
     * Uses ElevenLabs if enabled, otherwise OpenAI TTS
     */
    @GetMapping(value = "/intro", produces = "audio/mpeg")
    public ResponseEntity<byte[]> getIntroAudio() {
        try {
            byte[] audio;
            String provider;

            if (elevenLabsTtsService.isEnabled()) {
                log.info("Generating intro audio with ElevenLabs...");
                audio = elevenLabsTtsService.synthesize(introMessage);
                provider = "ElevenLabs";
            } else {
                log.info("Generating intro audio with OpenAI TTS, voice: {}", ttsVoice);
                audio = textToSpeechService.synthesize(introMessage, ttsVoice, "tts-1");
                provider = "OpenAI";
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.valueOf("audio/mpeg"));
            headers.set("X-TTS-Provider", provider);
            headers.set("X-Intro-Text", sanitizeForHeader(introMessage));
            headers.setContentLength(audio.length);
            headers.setCacheControl("public, max-age=3600");

            return new ResponseEntity<>(audio, headers, HttpStatus.OK);

        } catch (Exception e) {
            log.error("Failed to generate intro audio", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get current voice configuration (for frontend to check if cache is valid)
     */
    @GetMapping("/voice-config")
    public ResponseEntity<Map<String, String>> getVoiceConfig() {
        return ResponseEntity.ok(Map.of(
                "voiceId", ttsVoice,
                "introMessage", introMessage));
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "status", "healthy",
                "service", "Loop AI Voice Service",
                "voice", ttsVoice));
    }

    /**
     * Sanitize text for use in HTTP headers
     * - Remove CR/LF (not allowed in headers)
     * - Remove emojis and non-ASCII characters (only 0-255 allowed in headers)
     */
    private String sanitizeForHeader(String text) {
        if (text == null)
            return "";
        return text
                .replace("\r\n", " ")
                .replace("\n", " ")
                .replace("\r", " ")
                .replaceAll("[^\\x00-\\xFF]", "") // Remove non-ASCII (emojis, etc.)
                .replaceAll("\\s+", " ") // Collapse multiple spaces
                .trim();
    }
}

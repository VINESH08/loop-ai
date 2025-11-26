package com.loopai.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
@Slf4j
public class TextToSpeechService {

    private final WebClient webClient;

    public TextToSpeechService(@Value("${openai.api.key}") String apiKey) {
        // Increase buffer size to 10MB for large audio responses
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(10 * 1024 * 1024)) // 10MB
                .build();

        this.webClient = WebClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .exchangeStrategies(strategies)
                .build();
        
        log.info("TextToSpeechService initialized with 10MB buffer");
    }

    /**
     * Convert text to speech using OpenAI TTS
     * @param text Text to convert to speech
     * @return Audio bytes (MP3 format)
     */
    public byte[] synthesize(String text) {
        return synthesize(text, "alloy", "tts-1");
    }

    /**
     * Convert text to speech with custom voice and model
     * @param text Text to convert
     * @param voice Voice option: alloy, echo, fable, onyx, nova, shimmer
     * @param model Model: tts-1 (fast) or tts-1-hd (high quality)
     * @return Audio bytes (MP3 format)
     */
    public byte[] synthesize(String text, String voice, String model) {
        long ttsStart = System.currentTimeMillis();
        
        try {
            log.info("========================================");
            log.info("🔊 TTS SYNTHESIS START");
            log.info("   Text length: {} chars", text.length());
            log.info("   Voice: {}, Model: {}", voice, model);
            log.info("========================================");

            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "input", text,
                    "voice", voice,
                    "response_format", "mp3"
            );

            byte[] audioBytes = webClient.post()
                    .uri("/audio/speech")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block();

            long duration = System.currentTimeMillis() - ttsStart;
            
            log.info("========================================");
            log.info("✅ TTS SYNTHESIS COMPLETE");
            log.info("   Audio size: {} bytes ({} KB)", 
                    audioBytes != null ? audioBytes.length : 0,
                    audioBytes != null ? audioBytes.length / 1024 : 0);
            log.info("⏱️ LOG TIME: LLM Response → OpenAI TTS = {}ms", duration);
            log.info("========================================");
            
            return audioBytes;

        } catch (Exception e) {
            log.error("❌ OpenAI TTS synthesis failed: {}", e.getMessage());
            throw new RuntimeException("Failed to synthesize speech: " + e.getMessage(), e);
        }
    }
}

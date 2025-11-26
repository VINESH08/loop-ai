package com.loopai.backend.service;

import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * ElevenLabs Text-to-Speech Service
 * Uses Turbo v2.5 model for ultra-low latency (~1-2s vs 3-4s OpenAI)
 */
@Service
@Slf4j
public class ElevenLabsTtsService {

    private final OkHttpClient httpClient;
    private final String apiKey;
    private final String voiceId;
    private final boolean enabled;

    // ElevenLabs Voice IDs (pre-made voices)
    // Rachel (female): 21m00Tcm4TlvDq8ikWAM
    // Adam (male): pNInz6obpgDQGcFmaJgB
    // Bella (female): EXAVITQu4vr4xnSDxMaL
    // Josh (male): TxGEqnHWrfWFTfGW9XjX

    public ElevenLabsTtsService(
            @Value("${elevenlabs.api.key:}") String apiKey,
            @Value("${elevenlabs.voice.id:21m00Tcm4TlvDq8ikWAM}") String voiceId) {
        
        this.apiKey = apiKey;
        this.voiceId = voiceId;
        this.enabled = apiKey != null && !apiKey.isEmpty() && !apiKey.equals("YOUR_ELEVENLABS_API_KEY");
        
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();

        log.info("========================================");
        log.info("ElevenLabs TTS Service initialized");
        log.info("   Enabled: {}", enabled);
        log.info("   Voice ID: {}", voiceId);
        log.info("   Model: eleven_turbo_v2_5 (low latency)");
        log.info("========================================");
    }

    /**
     * Check if ElevenLabs is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Convert text to speech using ElevenLabs Turbo model
     * 
     * @param text Text to convert to speech
     * @return Audio bytes (MP3 format)
     */
    public byte[] synthesize(String text) {
        if (!enabled) {
            throw new IllegalStateException("ElevenLabs TTS is not configured. Set elevenlabs.api.key in application.properties");
        }

        log.info("========================================");
        log.info("🔊 ELEVENLABS TTS - START");
        log.info("   Text length: {} chars", text.length());
        log.info("   Voice ID: {}", voiceId);
        log.info("   Model: eleven_turbo_v2_5");
        log.info("========================================");

        try {
            String url = "https://api.elevenlabs.io/v1/text-to-speech/" + voiceId;

            // Request body with turbo model for low latency
            String jsonBody = String.format("""
                {
                    "text": "%s",
                    "model_id": "eleven_turbo_v2_5",
                    "voice_settings": {
                        "stability": 0.5,
                        "similarity_boost": 0.75,
                        "style": 0.0,
                        "use_speaker_boost": true
                    }
                }
                """, escapeJson(text));

            RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("xi-api-key", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "audio/mpeg")
                    .post(body)
                    .build();

            log.info("📤 Sending request to ElevenLabs...");
            long startTime = System.currentTimeMillis();

            try (Response response = httpClient.newCall(request).execute()) {
                long duration = System.currentTimeMillis() - startTime;
                log.info("📥 Response received in {} ms", duration);

                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "No error details";
                    log.error("❌ ELEVENLABS API ERROR: {} - {}", response.code(), errorBody);
                    throw new RuntimeException("ElevenLabs API error: " + response.code());
                }

                byte[] audioBytes = response.body().bytes();

                log.info("========================================");
                log.info("✅ ELEVENLABS TTS COMPLETE");
                log.info("   Audio size: {} bytes ({} KB)", audioBytes.length, audioBytes.length / 1024);
                log.info("⏱️ LOG TIME: Text → ElevenLabs TTS = {}ms", duration);
                log.info("========================================");

                return audioBytes;
            }

        } catch (IOException e) {
            log.error("❌ ElevenLabs TTS failed: {}", e.getMessage());
            throw new RuntimeException("Failed to synthesize speech: " + e.getMessage(), e);
        }
    }

    /**
     * Escape special characters for JSON
     */
    private String escapeJson(String text) {
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}


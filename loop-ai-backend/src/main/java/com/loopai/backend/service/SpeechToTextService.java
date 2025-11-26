package com.loopai.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class SpeechToTextService {

    private final OkHttpClient httpClient;
    private final String apiKey;
    private final ObjectMapper objectMapper;

    public SpeechToTextService(@Value("${deepgram.api.key}") String apiKey) {
        this.apiKey = apiKey;
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();

        log.info("========================================");
        log.info("SpeechToTextService initialized");
        log.info("Deepgram API Key configured: {}",
                apiKey != null && apiKey.length() > 10 ? "YES (length: " + apiKey.length() + ")" : "NO or INVALID");
        log.info("========================================");
    }

    /**
     * Convert audio bytes to text using Deepgram API
     * 
     * @param audioData Audio file bytes (supports wav, mp3, webm, etc.)
     * @param mimeType  MIME type of the audio (e.g., "audio/webm", "audio/wav")
     * @return Transcribed text
     */
    public String transcribe(byte[] audioData, String mimeType) {
        log.info("========================================");
        log.info("🎤 DEEPGRAM STT - START");
        log.info("========================================");
        log.info("📦 Audio received:");
        log.info("   - Size: {} bytes ({} KB)", audioData.length, audioData.length / 1024);
        log.info("   - MIME Type: {}", mimeType);

        try {
            // Build Deepgram API URL with parameters
            String url = "https://api.deepgram.com/v1/listen?" +
                    "model=nova-2&" +
                    "language=en&" +
                    "punctuate=true&" +
                    "smart_format=true";

            log.info("🌐 Calling Deepgram API:");
            log.info("   - URL: {}", url);
            log.info("   - Model: nova-2");

            // Create request body with audio data
            RequestBody body = RequestBody.create(audioData, MediaType.parse(mimeType));

            // Build request
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Token " + apiKey)
                    .addHeader("Content-Type", mimeType)
                    .post(body)
                    .build();

            log.info("📤 Sending request to Deepgram...");
            long startTime = System.currentTimeMillis();

            // Execute request
            try (Response response = httpClient.newCall(request).execute()) {
                long duration = System.currentTimeMillis() - startTime;
                log.info("📥 Response received in {} ms", duration);
                log.info("   - Status Code: {}", response.code());
                log.info("   - Status Message: {}", response.message());

                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "No error details";
                    log.error("❌ DEEPGRAM API ERROR:");
                    log.error("   - Code: {}", response.code());
                    log.error("   - Body: {}", errorBody);
                    log.info("========================================");
                    throw new RuntimeException("Deepgram API error: " + response.code() + " - " + errorBody);
                }

                String responseBody = response.body().string();
                log.info("📄 Raw Response (first 500 chars): {}",
                        responseBody.length() > 500 ? responseBody.substring(0, 500) + "..." : responseBody);

                // Parse JSON response
                JsonNode root = objectMapper.readTree(responseBody);

                // Check if response has expected structure
                JsonNode channels = root.path("results").path("channels");
                if (channels.isMissingNode() || channels.isEmpty()) {
                    log.error("❌ Unexpected response structure - no channels found");
                    log.info("========================================");
                    return "";
                }

                String transcript = channels
                        .get(0)
                        .path("alternatives")
                        .get(0)
                        .path("transcript")
                        .asText("");

                // Get confidence score
                double confidence = channels
                        .get(0)
                        .path("alternatives")
                        .get(0)
                        .path("confidence")
                        .asDouble(0.0);

                log.info("========================================");
                log.info("✅ TRANSCRIPTION SUCCESSFUL!");
                log.info("📝 Transcript: \"{}\"", transcript);
                log.info("📊 Confidence: {}%", String.format("%.1f", confidence * 100));
                log.info("⏱️ LOG TIME: User Audio → Deepgram STT = {}ms", duration);
                log.info("========================================");

                return transcript;
            }

        } catch (IOException e) {
            log.error("========================================");
            log.error("❌ DEEPGRAM TRANSCRIPTION FAILED");
            log.error("   - Error: {}", e.getMessage());
            log.error("   - Type: {}", e.getClass().getSimpleName());
            log.error("========================================", e);
            throw new RuntimeException("Failed to transcribe audio: " + e.getMessage(), e);
        }
    }
}

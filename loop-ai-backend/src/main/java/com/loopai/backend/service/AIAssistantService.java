package com.loopai.backend.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class AIAssistantService {

    // Groq Configuration (FAST LLM!)
    @Value("${groq.api.key}")
    private String groqApiKey;

    @Value("${groq.model.name:llama-3.1-70b-versatile}")
    private String groqModelName;

    @Value("${groq.temperature:0.1}")
    private Double groqTemperature;

    @Value("${groq.max-tokens:150}")
    private Integer groqMaxTokens;

    // OpenAI as fallback (if Groq key not configured)
    @Value("${openai.api.key:}")
    private String openaiApiKey;

    private final HospitalSearchTool hospitalSearchTool;

    private HospitalAssistant assistant;

    // Caffeine cache with automatic expiry - memory freed after 30 min of
    // inactivity
    private final Cache<String, MessageWindowChatMemory> userMemories = Caffeine.newBuilder()
            .expireAfterAccess(30, TimeUnit.MINUTES) // Expire after 30 min of no activity
            .maximumSize(1000) // Max 1000 concurrent users
            .recordStats() // Enable stats for monitoring
            .removalListener((key, value, cause) -> log.debug("Memory removed for user {}: {}", key, cause))
            .build();

    // Marker for out-of-scope detection
    public static final String FORWARD_MARKER = "FORWARD_TO_HUMAN:";

    /**
     * AI Assistant interface with per-user memory isolation
     * Includes out-of-scope detection for human agent forwarding
     */
    interface HospitalAssistant {
        @SystemMessage("""
                You are Loop AI. Brief responses only. No emojis.

                TOOL SELECTION:
                - For ADDRESS requests ("give me address of X") = Use getHospitalDetails
                - For "which city is X in?" = Use findHospitalLocation
                - For "is X in my network?" = Use confirmHospitalInNetwork
                - For "hospitals in Y city" = Use getHospitalsByCity

                WHEN TOOL ASKS FOR CITY:
                If tool response contains "Which city?" or "found in multiple cities" =
                YOU MUST ask the user: "Which city are you looking for?"
                Do NOT list all cities with details. Just ask which city.

                RULES:
                1. ONLY use data from tool responses. NEVER invent hospital names.
                2. If tool says "not found" = Say "I dont have that hospital"
                3. Keep responses under 50 words
                4. Pass tool's clarification questions directly to user

                NON-HOSPITAL topics = "FORWARD_TO_HUMAN: I'm sorry, I can't help with that. I am forwarding this to a human agent."
                """)
        String chat(@MemoryId String odId, @UserMessage String userMessage);
    }

    @PostConstruct
    public void init() {
        log.info("========================================");
        log.info("🤖 Initializing AI Assistant");
        log.info("========================================");

        ChatLanguageModel chatModel;
        String modelDescription;

        // Use Groq if API key is configured, otherwise fall back to OpenAI
        if (groqApiKey != null && !groqApiKey.isEmpty() && !groqApiKey.equals("YOUR_GROQ_API_KEY_HERE")) {
            // 🚀 GROQ - Ultra-fast inference using OpenAI-compatible API!
            chatModel = OpenAiChatModel.builder()
                    .baseUrl("https://api.groq.com/openai/v1") // Groq's OpenAI-compatible endpoint
                    .apiKey(groqApiKey)
                    .modelName(groqModelName)
                    .temperature(groqTemperature)
                    .maxTokens(groqMaxTokens)
                    .timeout(Duration.ofSeconds(30))
                    .build();
            modelDescription = "Groq " + groqModelName + " (FAST!)";
            log.info("🚀 Using GROQ for ultra-fast LLM inference");
            log.info("   Endpoint: https://api.groq.com/openai/v1");
        } else {
            // Fallback to OpenAI
            chatModel = OpenAiChatModel.builder()
                    .apiKey(openaiApiKey)
                    .modelName("gpt-4o-mini")
                    .temperature(0.1)
                    .maxTokens(150)
                    .build();
            modelDescription = "OpenAI GPT-4o-mini (fallback)";
            log.warn("⚠️ Groq API key not configured, using OpenAI as fallback");
        }

        // ChatMemoryProvider creates separate memory for each user (with auto-expiry)
        ChatMemoryProvider memoryProvider = odId -> userMemories.get(odId.toString(),
                id -> MessageWindowChatMemory.withMaxMessages(10));

        this.assistant = AiServices.builder(HospitalAssistant.class)
                .chatLanguageModel(chatModel)
                .tools(hospitalSearchTool)
                .chatMemoryProvider(memoryProvider) // Per-user memory!
                .build();

        log.info("✅ AI Assistant initialized with per-user memory");
        log.info("   - Model: {}", modelDescription);
        log.info("   - Temperature: {}", groqTemperature);
        log.info("   - Max Tokens: {}", groqMaxTokens);
        log.info("   - Memory: 10 messages per user");
        log.info("   - Tools: HospitalSearchTool (RAG)");
        log.info("========================================");
    }

    /**
     * Process user message with user-isolated memory
     * 
     * @param userId      Unique user identifier (from OAuth)
     * @param userMessage The user's transcribed speech or text
     * @return AI-generated response text
     */
    public String chat(String userId, String userMessage) {
        try {
            log.info("========================================");
            log.info("💬 LLM PROCESSING START");
            log.info("   User: {}", userId);
            log.info("   Input: \"{}\"", userMessage);
            log.info("========================================");

            long callStart = System.currentTimeMillis();
            String response = assistant.chat(userId, userMessage);
            long callTime = System.currentTimeMillis() - callStart;

            log.info("========================================");
            log.info("✅ LLM PROCESSING COMPLETE");
            log.info("   Response: \"{}\"",
                    response.length() > 150 ? response.substring(0, 150) + "..." : response);
            log.info("⏱️ LOG TIME: LLM Total (with function calls) = {}ms", callTime);
            log.info("========================================");

            return response;

        } catch (Exception e) {
            log.error("❌ AI chat failed: {}", e.getMessage(), e);
            return "I'm sorry, I encountered an error processing your request. " +
                    "Could you please try again or rephrase your question?";
        }
    }

    /**
     * Backward compatible - uses "default" as userId
     */
    public String chat(String userMessage) {
        return chat("default", userMessage);
    }

    /**
     * Clear memory for a specific user
     */
    public void clearMemory(String odId) {
        log.info("🧹 Clearing memory for user: {}", odId);
        userMemories.invalidate(odId);
    }

    /**
     * Get active user count (for monitoring)
     */
    public long getActiveUserCount() {
        return userMemories.estimatedSize();
    }

    /**
     * Get cache stats (for monitoring)
     */
    public String getCacheStats() {
        var stats = userMemories.stats();
        return String.format("Hits: %d, Misses: %d, Evictions: %d, Size: %d",
                stats.hitCount(), stats.missCount(), stats.evictionCount(), userMemories.estimatedSize());
    }
}

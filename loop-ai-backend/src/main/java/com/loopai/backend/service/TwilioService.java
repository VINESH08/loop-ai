package com.loopai.backend.service;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Call;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import com.twilio.type.Twiml;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Twilio Service for forwarding out-of-scope queries to human agents
 * Supports both SMS and Voice calls
 */
@Service
@Slf4j
public class TwilioService {

    @Value("${twilio.account.sid:}")
    private String accountSid;

    @Value("${twilio.auth.token:}")
    private String authToken;

    @Value("${twilio.phone.number:}")
    private String twilioPhoneNumber;

    @Value("${twilio.human.agent.number:}")
    private String humanAgentNumber;

    @Value("${twilio.enabled:false}")
    private boolean enabled;

    private boolean initialized = false;

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("========================================");
            log.info("📱 Twilio Service DISABLED");
            log.info("   Set twilio.enabled=true to enable");
            log.info("========================================");
            return;
        }

        if (accountSid == null || accountSid.isEmpty() ||
                accountSid.equals("your-account-sid")) {
            log.warn("========================================");
            log.warn("⚠️ Twilio credentials not configured!");
            log.warn("   Please set TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN");
            log.warn("========================================");
            return;
        }

        try {
            Twilio.init(accountSid, authToken);
            initialized = true;
            log.info("========================================");
            log.info("📱 Twilio Service Initialized");
            log.info("   From: {}", twilioPhoneNumber);
            log.info("   To (Human Agent): {}", humanAgentNumber);
            log.info("========================================");
        } catch (Exception e) {
            log.error("Failed to initialize Twilio: {}", e.getMessage());
        }
    }

    /**
     * Check if Twilio is properly configured and enabled
     */
    public boolean isAvailable() {
        return enabled && initialized;
    }

    /**
     * Forward to human agent via SMS (async)
     * Returns immediately, sends SMS in background
     */
    public CompletableFuture<Boolean> forwardToHumanAgentAsync(String odId, String userQuery) {
        return CompletableFuture.supplyAsync(() -> {
            return sendSmsToHumanAgent(odId, userQuery);
        });
    }

    /**
     * Send SMS to human agent with user details
     */
    public boolean sendSmsToHumanAgent(String odId, String userQuery) {
        if (!isAvailable()) {
            log.warn("Twilio not available - skipping SMS");
            return false;
        }

        try {
            String messageBody = String.format(
                    "🚨 Loop AI Alert\n\n" +
                            "User needs human assistance.\n\n" +
                            "User: %s\n" +
                            "Query: %s\n\n" +
                            "Please follow up with this user.",
                    odId,
                    truncate(userQuery, 100));

            Message message = Message.creator(
                    new PhoneNumber(humanAgentNumber), // To
                    new PhoneNumber(twilioPhoneNumber), // From
                    messageBody).create();

            log.info("✅ SMS sent to human agent. SID: {}", message.getSid());
            return true;

        } catch (Exception e) {
            log.error("❌ Failed to send SMS to human agent: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Make a voice call to human agent with user details
     * Uses TwiML to speak the message
     */
    public boolean callHumanAgent(String odId, String userQuery) {
        if (!isAvailable()) {
            log.warn("Twilio not available - skipping call");
            return false;
        }

        try {
            String twimlMessage = String.format(
                    "<Response>" +
                            "<Say voice=\"alice\">Alert from Loop AI. A user needs human assistance. " +
                            "User ID: %s. Their query was: %s. " +
                            "Please check the Loop AI dashboard for more details.</Say>" +
                            "</Response>",
                    sanitizeForTwiml(odId),
                    sanitizeForTwiml(truncate(userQuery, 50)));

            Call call = Call.creator(
                    new PhoneNumber(humanAgentNumber), // To
                    new PhoneNumber(twilioPhoneNumber), // From
                    new Twiml(twimlMessage)).create();

            log.info("✅ Call initiated to human agent. SID: {}", call.getSid());
            return true;

        } catch (Exception e) {
            log.error("❌ Failed to call human agent: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Forward using preferred method (SMS by default, faster and cheaper)
     */
    public void forwardToHumanAgent(String odId, String userQuery) {
        if (!isAvailable()) {
            log.info("Twilio disabled - logging forward request instead");
            log.info("🚨 HUMAN AGENT FORWARD REQUEST:");
            log.info("   User: {}", odId);
            log.info("   Query: {}", userQuery);
            return;
        }

        // Use SMS by default (faster, cheaper, non-intrusive)
        forwardToHumanAgentAsync(odId, userQuery);
    }

    /**
     * Truncate string to max length
     */
    private String truncate(String text, int maxLength) {
        if (text == null)
            return "";
        return text.length() > maxLength
                ? text.substring(0, maxLength) + "..."
                : text;
    }

    /**
     * Sanitize text for TwiML (remove special characters that might break XML)
     */
    private String sanitizeForTwiml(String text) {
        if (text == null)
            return "";
        return text
                .replace("&", "and")
                .replace("<", "")
                .replace(">", "")
                .replace("\"", "")
                .replace("'", "");
    }
}

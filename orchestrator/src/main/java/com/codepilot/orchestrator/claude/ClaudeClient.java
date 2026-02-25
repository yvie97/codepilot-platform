package com.codepilot.orchestrator.claude;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around the Anthropic Messages API.
 *
 * This is the foundation of §5.4 (LLM Integration). Right now it handles a
 * single turn; in Phase 1 we'll extend it to manage multi-turn conversation
 * history for the full agent loop.
 *
 * Why raw HttpClient instead of an SDK?
 *   - No extra dependencies — the Claude API is a straightforward REST endpoint
 *   - You see exactly what's on the wire, which makes debugging the agent loop easier
 *   - We can add retry logic (429/503) exactly as the design doc specifies (§5.4)
 */
@Component
public class ClaudeClient {

    // -------------------------------------------------------------------------
    // Data records
    // -------------------------------------------------------------------------

    /**
     * A single message in a conversation.
     * role must be "user" or "assistant" — Claude's API alternates between them.
     */
    public record Message(String role, String content) {}

    /**
     * The subset of the API response we care about.
     * @JsonIgnoreProperties(ignoreUnknown = true) tells Jackson to silently skip
     * any fields we didn't declare — safe and practical for API responses.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessagesResponse(List<ContentBlock> content) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record ContentBlock(String type, String text) {}

        /** Extracts the text from the first content block (the one we always use). */
        public String firstText() {
            return content.stream()
                    .filter(b -> "text".equals(b.type()))
                    .map(ContentBlock::text)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No text block in response"));
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private static final String API_URL   = "https://api.anthropic.com/v1/messages";
    private static final String API_VER   = "2023-06-01";
    private static final int    MAX_TOKENS = 4096;

    private final HttpClient    http;
    private final ObjectMapper  json;
    private final String        apiKey;

    // -------------------------------------------------------------------------
    // Constructor — Spring injects the API key from the environment variable
    // -------------------------------------------------------------------------

    public ClaudeClient(@Value("${anthropic.api-key}") String apiKey,
                        ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.json   = objectMapper;
        this.http   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Send a conversation to Claude and return the assistant's text reply.
     *
     * This is one "turn" of the agent loop from §5.4:
     *
     *   Agent turn N:
     *     Input:  messages  (the full conversation so far)
     *     Output: the assistant's text (a Python code block, or a <result> tag)
     *
     * @param model    e.g. "claude-sonnet-4-6"
     * @param messages the full conversation history so far (user + assistant turns)
     * @return the assistant's text content
     */
    public String complete(String model, List<Message> messages) {
        try {
            // 1. Build the JSON request body
            //    The Anthropic API expects: { model, max_tokens, messages: [{role, content}] }
            String requestBody = json.writeValueAsString(Map.of(
                    "model",      model,
                    "max_tokens", MAX_TOKENS,
                    "messages",   messages
            ));

            // 2. Build the HTTP request with the required headers
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(60))
                    .header("content-type",      "application/json")
                    .header("x-api-key",          apiKey)
                    .header("anthropic-version",  API_VER)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            // 3. Send and get the raw response
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            // 4. Check for API-level errors
            if (response.statusCode() != 200) {
                throw new ClaudeApiException(response.statusCode(), response.body());
            }

            // 5. Parse the JSON and extract the text
            //    Full response shape: { id, type, role, content: [{type, text}], ... }
            MessagesResponse parsed = json.readValue(response.body(), MessagesResponse.class);
            return parsed.firstText();

        } catch (ClaudeApiException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Claude API call failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // Exception type
    // -------------------------------------------------------------------------

    public static class ClaudeApiException extends RuntimeException {
        private final int statusCode;
        public ClaudeApiException(int statusCode, String body) {
            super("Claude API error %d: %s".formatted(statusCode, body));
            this.statusCode = statusCode;
        }
        public int statusCode() { return statusCode; }
    }
}

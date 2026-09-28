package com.cardamage.service;

import com.cardamage.exception.ClaudeApiException;
import com.cardamage.model.DamageAssessment;
import com.cardamage.validation.DamageResponseValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * Single-image call to Claude Vision, used both by the benchmark scenario
 * (one CarDD image at a time, see TZ section 12) and internally by
 * DamageAnalysisService for each image of a multi-photo demo session
 * (merging happens one level up, in DamageMergeService).
 *
 * TODO before first real run:
 *   - Confirm the exact model identifier against current Anthropic docs
 *     (anthropic.model in application.yml). Do not trust a hardcoded
 *     guess - check https://docs.claude.com/en/docs/about-claude/models
 *   - Confirm the official Anthropic Java SDK's Maven coordinates and
 *     request/response shape; this class currently talks to the plain
 *     REST endpoint via WebClient as a placeholder so the skeleton
 *     compiles and is easy to read without a vendor SDK dependency.
 */
@Service
public class ClaudeVisionService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeVisionService.class);

    private final WebClient anthropicWebClient;
    private final ObjectMapper objectMapper;
    private final DamageResponseValidator validator;

    @Value("${anthropic.model}")
    private String model;

    @Value("${anthropic.max-tokens:2048}")
    private int maxTokens;

    public ClaudeVisionService(WebClient anthropicWebClient,
                                ObjectMapper objectMapper,
                                DamageResponseValidator validator) {
        this.anthropicWebClient = anthropicWebClient;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    /**
     * Analyzes a single base64-encoded image and returns a validated
     * DamageAssessment.
     *
     * @throws ClaudeApiException on network failure, unparsable response,
     *         or a response that fails DamageResponseValidator - all three
     *         are treated the same way by the caller (DamageAnalysisService),
     *         which retries on ClaudeApiException.
     */
    public DamageAssessment analyzeSingleImage(String base64Image, String mediaType) {
        String prompt = buildDamageAssessmentPrompt();

        String requestBody;
        try {
            requestBody = buildRequestJson(base64Image, mediaType, prompt);
        } catch (Exception e) {
            throw new ClaudeApiException("Failed to build request payload", e);
        }

        String rawResponseText;
        try {
            String httpResponse = anthropicWebClient.post()
                    .uri("/v1/messages")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            rawResponseText = extractTextFromResponse(httpResponse);
        } catch (Exception e) {
            throw new ClaudeApiException("Claude API call failed", e);
        }

        DamageAssessment assessment;
        try {
            assessment = objectMapper.readValue(rawResponseText, DamageAssessment.class);
        } catch (Exception e) {
            throw new ClaudeApiException("Model response was not valid JSON: " + e.getMessage(), e);
        }

        // Jackson deserialization succeeding does NOT mean the content is
        // valid - explicitly run Bean Validation here.
        String violations = validator.validate(assessment);
        if (violations != null) {
            throw new ClaudeApiException("Model response failed validation: " + violations);
        }

        assessment.setStatus("success");
        return assessment;
    }

    private String buildDamageAssessmentPrompt() {
        return """
                Analyze this car photo for visible damage.

                Return ONLY a JSON object with this exact shape (no prose,
                no markdown fences):

                {
                  "damages": [
                    {
                      "damage_type": one of dent | scratch | crack | glass_shatter | tire_flat | lamp_broken,
                      "part": one of bumper | door | headlight | window | hood | fender | mirror | wheel | windshield | other,
                      "severity": one of minor | moderate | severe,
                      "action": one of repair | replacement,
                      "confidence": number between 0 and 1,
                      "description": short free-text description,
                      "bounding_box": [x, y, width, height] in pixel coordinates, if you can localize it
                    }
                  ],
                  "overall_score": number between 0 and 100
                }

                Severity definitions:
                - minor: cosmetic damage, no functional impact
                - moderate: functional impact, needs repair soon
                - severe: critical damage, safety issue

                If you are not reasonably confident (confidence > 0.5) about
                an instance, omit it rather than guessing. If you see no
                damage, return an empty damages array, not a fabricated one.
                """;
    }

    private String buildRequestJson(String base64Image, String mediaType, String prompt) throws Exception {
        var root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTokens);

        var message = objectMapper.createObjectNode();
        message.put("role", "user");

        var content = objectMapper.createArrayNode();

        var imageBlock = objectMapper.createObjectNode();
        imageBlock.put("type", "image");
        var source = objectMapper.createObjectNode();
        source.put("type", "base64");
        source.put("media_type", mediaType);
        source.put("data", base64Image);
        imageBlock.set("source", source);
        content.add(imageBlock);

        var textBlock = objectMapper.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", prompt);
        content.add(textBlock);

        message.set("content", content);

        var messages = objectMapper.createArrayNode();
        messages.add(message);
        root.set("messages", messages);

        return objectMapper.writeValueAsString(root);
    }

    /**
     * Pulls the text block(s) out of the Anthropic Messages API response
     * envelope. Anthropic's structured-output support (JSON Schema-
     * constrained responses) reduces but does not eliminate the chance of
     * malformed output - hence the explicit validation step above rather
     * than trusting this blindly.
     */
    private String extractTextFromResponse(String httpResponseBody) throws Exception {
        JsonNode root = objectMapper.readTree(httpResponseBody);
        JsonNode contentArray = root.get("content");
        if (contentArray == null || !contentArray.isArray() || contentArray.isEmpty()) {
            throw new ClaudeApiException("Unexpected API response shape: no content array");
        }

        StringBuilder sb = new StringBuilder();
        for (JsonNode block : contentArray) {
            if ("text".equals(block.path("type").asText())) {
                sb.append(block.path("text").asText());
            }
        }

        if (sb.isEmpty()) {
            throw new ClaudeApiException("No text block found in API response");
        }

        return sb.toString();
    }
}

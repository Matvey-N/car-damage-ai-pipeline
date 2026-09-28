package com.cardamage.core.pipeline;

import com.cardamage.core.model.DamageAssessment;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Step 1 of checking a model answer: raw text -> DamageAssessment object.
 *
 * Only checks that the text is JSON that maps onto the expected shape.
 * Whether the values are allowed (enums, ranges, boxes) is checked
 * separately and explicitly by ResponseFormatValidator.
 */
public class ResponseParser {

    private final ObjectMapper objectMapper;

    public ResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DamageAssessment parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new InvalidModelResponseException("empty answer");
        }
        String json = stripMarkdownFence(rawText.trim());
        if (!json.startsWith("{")) {
            throw new InvalidModelResponseException("answer is not a JSON object");
        }
        try {
            DamageAssessment parsed = objectMapper.readValue(json, DamageAssessment.class);
            if (parsed == null) {
                throw new InvalidModelResponseException("answer parsed to null");
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw new InvalidModelResponseException("invalid JSON: " + e.getOriginalMessage(), e);
        }
    }

    /** Models sometimes wrap JSON in ```json ... ``` despite instructions. */
    static String stripMarkdownFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        int closingFence = text.lastIndexOf("```");
        if (firstNewline < 0 || closingFence <= firstNewline) {
            return text;
        }
        return text.substring(firstNewline + 1, closingFence).trim();
    }
}

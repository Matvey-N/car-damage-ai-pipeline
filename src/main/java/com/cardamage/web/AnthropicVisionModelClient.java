package com.cardamage.web;

import com.cardamage.core.pipeline.ModelCallException;
import com.cardamage.core.pipeline.VisionModelClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Base64;

/**
 * Real model client: one POST /v1/messages call per image.
 * Returns only the text blocks of the answer; parsing and format
 * validation happen in the core pipeline (SingleImageAnalyzer).
 */
public class AnthropicVisionModelClient implements VisionModelClient {

    private static final String API_VERSION = "2023-06-01";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final int maxTokens;

    public AnthropicVisionModelClient(String baseUrl, String apiKey, String model, int maxTokens,
                                      int timeoutSeconds, ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10_000);
        requestFactory.setReadTimeout(timeoutSeconds * 1000);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", API_VERSION)
                .build();
        this.objectMapper = objectMapper;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    @Override
    public String analyze(byte[] image, String mediaType, String prompt) {
        String responseBody;
        try {
            responseBody = restClient.post()
                    .uri("/v1/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildRequest(image, mediaType, prompt))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new ModelCallException("HTTP " + e.getStatusCode().value() + ": "
                    + shorten(e.getResponseBodyAsString()), e);
        } catch (RestClientException e) {
            throw new ModelCallException("request failed: " + e.getMessage(), e);
        }
        return extractText(responseBody);
    }

    private String buildRequest(byte[] image, String mediaType, String prompt) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTokens);

        ArrayNode content = objectMapper.createArrayNode();
        ObjectNode imageBlock = content.addObject();
        imageBlock.put("type", "image");
        ObjectNode source = imageBlock.putObject("source");
        source.put("type", "base64");
        source.put("media_type", mediaType);
        source.put("data", Base64.getEncoder().encodeToString(image));

        ObjectNode textBlock = content.addObject();
        textBlock.put("type", "text");
        textBlock.put("text", prompt);

        ObjectNode message = root.putArray("messages").addObject();
        message.put("role", "user");
        message.set("content", content);

        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new ModelCallException("could not build request", e);
        }
    }

    /** Joins the text blocks; thinking blocks and other block types are skipped. */
    private String extractText(String responseBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception e) {
            throw new ModelCallException("API response is not JSON", e);
        }
        if ("max_tokens".equals(root.path("stop_reason").asText())) {
            throw new ModelCallException("answer was truncated (stop_reason = max_tokens)");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }
        if (text.length() == 0) {
            throw new ModelCallException("API response contains no text block");
        }
        return text.toString();
    }

    private static String shorten(String s) {
        return s == null || s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}

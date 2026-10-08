package com.cardamage.web;

import com.cardamage.core.demo.DamageMergeService;
import com.cardamage.core.pipeline.DownscalingImagePreprocessor;
import com.cardamage.core.pipeline.ResponseFormatValidator;
import com.cardamage.core.pipeline.ResponseParser;
import com.cardamage.core.pipeline.SingleImageAnalyzer;
import com.cardamage.core.pipeline.StubVisionModelClient;
import com.cardamage.core.pipeline.TiledImageAnalyzer;
import com.cardamage.core.pipeline.VisionModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the Spring-free core pipeline into the application. */
@Configuration
public class PipelineConfig {

    @Bean
    public VisionModelClient visionModelClient(
            @Value("${pipeline.model-client}") String clientType,
            @Value("${anthropic.base-url}") String baseUrl,
            @Value("${anthropic.api-key:}") String apiKey,
            @Value("${anthropic.model}") String model,
            @Value("${anthropic.max-tokens}") int maxTokens,
            @Value("${anthropic.timeout-seconds}") int timeoutSeconds,
            ObjectMapper objectMapper) {
        switch (clientType) {
            case "stub":
                return StubVisionModelClient.withDefaultAnswer();
            case "anthropic":
                if (apiKey == null || apiKey.isBlank()) {
                    throw new IllegalStateException(
                            "pipeline.model-client=anthropic requires the ANTHROPIC_API_KEY environment variable");
                }
                return new AnthropicVisionModelClient(baseUrl, apiKey, model, maxTokens, timeoutSeconds, objectMapper);
            default:
                throw new IllegalStateException("Unknown pipeline.model-client: " + clientType
                        + " (expected 'stub' or 'anthropic')");
        }
    }

    @Bean
    public SingleImageAnalyzer singleImageAnalyzer(
            VisionModelClient client,
            ObjectMapper objectMapper,
            @Value("${pipeline.max-attempts}") int maxAttempts,
            @Value("${pipeline.retry-backoff-ms}") long backoffMs,
            @Value("${pipeline.image.max-side}") int maxSide,
            @Value("${pipeline.image.max-bytes}") long maxBytes) {
        return new SingleImageAnalyzer(client, new ResponseParser(objectMapper),
                new ResponseFormatValidator(), maxAttempts, backoffMs, Thread::sleep,
                new DownscalingImagePreprocessor(maxSide, maxBytes, 0.9f));
    }

    @Bean
    public TiledImageAnalyzer tiledImageAnalyzer(
            SingleImageAnalyzer single,
            @Value("${pipeline.tiling.rows}") int rows,
            @Value("${pipeline.tiling.cols}") int cols,
            @Value("${pipeline.tiling.overlap}") double overlap,
            @Value("${pipeline.tiling.include-full-image}") boolean includeFullImage,
            @Value("${pipeline.tiling.merge-iou}") double mergeIou) {
        return new TiledImageAnalyzer(single, rows, cols, overlap, includeFullImage, mergeIou);
    }

    @Bean
    public DamageMergeService damageMergeService() {
        return new DamageMergeService();
    }
}

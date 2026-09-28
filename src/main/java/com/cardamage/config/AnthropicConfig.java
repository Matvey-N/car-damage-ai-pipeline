package com.cardamage.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * HTTP client configuration for the Anthropic Messages API.
 *
 * NOTE: this uses a plain WebClient against the public REST endpoint
 * rather than a vendor SDK, because the exact Maven coordinates / API
 * shape of the official Anthropic Java SDK were not verified at the time
 * this skeleton was written (see open TODO in ClaudeVisionService). Swap
 * this for the official SDK once confirmed, or keep the WebClient
 * implementation if the SDK doesn't fit - either is fine functionally.
 *
 * Model identifier: read from configuration (anthropic.model in
 * application.yml), not hardcoded, so it can be corrected without a code
 * change once verified against current Anthropic documentation.
 */
@Configuration
public class AnthropicConfig {

    @Value("${anthropic.api-key:}")
    private String apiKey;

    @Value("${anthropic.base-url:https://api.anthropic.com}")
    private String baseUrl;

    @Bean
    public WebClient anthropicWebClient() {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("content-type", "application/json")
                .build();
    }
}

package com.cardamage.service;

import com.cardamage.exception.ClaudeApiException;
import com.cardamage.model.DamageAssessment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates a single-image analysis call with retry + fallback.
 *
 * Used directly by the benchmark scenario (one image in, one
 * DamageAssessment out). Used per-image by the demo scenario, whose
 * multi-photo merging lives in DamageMergeService - kept separate so the
 * two scenarios' methodologies stay independent, per TZ section 12.
 */
@Service
public class DamageAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(DamageAnalysisService.class);
    private static final int MAX_ATTEMPTS = 3;

    private final ClaudeVisionService claudeVisionService;

    public DamageAnalysisService(ClaudeVisionService claudeVisionService) {
        this.claudeVisionService = claudeVisionService;
    }

    @Retryable(
            retryFor = ClaudeApiException.class,
            maxAttempts = MAX_ATTEMPTS,
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public DamageAssessment analyzeImage(String base64Image, String mediaType) {
        return claudeVisionService.analyzeSingleImage(base64Image, mediaType);
    }

    /**
     * Invoked by Spring Retry once all {@link #MAX_ATTEMPTS} attempts have
     * thrown {@link ClaudeApiException}. Returns a well-formed
     * DamageAssessment with status="error" - a valid JSON response,
     * distinct from "no damage found" (status="success", empty damages).
     * Callers must check {@link DamageAssessment#isSuccess()} before
     * treating an empty damages list as a real finding.
     */
    @Recover
    public DamageAssessment recoverFromFailedAnalysis(ClaudeApiException e, String base64Image, String mediaType) {
        log.warn("All {} attempts to analyze image failed: {}", MAX_ATTEMPTS, e.getMessage());
        return DamageAssessment.error(
                "Could not analyze image after " + MAX_ATTEMPTS + " attempts: " + e.getMessage()
        );
    }
}

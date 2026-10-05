package com.cardamage.core.pipeline;

import com.cardamage.core.model.DamageAssessment;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The core of the pipeline: analyzes exactly one image.
 *
 * Used as-is by the benchmark (one CarDD image per call) and once per
 * photo by the demo scenario.
 *
 * Flow per attempt:
 *   model call -> ResponseParser (is it JSON of the right shape?)
 *              -> ResponseFormatValidator (are all values allowed?)
 * A failure at any stage triggers another attempt, up to maxAttempts.
 * If all attempts fail, a well-formed status="error" result is returned
 * (fallback) instead of throwing, so the caller always gets valid JSON.
 */
public class SingleImageAnalyzer {

    public static final Set<String> SUPPORTED_MEDIA_TYPES = Set.of("image/jpeg", "image/png");

    /** Sleep abstraction so tests do not actually wait between attempts. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final VisionModelClient client;
    private final ResponseParser parser;
    private final ResponseFormatValidator validator;
    private final int maxAttempts;
    private final long backoffMillis;
    private final Sleeper sleeper;
    private final ImagePreprocessor preprocessor;

    public SingleImageAnalyzer(VisionModelClient client, ResponseParser parser,
                               ResponseFormatValidator validator,
                               int maxAttempts, long backoffMillis, Sleeper sleeper) {
        this(client, parser, validator, maxAttempts, backoffMillis, sleeper, ImagePreprocessor.NONE);
    }

    public SingleImageAnalyzer(VisionModelClient client, ResponseParser parser,
                               ResponseFormatValidator validator,
                               int maxAttempts, long backoffMillis, Sleeper sleeper,
                               ImagePreprocessor preprocessor) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.client = client;
        this.parser = parser;
        this.validator = validator;
        this.maxAttempts = maxAttempts;
        this.backoffMillis = backoffMillis;
        this.sleeper = sleeper;
        this.preprocessor = preprocessor;
    }

    /**
     * @throws IllegalArgumentException for bad input (empty image,
     *         unsupported media type) - that is a caller error, not a
     *         model failure, so it is not retried.
     */
    public DamageAssessment analyze(byte[] image, String mediaType) {
        if (image == null || image.length == 0) {
            throw new IllegalArgumentException("image is empty");
        }
        if (mediaType == null || !SUPPORTED_MEDIA_TYPES.contains(mediaType)) {
            throw new IllegalArgumentException("unsupported media type: " + mediaType
                    + " (supported: " + SUPPORTED_MEDIA_TYPES + ")");
        }

        PreparedImage prepared = preprocessor.prepare(image, mediaType);

        List<String> failureLog = new ArrayList<>();
        List<String> lastViolations = List.of();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1) {
                pause(attempt);
            }
            try {
                String raw = client.analyze(prepared.bytes(), prepared.mediaType(),
                        DamagePrompt.forAttempt(attempt, lastViolations));
                DamageAssessment parsed = parser.parse(raw);
                List<String> violations = validator.validate(parsed);
                if (violations.isEmpty()) {
                    return DamageAssessment.success(parsed.getDamages(), parsed.getOverallScore(), attempt);
                }
                lastViolations = violations;
                failureLog.add("attempt " + attempt + ": invalid format: " + String.join("; ", violations));
            } catch (InvalidModelResponseException e) {
                lastViolations = List.of(e.getMessage());
                failureLog.add("attempt " + attempt + ": unparseable answer: " + e.getMessage());
            } catch (ModelCallException e) {
                lastViolations = List.of();
                failureLog.add("attempt " + attempt + ": model call failed: " + e.getMessage());
            }
        }

        return DamageAssessment.error(
                "No valid answer after " + maxAttempts + " attempts. " + String.join(" | ", failureLog),
                maxAttempts);
    }

    private void pause(int attempt) {
        long delay = backoffMillis * (1L << (attempt - 2)); // 1x, 2x, 4x ...
        if (delay <= 0) {
            return;
        }
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

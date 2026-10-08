package com.cardamage.core.pipeline;

import com.cardamage.core.model.DamageAssessment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The core of the pipeline: analyzes exactly one image.
 *
 * Used as-is by the benchmark (one image per call, profile BENCHMARK) and
 * once per photo by the Mini App and the bot (profile GENERAL). Stateless
 * apart from its settings, so it may be called from several threads.
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
    private final Map<String, ResponseFormatValidator> validators = new ConcurrentHashMap<>();

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

    /** Benchmark profile (frozen prompt v3). */
    public DamageAssessment analyze(byte[] image, String mediaType) {
        return analyze(image, mediaType, AnalysisProfile.BENCHMARK);
    }

    /**
     * @throws IllegalArgumentException for bad input (empty image,
     *         unsupported media type) - that is a caller error, not a
     *         model failure, so it is not retried.
     */
    public DamageAssessment analyze(byte[] image, String mediaType, AnalysisProfile profile) {
        return analyzeWithPrompt(image, mediaType, profile, profile.prompt());
    }

    /**
     * Same pipeline with a different base prompt (e.g. the second look of
     * RelookAnalyzer); values are checked against the profile's taxonomy.
     */
    public DamageAssessment analyzeWithPrompt(byte[] image, String mediaType, AnalysisProfile profile, String basePrompt) {
        if (image == null || image.length == 0) {
            throw new IllegalArgumentException("image is empty");
        }
        if (mediaType == null || !SUPPORTED_MEDIA_TYPES.contains(mediaType)) {
            throw new IllegalArgumentException("unsupported media type: " + mediaType
                    + " (supported: " + SUPPORTED_MEDIA_TYPES + ")");
        }
        ResponseFormatValidator check = profile == AnalysisProfile.BENCHMARK ? validator
                : validators.computeIfAbsent(profile.name(), k -> profile.validator());

        PreparedImage prepared = preprocessor.prepare(image, mediaType);

        List<String> failureLog = new ArrayList<>();
        List<String> lastViolations = List.of();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1) {
                pause(attempt);
            }
            try {
                String raw = client.analyze(prepared.bytes(), prepared.mediaType(),
                        DamagePrompt.withCorrections(basePrompt, attempt, lastViolations));
                DamageAssessment parsed = parser.parse(raw);
                if (Boolean.FALSE.equals(parsed.getVehicleVisible()) && parsed.getDamages() == null) {
                    parsed.setDamages(new ArrayList<>());
                }
                List<String> violations = check.validate(parsed);
                if (violations.isEmpty()) {
                    DamageAssessment ok = DamageAssessment.success(parsed.getDamages(), parsed.getOverallScore(), attempt);
                    ok.setVehicleVisible(parsed.getVehicleVisible());
                    return ok;
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

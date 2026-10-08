package com.cardamage.core.pipeline;

import java.util.Locale;
import java.util.Set;

/**
 * What the model is asked to find: the prompt plus the allowed values.
 *
 *   BENCHMARK - frozen prompt v3, four SYNDCAR damage types; the only profile
 *               the benchmark numbers are reported for (default of /api/v1/analyze).
 *   GENERAL   - prompt g1 for real photos of any car, extended taxonomy
 *               (dents, paint chips, rust, broken parts); Mini App and bot.
 */
public record AnalysisProfile(String name, String promptVersion, String prompt,
                              Set<String> damageTypes, Set<String> parts) {

    public static final AnalysisProfile BENCHMARK = new AnalysisProfile("benchmark", DamagePrompt.VERSION,
            DamagePrompt.BASE, ResponseFormatValidator.DAMAGE_TYPES, ResponseFormatValidator.PARTS);

    public static final AnalysisProfile GENERAL = new AnalysisProfile("general", GeneralPrompt.VERSION,
            GeneralPrompt.BASE, ResponseFormatValidator.GENERAL_DAMAGE_TYPES, ResponseFormatValidator.GENERAL_PARTS);

    public static AnalysisProfile byName(String name) {
        String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        return switch (n) {
            case "", "benchmark" -> BENCHMARK;
            case "general" -> GENERAL;
            default -> throw new IllegalArgumentException("unknown profile '" + name + "' (expected 'benchmark' or 'general')");
        };
    }

    public ResponseFormatValidator validator() {
        return new ResponseFormatValidator(damageTypes, parts);
    }
}

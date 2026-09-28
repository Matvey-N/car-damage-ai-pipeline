package com.cardamage.core.pipeline;

import java.util.List;

/**
 * The prompt sent to the model.
 *
 * VERSION must be bumped on every change. The benchmark report records
 * which version produced the predictions; the version used for the test
 * set is frozen after dev-set tuning (TZ section 8).
 */
public final class DamagePrompt {

    public static final String VERSION = "v1";

    public static final String BASE = """
            You are inspecting a single photo of a car for VISIBLE exterior damage.

            Return ONLY one JSON object, no prose and no markdown, with exactly this shape:

            {
              "damages": [
                {
                  "damage_type": "dent" | "scratch" | "crack" | "glass_shatter" | "tire_flat" | "lamp_broken",
                  "part": "bumper" | "door" | "headlight" | "window" | "hood" | "fender" | "mirror" | "wheel" | "windshield" | "other",
                  "severity": "minor" | "moderate" | "severe",
                  "action": "repair" | "replacement",
                  "confidence": number from 0 to 1,
                  "description": short text,
                  "bounding_box": [x, y, w, h]
                }
              ],
              "overall_score": number from 0 to 100
            }

            Rules:
            - One entry per separate damage region. Do not list the same region twice.
            - bounding_box is the tight box around that damage region, as fractions of the
              image size: x and y are the top-left corner, w and h the width and height,
              all between 0 and 1 (e.g. [0.25, 0.4, 0.2, 0.1]).
            - confidence is your probability that this entry is a real damage of the stated type.
            - severity: minor = cosmetic only; moderate = clearly visible, needs repair;
              severe = broken, deformed or safety-relevant.
            - action: replacement if the part is broken/shattered or beyond repair, otherwise repair.
            - Only report damage you can actually see. If there is none, return "damages": [].
            """;

    private DamagePrompt() {
    }

    /**
     * Prompt for attempt N. From the second attempt on, the previous
     * format errors are appended so the model can correct them.
     */
    public static String forAttempt(int attempt, List<String> previousViolations) {
        if (attempt <= 1 || previousViolations == null || previousViolations.isEmpty()) {
            return BASE;
        }
        return BASE + "\nYour previous answer was rejected for these reasons: "
                + String.join("; ", previousViolations)
                + "\nReturn a corrected JSON object only.\n";
    }
}

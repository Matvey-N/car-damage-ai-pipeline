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

    public static final String VERSION = "v3";

    public static final String BASE = """
            You are inspecting a single photo of a car for VISIBLE exterior damage.

            Return ONLY one JSON object, no prose and no markdown, with exactly this shape:

            {
              "damages": [
                {
                  "damage_type": "glass_shatter" | "lamp_broken" | "crack" | "scratch",
                  "part": "bumper" | "door" | "light" | "window" | "windshield" | "hood" | "fender" | "mirror" | "wheel" | "other",
                  "severity": "minor" | "moderate" | "severe",
                  "action": "repair" | "replacement",
                  "confidence": number from 0 to 1,
                  "description": short text,
                  "bounding_box": [x, y, w, h]
                }
              ],
              "overall_score": number from 0 to 100
            }

            Damage types:
            - glass_shatter: any breakage of window or windshield glass: an impact point,
              a star-shaped or radiating crack pattern, or shattered glass. Damage to glass
              is glass_shatter, not crack.
            - lamp_broken: broken or cracked headlight or tail light (lens or housing).
            - crack: a crack line in a body panel, bumper or other non-glass part.
            - scratch: a scratch or scrape in the paint or surface.
            Report only these four types.

            Parts: "light" means a headlight or tail light; "fender" includes quarter panels;
            "bumper" includes the front and rear panels; use "other" for roof, rocker panel,
            license plate or anything else.

            Rules:
            - Inspect the whole car systematically, part by part, and report EVERY separate
              damage, including small ones. A photo often shows five or more.
            - One entry per separate damage region. Do not list the same region twice, and do
              not merge separate damages into one entry, even if they are on the same part.
            - bounding_box encloses the full extent of that damage region (for a crack pattern,
              all of its lines to their ends, not only the centre), without extra margin.
              It is given as fractions of the image size: x and y are the top-left corner,
              w and h the width and height, all between 0 and 1 (e.g. [0.25, 0.4, 0.2, 0.1]).
            - confidence is your probability that this entry is a real damage of the stated type.
            - severity:
              minor = small scratch or scuff (under about 5 cm), or a short crack that does not spread;
              moderate = long or deep scratch with paint damage, or a crack in a part that is
              still in one piece;
              severe = shattered or broken glass, a broken light, or a crack that threatens
              the part or safety.
            - action: repair for scratches and for cracks in a part that is not broken;
              replacement for broken glass, broken lights and heavily damaged parts.
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

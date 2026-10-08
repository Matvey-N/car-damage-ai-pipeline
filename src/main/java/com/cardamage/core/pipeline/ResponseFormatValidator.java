package com.cardamage.core.pipeline;

import com.cardamage.core.model.Damage;
import com.cardamage.core.model.DamageAssessment;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Step 2 of checking a model answer: are all values allowed?
 *
 * This check is called explicitly by SingleImageAnalyzer right after
 * parsing. Nothing here runs automatically - there are no validation
 * annotations whose effect would depend on framework configuration.
 * The rules below are the single source of truth for the answer format
 * (they mirror src/main/resources/schema/damage-assessment-schema.json).
 */
public class ResponseFormatValidator {

    /** Damage types of the SYNDCAR dataset (broken glass, broken lights, cracks, scratches). */
    public static final Set<String> DAMAGE_TYPES =
            Set.of("glass_shatter", "lamp_broken", "crack", "scratch");

    public static final Set<String> PARTS =
            Set.of("bumper", "door", "light", "window", "windshield", "hood",
                    "fender", "mirror", "wheel", "other");

    public static final Set<String> SEVERITIES = Set.of("minor", "moderate", "severe");

    public static final Set<String> ACTIONS = Set.of("repair", "replacement");

    /**
     * Extended taxonomy for photos of any car (profile "general", Mini App and bot).
     * A superset of the benchmark sets: the benchmark keeps its four SYNDCAR types.
     */
    public static final Set<String> GENERAL_DAMAGE_TYPES = Set.of(
            "glass_shatter", "lamp_broken", "crack", "scratch", "dent", "paint_chip", "rust", "broken_part");

    public static final Set<String> GENERAL_PARTS = Set.of(
            "bumper", "door", "light", "window", "windshield", "hood", "fender", "mirror", "wheel", "other",
            "trunk", "roof", "grille", "sill");

    private final Set<String> damageTypes;
    private final Set<String> parts;

    /** The benchmark taxonomy (four SYNDCAR damage types). */
    public ResponseFormatValidator() {
        this(DAMAGE_TYPES, PARTS);
    }

    public ResponseFormatValidator(Set<String> damageTypes, Set<String> parts) {
        this.damageTypes = Set.copyOf(damageTypes);
        this.parts = Set.copyOf(parts);
    }

    /** Validator accepting the extended taxonomy (also accepts every benchmark answer). */
    public static ResponseFormatValidator general() {
        return new ResponseFormatValidator(GENERAL_DAMAGE_TYPES, GENERAL_PARTS);
    }

    /** Tolerance for x + w <= 1 and y + h <= 1 (floating point rounding). */
    private static final double BOX_EPS = 1e-3;

    /** @return list of violations; empty list means the answer is valid */
    public List<String> validate(DamageAssessment answer) {
        List<String> violations = new ArrayList<>();
        if (answer == null) {
            violations.add("answer is null");
            return violations;
        }

        Double score = answer.getOverallScore();
        if (score == null) {
            violations.add("overall_score is missing");
        } else if (score < 0 || score > 100) {
            violations.add("overall_score must be in [0, 100], was " + score);
        }

        if (answer.getDamages() == null) {
            violations.add("damages is missing");
            return violations;
        }

        for (int i = 0; i < answer.getDamages().size(); i++) {
            validateDamage(answer.getDamages().get(i), "damages[" + i + "]", violations);
        }
        return violations;
    }

    private void validateDamage(Damage d, String path, List<String> violations) {
        if (d == null) {
            violations.add(path + " is null");
            return;
        }
        checkEnum(d.getDamageType(), damageTypes, path + ".damage_type", violations);
        checkEnum(d.getPart(), parts, path + ".part", violations);
        checkEnum(d.getSeverity(), SEVERITIES, path + ".severity", violations);
        checkEnum(d.getAction(), ACTIONS, path + ".action", violations);

        Double c = d.getConfidence();
        if (c == null) {
            violations.add(path + ".confidence is missing");
        } else if (c < 0 || c > 1) {
            violations.add(path + ".confidence must be in [0, 1], was " + c);
        }

        validateBox(d.getBoundingBox(), path + ".bounding_box", violations);
    }

    /**
     * The box is required: without it a prediction cannot be matched to
     * a ground-truth damage in the benchmark (TZ section 7).
     */
    private void validateBox(List<Double> box, String path, List<String> violations) {
        if (box == null) {
            violations.add(path + " is missing");
            return;
        }
        if (box.size() != 4) {
            violations.add(path + " must have 4 values [x, y, w, h], had " + box.size());
            return;
        }
        for (Double v : box) {
            if (v == null || v < 0 || v > 1) {
                violations.add(path + " values must be normalized to [0, 1], was " + box);
                return;
            }
        }
        double x = box.get(0), y = box.get(1), w = box.get(2), h = box.get(3);
        if (w <= 0 || h <= 0) {
            violations.add(path + " width and height must be > 0, was " + box);
        }
        if (x + w > 1 + BOX_EPS || y + h > 1 + BOX_EPS) {
            violations.add(path + " extends outside the image, was " + box);
        }
    }

    private void checkEnum(String value, Set<String> allowed, String path, List<String> violations) {
        if (value == null) {
            violations.add(path + " is missing");
        } else if (!allowed.contains(value)) {
            violations.add(path + " has unknown value '" + value + "'");
        }
    }
}

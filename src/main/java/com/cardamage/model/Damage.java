package com.cardamage.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * One detected damage instance, as returned by the model and as used in
 * ground truth files for the benchmark.
 *
 * NOTE ON VALIDATION: putting @Pattern / @DecimalMin annotations on the
 * fields below does NOT validate anything by itself. Bean Validation only
 * runs when something explicitly triggers it - either:
 *   (a) a @Validated parameter on a Spring-managed method (e.g. a
 *       controller argument, or a service method annotated with
 *       @Validated at the class level), or
 *   (b) an explicit call to Validator.validate(object) from code
 *       (see DamageResponseValidator).
 * Deserializing JSON into this POJO via Jackson does NOT run these
 * annotations. The explicit validation step is implemented in
 * DamageResponseValidator, called right after Jackson deserialization in
 * ClaudeVisionService.
 */
public class Damage {

    public static final String[] VALID_DAMAGE_TYPES = {
            "dent", "scratch", "crack", "glass_shatter", "tire_flat", "lamp_broken"
    };

    public static final String[] VALID_PARTS = {
            "bumper", "door", "headlight", "window", "hood",
            "fender", "mirror", "wheel", "windshield", "other"
    };

    public static final String[] VALID_SEVERITIES = {"minor", "moderate", "severe"};

    public static final String[] VALID_ACTIONS = {"repair", "replacement"};

    @NotBlank
    @Pattern(regexp = "dent|scratch|crack|glass_shatter|tire_flat|lamp_broken")
    @JsonProperty("damage_type")
    private String damageType;

    @NotBlank
    @Pattern(regexp = "bumper|door|headlight|window|hood|fender|mirror|wheel|windshield|other")
    @JsonProperty("part")
    private String part;

    @NotBlank
    @Pattern(regexp = "minor|moderate|severe")
    @JsonProperty("severity")
    private String severity;

    @NotBlank
    @Pattern(regexp = "repair|replacement")
    @JsonProperty("action")
    private String action;

    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    @JsonProperty("confidence")
    private Double confidence;

    @JsonProperty("description")
    private String description;

    /**
     * Optional bounding box [x, y, w, h] in pixel coordinates of the source
     * image. Populated by the model when available and used only for the
     * benchmark's prediction-to-ground-truth matching (IoU); not required
     * for the demo scenario.
     */
    @JsonProperty("bounding_box")
    private double[] boundingBox;

    public Damage() {
    }

    public String getDamageType() {
        return damageType;
    }

    public void setDamageType(String damageType) {
        this.damageType = damageType;
    }

    public String getPart() {
        return part;
    }

    public void setPart(String part) {
        this.part = part;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public double[] getBoundingBox() {
        return boundingBox;
    }

    public void setBoundingBox(double[] boundingBox) {
        this.boundingBox = boundingBox;
    }
}

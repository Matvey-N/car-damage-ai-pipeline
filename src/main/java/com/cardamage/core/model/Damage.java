package com.cardamage.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One damage instance found on one image.
 *
 * This class carries data only. It is NOT validated by being deserialized:
 * format checks are done explicitly by ResponseFormatValidator.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Damage {

    @JsonProperty("damage_type")
    private String damageType;

    @JsonProperty("part")
    private String part;

    @JsonProperty("severity")
    private String severity;

    @JsonProperty("action")
    private String action;

    @JsonProperty("confidence")
    private Double confidence;

    @JsonProperty("description")
    private String description;

    /**
     * Normalized box [x, y, w, h], each value in [0, 1] relative to the
     * image width/height. Normalized (not pixel) coordinates are used
     * because the API may downscale large images before the model sees
     * them, which would make pixel coordinates ambiguous.
     */
    @JsonProperty("bounding_box")
    private List<Double> boundingBox;

    public Damage() {
    }

    public Damage(String damageType, String part, String severity, String action,
                  Double confidence, List<Double> boundingBox) {
        this.damageType = damageType;
        this.part = part;
        this.severity = severity;
        this.action = action;
        this.confidence = confidence;
        this.boundingBox = boundingBox;
    }

    public String getDamageType() { return damageType; }
    public void setDamageType(String damageType) { this.damageType = damageType; }

    public String getPart() { return part; }
    public void setPart(String part) { this.part = part; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<Double> getBoundingBox() { return boundingBox; }
    public void setBoundingBox(List<Double> boundingBox) { this.boundingBox = boundingBox; }
}

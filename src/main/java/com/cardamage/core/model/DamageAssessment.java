package com.cardamage.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of analyzing one image (or, in the demo scenario, the merged
 * result of several photos).
 *
 * Two outcomes share this shape and are told apart by {@code status}:
 *   status = "success" - the model answered and the answer passed format
 *                        validation. An empty damages list here means
 *                        "no visible damage found".
 *   status = "error"   - no valid answer after all attempts. damages is
 *                        empty but this does NOT mean "no damage";
 *                        error_message explains what failed.
 *
 * The model itself only returns damages + overall_score; status,
 * error_message and attempts are set by the pipeline.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DamageAssessment {

    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_ERROR = "error";

    @JsonProperty("status")
    private String status;

    @JsonProperty("damages")
    private List<Damage> damages;

    @JsonProperty("overall_score")
    private Double overallScore;

    @JsonProperty("error_message")
    private String errorMessage;

    /** Number of model calls used to obtain this result (1..maxAttempts). */
    @JsonProperty("attempts")
    private Integer attempts;

    public DamageAssessment() {
    }

    public static DamageAssessment success(List<Damage> damages, double overallScore, int attempts) {
        DamageAssessment a = new DamageAssessment();
        a.status = STATUS_SUCCESS;
        a.damages = damages;
        a.overallScore = overallScore;
        a.attempts = attempts;
        return a;
    }

    public static DamageAssessment error(String message, int attempts) {
        DamageAssessment a = new DamageAssessment();
        a.status = STATUS_ERROR;
        a.damages = new ArrayList<>();
        a.overallScore = 0.0;
        a.errorMessage = message;
        a.attempts = attempts;
        return a;
    }

    @JsonIgnore
    public boolean isSuccess() {
        return STATUS_SUCCESS.equals(status);
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public List<Damage> getDamages() { return damages; }
    public void setDamages(List<Damage> damages) { this.damages = damages; }

    public Double getOverallScore() { return overallScore; }
    public void setOverallScore(Double overallScore) { this.overallScore = overallScore; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }
}

package com.cardamage.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Full pipeline output. Two flavours share this same shape, distinguished
 * by {@link #status}:
 *
 *  - status = "success": a real analysis result, damages/overallScore are
 *    the model's findings.
 *  - status = "error": a valid JSON object returned after retries were
 *    exhausted (see DamageAnalysisService). damages is empty, overallScore
 *    is 0, and errorMessage explains what happened. This is NOT the same
 *    thing as "analysis found no damage" - callers must check status
 *    before interpreting an empty damages list as "car is undamaged".
 */
public class DamageAssessment {

    @NotNull
    @Valid
    @JsonProperty("damages")
    private List<Damage> damages = new ArrayList<>();

    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    @JsonProperty("overall_score")
    private Double overallScore;

    /** "success" or "error". See class javadoc. */
    @NotNull
    @JsonProperty("status")
    private String status;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("error_message")
    private String errorMessage;

    public DamageAssessment() {
    }

    public static DamageAssessment success(List<Damage> damages, double overallScore) {
        DamageAssessment a = new DamageAssessment();
        a.damages = damages;
        a.overallScore = overallScore;
        a.status = "success";
        return a;
    }

    public static DamageAssessment error(String message) {
        DamageAssessment a = new DamageAssessment();
        a.damages = new ArrayList<>();
        a.overallScore = 0.0;
        a.status = "error";
        a.errorMessage = message;
        return a;
    }

    public boolean isSuccess() {
        return "success".equals(status);
    }

    public List<Damage> getDamages() {
        return damages;
    }

    public void setDamages(List<Damage> damages) {
        this.damages = damages;
    }

    public Double getOverallScore() {
        return overallScore;
    }

    public void setOverallScore(Double overallScore) {
        this.overallScore = overallScore;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}

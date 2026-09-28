package com.cardamage.validation;

import com.cardamage.model.DamageAssessment;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Explicitly runs Bean Validation against a deserialized model response.
 *
 * Why this class exists: putting @Pattern / @DecimalMin / @NotBlank on the
 * Damage and DamageAssessment fields does nothing by itself. Jackson
 * deserialization does not invoke Bean Validation. Validation only runs
 * when something calls it - either a @Validated-annotated Spring method
 * argument, or an explicit Validator.validate(...) call, as done here.
 *
 * This is the single place in the pipeline where "does this JSON actually
 * satisfy the schema" is checked. ClaudeVisionService calls this right
 * after Jackson parses the raw response, before treating the result as
 * usable.
 */
@Component
public class DamageResponseValidator {

    private final Validator validator;

    public DamageResponseValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        this.validator = factory.getValidator();
    }

    /**
     * @return null if valid; otherwise a human-readable description of the
     *         first violations found (used for logging and for deciding
     *         whether to retry).
     */
    public String validate(DamageAssessment assessment) {
        if (assessment == null) {
            return "response was null / not deserializable";
        }

        Set<ConstraintViolation<DamageAssessment>> violations = validator.validate(assessment);
        if (violations.isEmpty()) {
            return null;
        }

        return violations.stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage() + " (was: " + v.getInvalidValue() + ")")
                .collect(Collectors.joining("; "));
    }

    public boolean isValid(DamageAssessment assessment) {
        return validate(assessment) == null;
    }
}

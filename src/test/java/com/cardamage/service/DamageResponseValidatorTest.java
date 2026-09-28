package com.cardamage.service;

import com.cardamage.model.Damage;
import com.cardamage.model.DamageAssessment;
import com.cardamage.validation.DamageResponseValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DamageResponseValidatorTest {

    private final DamageResponseValidator validator = new DamageResponseValidator();

    @Test
    void validAssessmentPassesValidation() {
        Damage d = new Damage();
        d.setDamageType("dent");
        d.setPart("bumper");
        d.setSeverity("moderate");
        d.setAction("repair");
        d.setConfidence(0.9);

        DamageAssessment assessment = DamageAssessment.success(List.of(d), 60.0);

        assertNull(validator.validate(assessment), "well-formed assessment should have no violations");
    }

    @Test
    void confidenceOutOfRangeFailsValidation() {
        Damage d = new Damage();
        d.setDamageType("dent");
        d.setPart("bumper");
        d.setSeverity("moderate");
        d.setAction("repair");
        d.setConfidence(1.5); // invalid: > 1.0

        DamageAssessment assessment = DamageAssessment.success(List.of(d), 60.0);

        assertNotNull(validator.validate(assessment), "confidence > 1.0 must be rejected");
    }

    @Test
    void invalidEnumValueFailsValidation() {
        Damage d = new Damage();
        d.setDamageType("totally_made_up_type"); // not in the allowed enum
        d.setPart("bumper");
        d.setSeverity("moderate");
        d.setAction("repair");
        d.setConfidence(0.8);

        DamageAssessment assessment = DamageAssessment.success(List.of(d), 60.0);

        assertNotNull(validator.validate(assessment), "unknown damage_type must be rejected");
    }

    @Test
    void nullResponseFailsValidation() {
        assertNotNull(validator.validate(null));
    }
}

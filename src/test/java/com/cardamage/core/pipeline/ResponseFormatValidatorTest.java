package com.cardamage.core.pipeline;

import com.cardamage.core.model.Damage;
import com.cardamage.core.model.DamageAssessment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResponseFormatValidatorTest {

    private final ResponseFormatValidator validator = new ResponseFormatValidator();

    private static Damage validDamage() {
        return new Damage("dent", "door", "moderate", "repair", 0.8, List.of(0.1, 0.2, 0.3, 0.2));
    }

    private static DamageAssessment answerWith(Damage d) {
        DamageAssessment a = new DamageAssessment();
        a.setDamages(new ArrayList<>(List.of(d)));
        a.setOverallScore(40.0);
        return a;
    }

    @Test
    void validAnswerHasNoViolations() {
        assertTrue(validator.validate(answerWith(validDamage())).isEmpty());
    }

    @Test
    void emptyDamageListIsValid() {
        DamageAssessment a = new DamageAssessment();
        a.setDamages(new ArrayList<>());
        a.setOverallScore(0.0);
        assertTrue(validator.validate(a).isEmpty(), "'no damage found' is a valid answer");
    }

    @Test
    void unknownDamageTypeIsRejected() {
        Damage d = validDamage();
        d.setDamageType("rust");
        assertFalse(validator.validate(answerWith(d)).isEmpty());
    }

    @Test
    void confidenceOutOfRangeIsRejected() {
        Damage d = validDamage();
        d.setConfidence(1.2);
        assertFalse(validator.validate(answerWith(d)).isEmpty());
    }

    @Test
    void missingFieldsAreRejected() {
        Damage d = validDamage();
        d.setSeverity(null);
        d.setConfidence(null);
        assertEquals(2, validator.validate(answerWith(d)).size());
    }

    @Test
    void missingBoxIsRejected() {
        Damage d = validDamage();
        d.setBoundingBox(null);
        assertFalse(validator.validate(answerWith(d)).isEmpty());
    }

    @Test
    void pixelCoordinatesAreRejected() {
        Damage d = validDamage();
        d.setBoundingBox(List.of(120.0, 80.0, 300.0, 150.0));
        assertFalse(validator.validate(answerWith(d)).isEmpty());
    }

    @Test
    void boxOutsideImageIsRejected() {
        Damage d = validDamage();
        d.setBoundingBox(List.of(0.8, 0.1, 0.5, 0.2)); // x + w = 1.3
        assertFalse(validator.validate(answerWith(d)).isEmpty());
    }

    @Test
    void boxWithWrongLengthIsRejected() {
        Damage d = validDamage();
        d.setBoundingBox(Arrays.asList(0.1, 0.1, 0.2));
        assertFalse(validator.validate(answerWith(d)).isEmpty());
    }

    @Test
    void missingOverallScoreIsRejected() {
        DamageAssessment a = answerWith(validDamage());
        a.setOverallScore(null);
        assertFalse(validator.validate(a).isEmpty());
    }

    @Test
    void nullAnswerIsRejected() {
        assertFalse(validator.validate(null).isEmpty());
    }
}

package com.cardamage.core.demo;

import com.cardamage.core.model.Damage;
import com.cardamage.core.model.DamageAssessment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DamageMergeServiceTest {

    private final DamageMergeService merge = new DamageMergeService();

    private static DamageAssessment photo(String part, String type, String severity, double conf) {
        return DamageAssessment.success(
                List.of(new Damage(type, part, severity, "repair", conf, List.of(0.1, 0.1, 0.1, 0.1))), 0, 1);
    }

    @Test
    void sameDamageOnTwoPhotosBecomesOne() {
        DamageAssessment r = merge.merge(List.of(
                photo("bumper", "dent", "moderate", 0.9), photo("bumper", "dent", "moderate", 0.7)));
        assertEquals(1, r.getDamages().size());
        assertEquals(0.8, r.getDamages().get(0).getConfidence(), 1e-9);
        assertNull(r.getDamages().get(0).getBoundingBox());
    }

    @Test
    void differentDamagesAreKept() {
        DamageAssessment r = merge.merge(List.of(
                photo("bumper", "dent", "moderate", 0.9), photo("headlight", "lamp_broken", "severe", 0.9)));
        assertEquals(2, r.getDamages().size());
    }

    @Test
    void moreSevereSeverityWins() {
        DamageAssessment r = merge.merge(List.of(
                photo("door", "scratch", "minor", 0.9), photo("door", "scratch", "severe", 0.5)));
        assertEquals("severe", r.getDamages().get(0).getSeverity());
    }

    @Test
    void failedPhotosAreSkipped() {
        DamageAssessment r = merge.merge(List.of(
                photo("door", "dent", "minor", 0.9), DamageAssessment.error("failed", 3)));
        assertTrue(r.isSuccess());
        assertEquals(1, r.getDamages().size());
    }

    @Test
    void allPhotosFailedGivesError() {
        DamageAssessment r = merge.merge(List.of(DamageAssessment.error("failed", 3)));
        assertFalse(r.isSuccess());
    }
}

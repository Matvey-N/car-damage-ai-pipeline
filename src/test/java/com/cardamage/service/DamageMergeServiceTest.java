package com.cardamage.service;

import com.cardamage.model.Damage;
import com.cardamage.model.DamageAssessment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamageMergeServiceTest {

    private final DamageMergeService mergeService = new DamageMergeService();

    private Damage damage(String part, String type, String severity, double confidence) {
        Damage d = new Damage();
        d.setPart(part);
        d.setDamageType(type);
        d.setSeverity(severity);
        d.setAction("repair");
        d.setConfidence(confidence);
        return d;
    }

    @Test
    void sameDamageAcrossPhotosIsMergedIntoOne() {
        DamageAssessment img1 = DamageAssessment.success(
                List.of(damage("bumper", "dent", "moderate", 0.9)), 0);
        DamageAssessment img2 = DamageAssessment.success(
                List.of(damage("bumper", "dent", "moderate", 0.7)), 0);

        DamageAssessment merged = mergeService.merge(List.of(img1, img2));

        assertEquals(1, merged.getDamages().size(), "same (part,type) across photos must collapse to one entry");
        assertEquals(0.8, merged.getDamages().get(0).getConfidence(), 0.001, "confidence should be averaged");
    }

    @Test
    void differentDamagesAreAllKept() {
        DamageAssessment img1 = DamageAssessment.success(
                List.of(damage("bumper", "dent", "moderate", 0.9)), 0);
        DamageAssessment img2 = DamageAssessment.success(
                List.of(damage("headlight", "lamp_broken", "severe", 0.95)), 0);

        DamageAssessment merged = mergeService.merge(List.of(img1, img2));

        assertEquals(2, merged.getDamages().size(), "distinct damages must not be collapsed");
    }

    @Test
    void conflictingSeverityTakesTheMoreSevereOne() {
        DamageAssessment img1 = DamageAssessment.success(
                List.of(damage("door", "scratch", "minor", 0.8)), 0);
        DamageAssessment img2 = DamageAssessment.success(
                List.of(damage("door", "scratch", "severe", 0.6)), 0);

        DamageAssessment merged = mergeService.merge(List.of(img1, img2));

        assertEquals("severe", merged.getDamages().get(0).getSeverity(),
                "merge must be conservative and keep the higher severity");
    }

    @Test
    void errorResultsFromFailedImagesAreIgnored() {
        DamageAssessment ok = DamageAssessment.success(
                List.of(damage("bumper", "dent", "moderate", 0.9)), 0);
        DamageAssessment failed = DamageAssessment.error("could not analyze");

        DamageAssessment merged = mergeService.merge(List.of(ok, failed));

        assertTrue(merged.isSuccess());
        assertEquals(1, merged.getDamages().size());
    }
}

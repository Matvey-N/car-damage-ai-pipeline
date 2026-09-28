package com.cardamage.core.demo;

import com.cardamage.core.model.Damage;
import com.cardamage.core.model.DamageAssessment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DEMO scenario only (3-10 photos of one car). Not used by the benchmark,
 * which evaluates single CarDD images (TZ section 12).
 *
 * Rules:
 *  - same (part, damage_type) on several photos -> one entry, confidence
 *    averaged, the more severe severity kept
 *  - different (part, damage_type) -> all kept
 *  - photos whose analysis failed (status=error) are skipped
 * Bounding boxes are dropped: boxes from different photos are not comparable.
 */
public class DamageMergeService {

    private static final Map<String, Integer> SEVERITY_RANK =
            Map.of("minor", 0, "moderate", 1, "severe", 2);

    public DamageAssessment merge(List<DamageAssessment> perPhoto) {
        Map<String, List<Damage>> groups = new LinkedHashMap<>();
        int analyzed = 0;

        for (DamageAssessment result : perPhoto) {
            if (result == null || !result.isSuccess()) {
                continue;
            }
            analyzed++;
            for (Damage d : result.getDamages()) {
                groups.computeIfAbsent(d.getPart() + "|" + d.getDamageType(), k -> new ArrayList<>()).add(d);
            }
        }

        if (analyzed == 0) {
            return DamageAssessment.error("None of the photos could be analyzed", 0);
        }

        List<Damage> merged = new ArrayList<>();
        for (List<Damage> group : groups.values()) {
            merged.add(mergeGroup(group));
        }
        return DamageAssessment.success(merged, overallScore(merged), 1);
    }

    private Damage mergeGroup(List<Damage> group) {
        Damage best = group.get(0);
        String severity = best.getSeverity();
        double sum = 0;
        for (Damage d : group) {
            sum += d.getConfidence();
            if (d.getConfidence() > best.getConfidence()) {
                best = d;
            }
            if (SEVERITY_RANK.get(d.getSeverity()) > SEVERITY_RANK.get(severity)) {
                severity = d.getSeverity();
            }
        }
        Damage m = new Damage(best.getDamageType(), best.getPart(), severity,
                best.getAction(), sum / group.size(), null);
        m.setDescription(best.getDescription());
        return m;
    }

    /** Illustrative summary number for the demo, not a validated metric. */
    private double overallScore(List<Damage> damages) {
        double s = 0;
        for (Damage d : damages) {
            s += (SEVERITY_RANK.get(d.getSeverity()) + 1) * 20.0 * d.getConfidence();
        }
        return Math.min(100.0, s);
    }
}

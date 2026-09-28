package com.cardamage.service;

import com.cardamage.model.Damage;
import com.cardamage.model.DamageAssessment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merging logic for the DEMO scenario only (3-10 photos of one car,
 * uploaded via the Telegram bot). Per TZ section 12, this is explicitly
 * NOT used for the benchmark scenario, which scores single CarDD images
 * independently via IoU matching against ground truth.
 *
 * Rules (TZ section 12 / 7):
 *  - same (part, damage_type) seen across several photos -> keep the
 *    highest-confidence instance, but report the AVERAGE confidence
 *    across the photos it appeared in (avoids one lucky high-confidence
 *    call dominating)
 *  - different damages -> keep all
 *  - conflicting severity for the same (part, damage_type) -> take the
 *    more severe of the two (conservative: prefer to over- than
 *    under-flag for a user-facing demo)
 */
@Service
public class DamageMergeService {

    private static final Map<String, Integer> SEVERITY_RANK = Map.of(
            "minor", 0,
            "moderate", 1,
            "severe", 2
    );

    /**
     * @param perImageResults one DamageAssessment per uploaded photo, in
     *                        the order photos were submitted. Assessments
     *                        with status="error" are skipped (their
     *                        failure doesn't invalidate the rest).
     */
    public DamageAssessment merge(List<DamageAssessment> perImageResults) {
        Map<String, List<Damage>> grouped = new LinkedHashMap<>();

        for (DamageAssessment result : perImageResults) {
            if (result == null || !result.isSuccess()) {
                continue;
            }
            for (Damage damage : result.getDamages()) {
                String key = damage.getPart() + "|" + damage.getDamageType();
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(damage);
            }
        }

        List<Damage> merged = new ArrayList<>();
        for (List<Damage> group : grouped.values()) {
            merged.add(mergeGroup(group));
        }

        double overallScore = merged.isEmpty() ? 0.0 : computeOverallScore(merged);
        return DamageAssessment.success(merged, overallScore);
    }

    private Damage mergeGroup(List<Damage> group) {
        // Highest-confidence instance provides part/type/description/box.
        Damage best = group.get(0);
        for (Damage d : group) {
            if (d.getConfidence() > best.getConfidence()) {
                best = d;
            }
        }

        double avgConfidence = group.stream()
                .mapToDouble(Damage::getConfidence)
                .average()
                .orElse(best.getConfidence());

        String mostSevere = group.stream()
                .map(Damage::getSeverity)
                .max((a, b) -> SEVERITY_RANK.get(a) - SEVERITY_RANK.get(b))
                .orElse(best.getSeverity());

        Damage merged = new Damage();
        merged.setPart(best.getPart());
        merged.setDamageType(best.getDamageType());
        merged.setSeverity(mostSevere);
        merged.setAction(best.getAction());
        merged.setConfidence(avgConfidence);
        merged.setDescription(best.getDescription());
        return merged;
    }

    /**
     * Simple demo-purpose aggregate: not a validated metric, just a
     * user-facing summary number. Weighted by severity so a single severe
     * finding pulls the score up more than several minor ones.
     */
    private double computeOverallScore(List<Damage> damages) {
        double sum = damages.stream()
                .mapToDouble(d -> (SEVERITY_RANK.get(d.getSeverity()) + 1) * 20.0 * d.getConfidence())
                .sum();
        return Math.min(100.0, sum);
    }
}

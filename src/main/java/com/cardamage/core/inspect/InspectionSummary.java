package com.cardamage.core.inspect;

import com.cardamage.core.demo.DamageMergeService;
import com.cardamage.core.demo.PriceEstimator;
import com.cardamage.core.model.Damage;
import com.cardamage.core.model.DamageAssessment;

import java.util.ArrayList;
import java.util.List;

/** Merged list of damages over all photos of an inspection, with the demo price range. */
public record InspectionSummary(List<Damage> damages, List<PriceEstimator.Range> prices,
                                PriceEstimator.Range total, String currency) {

    public static InspectionSummary of(List<Damage> damagesOverPhotos, DamageMergeService merge, PriceEstimator prices) {
        List<Damage> merged = damagesOverPhotos.isEmpty() ? List.of()
                : merge.merge(List.of(DamageAssessment.success(new ArrayList<>(damagesOverPhotos), 0, 1))).getDamages();
        List<PriceEstimator.Range> each = merged.stream().map(prices::priceOf).toList();
        return new InspectionSummary(merged, each, prices.total(merged), prices.currency());
    }

    public static List<Damage> allDamages(List<InspectionStore.Photo> photos) {
        List<Damage> all = new ArrayList<>();
        for (InspectionStore.Photo p : photos) {
            if ("success".equals(p.status())) {
                all.addAll(p.damages());
            }
        }
        return all;
    }
}

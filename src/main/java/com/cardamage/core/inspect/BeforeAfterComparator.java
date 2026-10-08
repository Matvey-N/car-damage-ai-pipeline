package com.cardamage.core.inspect;

import com.cardamage.core.model.Damage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Rental / car sharing: which damages on the "after" photos were not there
 * on the "before" photos?
 *
 * Photos are paired by view (guided capture: front, left side, ...). On a pair,
 * an "after" damage counts as already present if a "before" damage of the same
 * type on the same part lies nearby: box centres closer than MAX_CENTRE_DISTANCE
 * (fraction of the image), because two photos of one view are never framed
 * exactly alike. Otherwise it is new. An "after" photo without a "before" photo
 * of its view cannot be compared: its damages are reported as "not compared",
 * not as new.
 *
 * Limits: a damage seen from a clearly different angle may be misjudged; the
 * user sees the photos side by side and can correct the result.
 */
public final class BeforeAfterComparator {

    public static final double MAX_CENTRE_DISTANCE = 0.15;

    public enum Status { NEW, EXISTING, NOT_COMPARED }

    public record Finding(int afterPhotoIndex, String view, int damageIndex, Damage damage, Status status) {
    }

    private BeforeAfterComparator() {
    }

    public static List<Finding> compare(List<InspectionStore.Photo> before, List<InspectionStore.Photo> after) {
        Map<String, List<InspectionStore.Photo>> beforeByView = before.stream()
                .filter(p -> p.view() != null && !Views.EXTRA.equals(p.view()) && "success".equals(p.status()))
                .collect(Collectors.groupingBy(InspectionStore.Photo::view));
        List<Finding> findings = new ArrayList<>();
        for (InspectionStore.Photo a : after) {
            if (!"success".equals(a.status())) {
                continue;
            }
            List<InspectionStore.Photo> sameView = a.view() == null ? null : beforeByView.get(a.view());
            List<Damage> damages = a.damages();
            for (int i = 0; i < damages.size(); i++) {
                Damage d = damages.get(i);
                Status status;
                if (sameView == null) {
                    status = Status.NOT_COMPARED;
                } else {
                    boolean seen = sameView.stream().flatMap(b -> b.damages().stream()).anyMatch(b -> sameDamage(b, d));
                    status = seen ? Status.EXISTING : Status.NEW;
                }
                findings.add(new Finding(a.index(), a.view(), i, d, status));
            }
        }
        return findings;
    }

    static boolean sameDamage(Damage before, Damage after) {
        if (!before.getDamageType().equals(after.getDamageType()) || !before.getPart().equals(after.getPart())) {
            return false;
        }
        double[] cb = centre(before.getBoundingBox());
        double[] ca = centre(after.getBoundingBox());
        return Math.hypot(cb[0] - ca[0], cb[1] - ca[1]) <= MAX_CENTRE_DISTANCE;
    }

    private static double[] centre(List<Double> box) {
        return new double[]{box.get(0) + box.get(2) / 2, box.get(1) + box.get(3) / 2};
    }
}

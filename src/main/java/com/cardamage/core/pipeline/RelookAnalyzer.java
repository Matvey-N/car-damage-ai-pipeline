package com.cardamage.core.pipeline;

import com.cardamage.core.model.Damage;
import com.cardamage.core.model.DamageAssessment;

import java.util.ArrayList;
import java.util.List;

/**
 * "Second look": two model calls per photo to find more damages.
 *
 * Why: the benchmark shows that the model mostly MISSES damages (recall .39-.46
 * with precision .60), and a cheaper model misses even more. A missed damage is
 * usually not invisible: the model simply stops after the most obvious ones.
 *
 * How:
 *   1. normal analysis of the photo (the profile's prompt);
 *   2. the damages found are drawn on the photo as numbered red rectangles, and the
 *      model is asked to look again and report ONLY damages that are not marked yet;
 *   3. the two lists are joined; a second-look box that repeats a marked damage
 *      (same type, IoU >= mergeIou or lying inside it) is dropped.
 * Coordinates of the second look refer to the same full image, so no mapping is
 * needed. If the second call fails, the first result is returned with a note.
 * Costs 2 model calls per photo (vs 5 for the 2x2 tiled mode).
 */
public class RelookAnalyzer {

    public static final String PROMPT_VERSION = "rl1";

    static final String SUFFIX = """

            SECOND LOOK. This photo was already inspected. %s
            Look at the photo again, slowly and part by part, and concentrate on what is easy to miss:
            small and thin scratches, damages at the edges and corners of the image, lower parts of the
            car, lights, glass, the area around and between already found damages.
            Report ONLY damages that are NOT already marked. A marked damage must not be reported again,
            also not with a slightly different box. Boxes are fractions of this whole image.
            The red rectangles and numbers were drawn by us: they are not damage.
            If you find nothing new, return "damages": [].
            """;

    private final SingleImageAnalyzer single;
    private final double mergeIou;

    public RelookAnalyzer(SingleImageAnalyzer single, double mergeIou) {
        this.single = single;
        this.mergeIou = mergeIou;
    }

    public DamageAssessment analyze(byte[] image, String mediaType) {
        return analyze(image, mediaType, AnalysisProfile.BENCHMARK);
    }

    public DamageAssessment analyze(byte[] image, String mediaType, AnalysisProfile profile) {
        DamageAssessment first = single.analyze(image, mediaType, profile);
        if (!first.isSuccess() || Boolean.FALSE.equals(first.getVehicleVisible())) {
            return first;
        }
        List<Damage> found = first.getDamages();
        byte[] marked = image;
        String markedType = mediaType;
        if (!found.isEmpty()) {
            List<List<Double>> boxes = new ArrayList<>();
            for (Damage d : found) {
                boxes.add(d.getBoundingBox());
            }
            marked = RegionDescriber.drawRegions(image, boxes);
            markedType = "image/png";
        }
        DamageAssessment second = single.analyzeWithPrompt(marked, markedType, profile,
                profile.prompt() + String.format(SUFFIX, alreadyFound(found)));
        int calls = first.getAttempts() + second.getAttempts();
        if (!second.isSuccess()) {
            DamageAssessment r = DamageAssessment.success(found, first.getOverallScore(), calls);
            r.setVehicleVisible(first.getVehicleVisible());
            r.setErrorMessage("Second look failed, only the first result is shown: " + second.getErrorMessage());
            return r;
        }
        List<Damage> fresh = new ArrayList<>();
        for (Damage d : second.getDamages()) {
            boolean repeat = false;
            for (Damage k : found) {
                if (k.getDamageType().equals(d.getDamageType())
                        && TiledImageAnalyzer.sameDamage(k.getBoundingBox(), d.getBoundingBox(), mergeIou)) {
                    repeat = true;
                    break;
                }
            }
            if (!repeat) {
                fresh.add(d);
            }
        }
        List<Damage> all = new ArrayList<>(found);
        // duplicates inside the second answer itself
        all.addAll(TiledImageAnalyzer.merge(fresh, mergeIou));
        DamageAssessment r = DamageAssessment.success(all,
                Math.max(first.getOverallScore(), second.getOverallScore()), calls);
        r.setVehicleVisible(first.getVehicleVisible());
        return r;
    }

    static String alreadyFound(List<Damage> found) {
        if (found.isEmpty()) {
            return "The first inspection found no damage. Check carefully whether something was missed.";
        }
        StringBuilder sb = new StringBuilder("The damages already found are marked on the image with red numbered rectangles:\n");
        for (int i = 0; i < found.size(); i++) {
            Damage d = found.get(i);
            List<Double> b = d.getBoundingBox();
            sb.append(String.format(java.util.Locale.ROOT, "%d: %s on %s at [%.3f, %.3f, %.3f, %.3f]%n",
                    i + 1, d.getDamageType(), d.getPart(), b.get(0), b.get(1), b.get(2), b.get(3)));
        }
        return sb.toString().trim();
    }
}

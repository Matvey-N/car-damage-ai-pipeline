package com.cardamage.core.pipeline;

import com.cardamage.core.model.Damage;
import com.cardamage.core.model.DamageAssessment;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Analyzes one image as a grid of overlapping tiles (plus, optionally, the
 * whole image) and merges the answers.
 *
 * Why: before a call the image is scaled down to 1568 px on the longer side,
 * and the benchmark showed that small damages are mostly missed. A tile of a
 * 2x2 grid covers about 60% of each side, so after the same downscaling the
 * model sees it at a higher resolution (about 1.6x for a 3264 px photo).
 *
 * Each tile goes through the normal single-image pipeline (validation, retry,
 * fallback) with the SAME prompt, so that the experiment changes only the
 * resolution the model sees. Boxes are mapped from tile to image coordinates.
 * The same damage seen in several tiles is merged: of two boxes with the same
 * damage type that overlap (IoU >= mergeIou, or the smaller one lies mostly
 * inside the larger one), the one with the lower confidence is dropped.
 */
public class TiledImageAnalyzer {

    /** Share of the smaller box that must lie inside the larger one to count as the same damage. */
    static final double CONTAINMENT = 0.8;

    private final SingleImageAnalyzer single;
    private final int rows;
    private final int cols;
    private final double overlap;
    private final boolean includeFullImage;
    private final double mergeIou;

    public TiledImageAnalyzer(SingleImageAnalyzer single, int rows, int cols, double overlap,
                              boolean includeFullImage, double mergeIou) {
        if (rows < 1 || cols < 1 || overlap < 0 || overlap >= 0.5) {
            throw new IllegalArgumentException("need rows, cols >= 1 and 0 <= overlap < 0.5");
        }
        this.single = single;
        this.rows = rows;
        this.cols = cols;
        this.overlap = overlap;
        this.includeFullImage = includeFullImage;
        this.mergeIou = mergeIou;
    }

    /** One tile as a fraction of the image: x, y, w, h in [0, 1]. */
    record Tile(double x, double y, double w, double h) {
    }

    public String describe() {
        return rows + "x" + cols + " tiles, overlap " + overlap + (includeFullImage ? ", plus the whole image" : "");
    }

    public DamageAssessment analyze(byte[] image, String mediaType) {
        if (image == null || image.length == 0) {
            throw new IllegalArgumentException("image is empty");
        }
        if (mediaType == null || !SingleImageAnalyzer.SUPPORTED_MEDIA_TYPES.contains(mediaType)) {
            throw new IllegalArgumentException("unsupported media type: " + mediaType);
        }
        BufferedImage full = read(image);

        List<Damage> found = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int calls = 0;
        int succeeded = 0;
        double scoreSum = 0;

        if (includeFullImage) {
            DamageAssessment r = single.analyze(image, mediaType);
            calls += r.getAttempts();
            if (r.isSuccess()) {
                succeeded++;
                scoreSum += r.getOverallScore();
                found.addAll(r.getDamages());
            } else {
                failures.add("whole image: " + r.getErrorMessage());
            }
        }

        for (Tile t : tiles(rows, cols, overlap)) {
            DamageAssessment r = single.analyze(crop(full, t), "image/png");
            calls += r.getAttempts();
            if (!r.isSuccess()) {
                failures.add(String.format("tile %.2f,%.2f: %s", t.x(), t.y(), r.getErrorMessage()));
                continue;
            }
            succeeded++;
            scoreSum += r.getOverallScore();
            for (Damage d : r.getDamages()) {
                found.add(toImageCoordinates(d, t));
            }
        }

        if (succeeded == 0) {
            return DamageAssessment.error("No tile could be analyzed. " + String.join(" | ", failures), calls);
        }
        DamageAssessment result = DamageAssessment.success(merge(found, mergeIou), scoreSum / succeeded, calls);
        if (!failures.isEmpty()) {
            // partial result: still useful, but the caller should know some areas were not analyzed
            result.setErrorMessage("Some tiles failed: " + String.join(" | ", failures));
        }
        return result;
    }

    /** Grid of tiles covering the image, neighbours overlapping by the given share of a tile. */
    static List<Tile> tiles(int rows, int cols, double overlap) {
        List<Tile> tiles = new ArrayList<>();
        double w = 1.0 / (cols - (cols - 1) * overlap);
        double h = 1.0 / (rows - (rows - 1) * overlap);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double x = cols == 1 ? 0 : c * (1 - w) / (cols - 1);
                double y = rows == 1 ? 0 : r * (1 - h) / (rows - 1);
                tiles.add(new Tile(x, y, Math.min(w, 1), Math.min(h, 1)));
            }
        }
        return tiles;
    }

    static Damage toImageCoordinates(Damage d, Tile t) {
        List<Double> b = d.getBoundingBox();
        List<Double> mapped = List.of(
                t.x() + b.get(0) * t.w(),
                t.y() + b.get(1) * t.h(),
                b.get(2) * t.w(),
                b.get(3) * t.h());
        Damage m = new Damage(d.getDamageType(), d.getPart(), d.getSeverity(), d.getAction(), d.getConfidence(), mapped);
        m.setDescription(d.getDescription());
        return m;
    }

    /** Greedy by confidence: a box is dropped if a kept box of the same type covers the same damage. */
    static List<Damage> merge(List<Damage> damages, double mergeIou) {
        List<Damage> sorted = new ArrayList<>(damages);
        sorted.sort(Comparator.comparingDouble(Damage::getConfidence).reversed());
        List<Damage> kept = new ArrayList<>();
        for (Damage d : sorted) {
            boolean duplicate = false;
            for (Damage k : kept) {
                if (k.getDamageType().equals(d.getDamageType()) && sameDamage(k.getBoundingBox(), d.getBoundingBox(), mergeIou)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                kept.add(d);
            }
        }
        return kept;
    }

    static boolean sameDamage(List<Double> a, List<Double> b, double mergeIou) {
        double ix = Math.max(0, Math.min(a.get(0) + a.get(2), b.get(0) + b.get(2)) - Math.max(a.get(0), b.get(0)));
        double iy = Math.max(0, Math.min(a.get(1) + a.get(3), b.get(1) + b.get(3)) - Math.max(a.get(1), b.get(1)));
        double inter = ix * iy;
        if (inter <= 0) {
            return false;
        }
        double areaA = a.get(2) * a.get(3);
        double areaB = b.get(2) * b.get(3);
        double iou = inter / (areaA + areaB - inter);
        return iou >= mergeIou || inter / Math.min(areaA, areaB) >= CONTAINMENT;
    }

    private static BufferedImage read(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new IllegalArgumentException("image could not be read (not a valid JPEG or PNG)");
            }
            return image;
        } catch (IOException e) {
            throw new IllegalArgumentException("image could not be read: " + e.getMessage(), e);
        }
    }

    private static byte[] crop(BufferedImage image, Tile t) {
        int x = (int) Math.round(t.x() * image.getWidth());
        int y = (int) Math.round(t.y() * image.getHeight());
        int w = Math.min(image.getWidth() - x, (int) Math.round(t.w() * image.getWidth()));
        int h = Math.min(image.getHeight() - y, (int) Math.round(t.h() * image.getHeight()));
        BufferedImage tile = image.getSubimage(x, y, Math.max(1, w), Math.max(1, h));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(tile, "png", out);
        } catch (IOException e) {
            throw new IllegalStateException("tile encoding failed", e);
        }
        return out.toByteArray();
    }
}

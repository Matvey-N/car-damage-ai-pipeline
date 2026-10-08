package com.cardamage.core.pipeline;

import com.cardamage.core.model.Damage;
import com.cardamage.core.model.DamageAssessment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TiledImageAnalyzerTest {

    private static final String SCRATCH_IN_TOP_LEFT_QUARTER =
            "{\"damages\":[{\"damage_type\":\"scratch\",\"part\":\"door\",\"severity\":\"minor\",\"action\":\"repair\","
            + "\"confidence\":%s,\"bounding_box\":[0.25,0.25,0.5,0.5]}],\"overall_score\":20}";
    private static final String NOTHING = "{\"damages\":[],\"overall_score\":0}";

    /** 400x200 image: left half red, right half blue. */
    private static byte[] twoColourImage() throws Exception {
        BufferedImage img = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 200, 200);
        g.setColor(Color.BLUE);
        g.fillRect(200, 0, 200, 200);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** Fake model: sees a damage only in an all-red tile; counts calls. */
    private static class RedTileModel implements VisionModelClient {
        int calls;

        @Override
        public String analyze(byte[] image, String mediaType, String prompt) {
            calls++;
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(image));
                boolean allRed = new Color(img.getRGB(0, 0)).getRed() > 200
                        && new Color(img.getRGB(img.getWidth() - 1, img.getHeight() - 1)).getRed() > 200;
                return allRed ? String.format(SCRATCH_IN_TOP_LEFT_QUARTER, "0.7") : NOTHING;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static SingleImageAnalyzer single(VisionModelClient client) {
        return new SingleImageAnalyzer(client, new ResponseParser(new ObjectMapper()),
                new ResponseFormatValidator(), 3, 0, ms -> { });
    }

    private static Damage damage(String type, double conf, double x, double y, double w, double h) {
        return new Damage(type, "door", "minor", "repair", conf, List.of(x, y, w, h));
    }

    @Test
    void gridCoversTheImageWithOverlap() {
        List<TiledImageAnalyzer.Tile> tiles = TiledImageAnalyzer.tiles(2, 2, 0.2);
        assertEquals(4, tiles.size());
        TiledImageAnalyzer.Tile first = tiles.get(0);
        TiledImageAnalyzer.Tile last = tiles.get(3);
        assertEquals(0.0, first.x(), 1e-9);
        assertEquals(1.0, last.x() + last.w(), 1e-9, "the last tile ends at the right edge");
        assertEquals(1.0, last.y() + last.h(), 1e-9, "the last tile ends at the bottom edge");
        // neighbours overlap by 20% of a tile
        assertEquals(0.2 * first.w(), first.x() + first.w() - tiles.get(1).x(), 1e-9);
    }

    @Test
    void boxIsMappedFromTileToImage() {
        Damage d = damage("crack", 0.8, 0.5, 0.5, 0.5, 0.5);
        Damage m = TiledImageAnalyzer.toImageCoordinates(d, new TiledImageAnalyzer.Tile(0.5, 0.0, 0.5, 0.5));
        assertEquals(List.of(0.75, 0.25, 0.25, 0.25), m.getBoundingBox());
        assertEquals("crack", m.getDamageType());
    }

    @Test
    void duplicatesOfOneDamageAreMergedKeepingTheMostConfident() {
        List<Damage> merged = TiledImageAnalyzer.merge(List.of(
                damage("scratch", 0.6, 0.10, 0.10, 0.20, 0.20),
                damage("scratch", 0.9, 0.11, 0.11, 0.20, 0.20),   // same damage, higher confidence
                damage("scratch", 0.5, 0.12, 0.12, 0.05, 0.05),   // lies inside: same damage, cut at a tile edge
                damage("crack", 0.7, 0.10, 0.10, 0.20, 0.20),     // other type at the same place: kept
                damage("scratch", 0.8, 0.70, 0.70, 0.10, 0.10)),  // elsewhere: kept
                0.5);
        assertEquals(3, merged.size());
        assertEquals(0.9, merged.get(0).getConfidence());
    }

    @Test
    void damageSeenOnlyInATileIsFoundAndPlacedInImageCoordinates() throws Exception {
        RedTileModel model = new RedTileModel();
        // 1 row x 2 columns, no overlap: left tile is red, right tile is blue; whole image is not all red
        TiledImageAnalyzer tiled = new TiledImageAnalyzer(single(model), 1, 2, 0.0, true, 0.5);
        DamageAssessment r = tiled.analyze(twoColourImage(), "image/png");

        assertTrue(r.isSuccess());
        assertEquals(3, model.calls, "whole image + 2 tiles");
        assertEquals(3, r.getAttempts());
        assertEquals(1, r.getDamages().size());
        // [0.25, 0.25, 0.5, 0.5] in the left half -> [0.125, 0.25, 0.25, 0.5] in the image
        List<Double> box = r.getDamages().get(0).getBoundingBox();
        assertEquals(0.125, box.get(0), 1e-9);
        assertEquals(0.25, box.get(1), 1e-9);
        assertEquals(0.25, box.get(2), 1e-9);
        assertEquals(0.5, box.get(3), 1e-9);
    }

    @Test
    void failedTilesAreReportedButDoNotDiscardTheRest() throws Exception {
        VisionModelClient brokenOnTiles = new VisionModelClient() {
            int calls;

            @Override
            public String analyze(byte[] image, String mediaType, String prompt) {
                calls++;
                if (calls == 1) {
                    return String.format(SCRATCH_IN_TOP_LEFT_QUARTER, "0.8");  // the whole image works
                }
                throw new ModelCallException("timeout");
            }
        };
        DamageAssessment r = new TiledImageAnalyzer(single(brokenOnTiles), 1, 2, 0.0, true, 0.5)
                .analyze(twoColourImage(), "image/png");
        assertTrue(r.isSuccess());
        assertEquals(1, r.getDamages().size());
        assertNotNull(r.getErrorMessage());
        assertTrue(r.getErrorMessage().contains("Some tiles failed"));
    }

    @Test
    void everythingFailingIsAnError() throws Exception {
        VisionModelClient broken = (image, mediaType, prompt) -> {
            throw new ModelCallException("down");
        };
        DamageAssessment r = new TiledImageAnalyzer(single(broken), 1, 2, 0.0, false, 0.5)
                .analyze(twoColourImage(), "image/png");
        assertFalse(r.isSuccess());
        assertEquals(6, r.getAttempts(), "2 tiles x 3 attempts");
    }

    @Test
    void unreadableImageIsBadInput() {
        TiledImageAnalyzer tiled = new TiledImageAnalyzer(single(new RedTileModel()), 2, 2, 0.2, true, 0.5);
        assertThrows(IllegalArgumentException.class, () -> tiled.analyze(new byte[]{1, 2, 3}, "image/png"));
    }
}

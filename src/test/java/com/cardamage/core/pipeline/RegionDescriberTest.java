package com.cardamage.core.pipeline;

import com.cardamage.core.model.DamageAssessment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RegionDescriberTest {

    private static final List<List<Double>> TWO_REGIONS = List.of(
            List.of(0.1, 0.1, 0.2, 0.2), List.of(0.6, 0.5, 0.1, 0.1));

    private static final String GOOD = "{\"regions\":["
            + "{\"region\":1,\"damage_type\":\"lamp_broken\",\"part\":\"light\",\"severity\":\"severe\",\"action\":\"replacement\",\"confidence\":0.9},"
            + "{\"region\":2,\"damage_type\":\"none\",\"part\":null,\"severity\":null,\"action\":null,\"confidence\":0.8}]}";
    private static final String MISSING_REGION_2 = "{\"regions\":["
            + "{\"region\":1,\"damage_type\":\"scratch\",\"part\":\"door\",\"severity\":\"minor\",\"action\":\"repair\",\"confidence\":0.6}]}";

    private static byte[] png() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private static RegionDescriber describer(StubVisionModelClient stub) {
        return new RegionDescriber(stub, new ObjectMapper(), 3, ImagePreprocessor.NONE);
    }

    @Test
    void regionsKeepTheDetectorBoxAndNoneIsDropped() throws Exception {
        StubVisionModelClient stub = new StubVisionModelClient(List.of(GOOD));
        DamageAssessment r = describer(stub).describe(png(), "image/png", TWO_REGIONS);
        assertTrue(r.isSuccess());
        assertEquals(1, r.getDamages().size(), "region 2 was rejected by the model");
        assertEquals("lamp_broken", r.getDamages().get(0).getDamageType());
        assertEquals(TWO_REGIONS.get(0), r.getDamages().get(0).getBoundingBox());
        assertTrue(stub.getReceivedPrompts().get(0).contains("Region 2: [0.600, 0.500, 0.100, 0.100]"));
    }

    @Test
    void anAnswerMissingARegionIsRetriedWithTheReason() throws Exception {
        StubVisionModelClient stub = new StubVisionModelClient(List.of(MISSING_REGION_2, GOOD));
        DamageAssessment r = describer(stub).describe(png(), "image/png", TWO_REGIONS);
        assertTrue(r.isSuccess());
        assertEquals(2, r.getAttempts());
        assertTrue(stub.getReceivedPrompts().get(1).contains("expected an entry for every region"));
    }

    @Test
    void threeBadAnswersGiveAnError() throws Exception {
        DamageAssessment r = describer(new StubVisionModelClient(List.of("not json"))).describe(png(), "image/png", TWO_REGIONS);
        assertFalse(r.isSuccess());
        assertEquals(3, r.getAttempts());
    }

    @Test
    void noRegionsMeansNoCall() throws Exception {
        StubVisionModelClient stub = new StubVisionModelClient(List.of(GOOD));
        DamageAssessment r = describer(stub).describe(png(), "image/png", List.of());
        assertTrue(r.isSuccess());
        assertEquals(0, stub.getCalls());
    }

    @Test
    void badRegionsAreBadInput() {
        assertThrows(IllegalArgumentException.class,
                () -> RegionDescriber.checkRegions(List.of(List.of(0.1, 0.1, 1.5, 0.1))));
        assertThrows(IllegalArgumentException.class,
                () -> RegionDescriber.checkRegions(List.of(List.of(0.1, 0.1, 0.1))));
    }

    @Test
    void unknownTypeAndDuplicateRegionAreViolations() {
        RegionDescriber d = describer(new StubVisionModelClient(List.of(GOOD)));
        List<String> violations = new java.util.ArrayList<>();
        d.parse("{\"regions\":[{\"region\":1,\"damage_type\":\"dent\",\"confidence\":0.5},"
                + "{\"region\":1,\"damage_type\":\"none\",\"confidence\":0.5}]}", TWO_REGIONS, violations);
        assertEquals(1, violations.stream().filter(v -> v.contains("unknown damage_type")).count());
    }
}

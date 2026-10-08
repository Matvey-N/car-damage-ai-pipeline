package com.cardamage.core.pipeline;

import com.cardamage.core.model.Damage;
import com.cardamage.core.model.DamageAssessment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RelookAnalyzerTest {


    private static String answer(String... damages) {
        List<String> items = new ArrayList<>();
        for (String d : damages) {
            String[] f = d.split("\\|");
            items.add("{\"damage_type\":\"" + f[0] + "\",\"part\":\"" + f[1] + "\",\"severity\":\"minor\","
                    + "\"action\":\"repair\",\"confidence\":" + f[2] + ",\"bounding_box\":[" + f[3] + "]}");
        }
        return "{\"damages\":[" + String.join(",", items) + "],\"overall_score\":30}";
    }

    private static byte[] jpeg() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }

    /** Records the media types it was called with. */
    private static class Recording implements VisionModelClient {
        final StubVisionModelClient stub;
        final List<String> mediaTypes = new ArrayList<>();

        Recording(String... answers) {
            stub = new StubVisionModelClient(Arrays.asList(answers));
        }

        @Override
        public String analyze(byte[] image, String mediaType, String prompt) {
            mediaTypes.add(mediaType);
            return stub.analyze(image, mediaType, prompt);
        }
    }

    private static RelookAnalyzer relook(VisionModelClient client) {
        return new RelookAnalyzer(new SingleImageAnalyzer(client, new ResponseParser(new ObjectMapper()),
                new ResponseFormatValidator(), 3, 0, ms -> { }), 0.5);
    }

    @Test
    void secondLookAddsOnlyNewDamages() throws Exception {
        Recording model = new Recording(
                answer("scratch|door|0.9|0.10,0.10,0.20,0.10"),
                // repeats the marked scratch with a slightly different box, adds a crack
                answer("scratch|door|0.6|0.11,0.10,0.19,0.11", "crack|bumper|0.7|0.60,0.70,0.20,0.10"));
        DamageAssessment r = relook(model).analyze(jpeg(), "image/jpeg");

        assertTrue(r.isSuccess());
        assertEquals(2, r.getAttempts(), "two model calls");
        assertEquals(List.of("scratch", "crack"), r.getDamages().stream().map(Damage::getDamageType).toList());
        assertEquals(0.9, r.getDamages().get(0).getConfidence(), "the first answer is kept for the marked damage");
        List<String> prompts = model.stub.getReceivedPrompts();
        assertFalse(prompts.get(0).contains("SECOND LOOK"));
        assertTrue(prompts.get(1).contains("SECOND LOOK"));
        assertTrue(prompts.get(1).contains("1: scratch on door at [0.100, 0.100, 0.200, 0.100]"));
        assertEquals(List.of("image/jpeg", "image/png"), model.mediaTypes, "second call gets the photo with boxes drawn");
    }

    @Test
    void nothingFoundFirstStillGetsASecondLookAtTheOriginalPhoto() throws Exception {
        Recording model = new Recording(answer(), answer("scratch|door|0.5|0.1,0.1,0.1,0.1"));
        DamageAssessment r = relook(model).analyze(jpeg(), "image/jpeg");
        assertEquals(1, r.getDamages().size());
        assertEquals(List.of("image/jpeg", "image/jpeg"), model.mediaTypes);
        assertTrue(model.stub.getReceivedPrompts().get(1).contains("found no damage"));
    }

    @Test
    void failedSecondLookKeepsTheFirstResultWithANote() throws Exception {
        Recording model = new Recording(answer("scratch|door|0.9|0.10,0.10,0.20,0.10"), null);
        DamageAssessment r = relook(model).analyze(jpeg(), "image/jpeg");
        assertTrue(r.isSuccess());
        assertEquals(1, r.getDamages().size());
        assertEquals(4, r.getAttempts(), "1 + 3 failed attempts");
        assertTrue(r.getErrorMessage().startsWith("Second look failed"));
    }

    @Test
    void failedFirstLookIsReturnedAsIs() throws Exception {
        Recording model = new Recording((String) null);
        DamageAssessment r = relook(model).analyze(jpeg(), "image/jpeg");
        assertFalse(r.isSuccess());
        assertEquals(3, model.stub.getCalls());
    }

    @Test
    void noSecondLookWhenThereIsNoCar() throws Exception {
        Recording model = new Recording("{\"vehicle_visible\":false,\"damages\":[],\"overall_score\":0}");
        DamageAssessment r = relook(model).analyze(jpeg(), "image/jpeg", AnalysisProfile.GENERAL);
        assertTrue(r.isSuccess());
        assertEquals(Boolean.FALSE, r.getVehicleVisible());
        assertEquals(1, model.stub.getCalls());
    }
}

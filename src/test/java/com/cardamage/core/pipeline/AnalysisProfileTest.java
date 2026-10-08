package com.cardamage.core.pipeline;

import com.cardamage.core.model.DamageAssessment;
import com.cardamage.core.model.Labels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisProfileTest {

    private static final String DENT = "{\"vehicle_visible\":true,\"damages\":[{\"damage_type\":\"dent\",\"part\":\"trunk\","
            + "\"severity\":\"moderate\",\"action\":\"repair\",\"confidence\":0.8,\"description\":\"вмятина на крышке\","
            + "\"bounding_box\":[0.2,0.3,0.2,0.1]}],\"overall_score\":30}";

    private static SingleImageAnalyzer analyzer(StubVisionModelClient client) {
        return new SingleImageAnalyzer(client, new ResponseParser(new ObjectMapper()),
                new ResponseFormatValidator(), 3, 0, ms -> { });
    }

    @Test
    void profilesByName() {
        assertSame(AnalysisProfile.BENCHMARK, AnalysisProfile.byName(null));
        assertSame(AnalysisProfile.BENCHMARK, AnalysisProfile.byName("benchmark"));
        assertSame(AnalysisProfile.GENERAL, AnalysisProfile.byName(" General "));
        assertThrows(IllegalArgumentException.class, () -> AnalysisProfile.byName("other"));
        assertEquals("v3", AnalysisProfile.BENCHMARK.promptVersion());
        assertEquals(GeneralPrompt.VERSION, AnalysisProfile.GENERAL.promptVersion());
    }

    @Test
    void generalTaxonomyExtendsTheBenchmarkOne() {
        assertTrue(ResponseFormatValidator.GENERAL_DAMAGE_TYPES.containsAll(ResponseFormatValidator.DAMAGE_TYPES));
        assertTrue(ResponseFormatValidator.GENERAL_PARTS.containsAll(ResponseFormatValidator.PARTS));
        for (String t : ResponseFormatValidator.GENERAL_DAMAGE_TYPES) {
            assertTrue(GeneralPrompt.BASE.contains("\"" + t + "\""), "prompt lists " + t);
            assertTrue(Labels.TYPE.containsKey(t) && Labels.TYPE_COLOR.containsKey(t), "label for " + t);
        }
        for (String p : ResponseFormatValidator.GENERAL_PARTS) {
            assertTrue(GeneralPrompt.BASE.contains("\"" + p + "\""), "prompt lists " + p);
            assertTrue(Labels.PART.containsKey(p), "label for " + p);
        }
    }

    @Test
    void everyPartHasADemoPrice() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/demo/price_table.json")) {
            JsonNode prices = new ObjectMapper().readTree(in).path("prices");
            for (String p : ResponseFormatValidator.GENERAL_PARTS) {
                assertTrue(prices.has(p), "price for " + p);
            }
        }
    }

    @Test
    void dentIsValidOnlyInTheGeneralProfile() {
        StubVisionModelClient general = new StubVisionModelClient(List.of(DENT));
        DamageAssessment r = analyzer(general).analyze(new byte[]{1}, "image/jpeg", AnalysisProfile.GENERAL);
        assertTrue(r.isSuccess());
        assertEquals("dent", r.getDamages().get(0).getDamageType());
        assertEquals("вмятина на крышке", r.getDamages().get(0).getDescription());
        assertEquals(Boolean.TRUE, r.getVehicleVisible());
        assertEquals(GeneralPrompt.BASE, general.getReceivedPrompts().get(0));

        StubVisionModelClient benchmark = new StubVisionModelClient(List.of(DENT));
        DamageAssessment b = analyzer(benchmark).analyze(new byte[]{1}, "image/jpeg");
        assertFalse(b.isSuccess(), "dent and trunk are not in the benchmark taxonomy");
        assertTrue(benchmark.getReceivedPrompts().get(1).contains("unknown value 'dent'"), "retry names the problem");
        assertEquals(DamagePrompt.BASE, benchmark.getReceivedPrompts().get(0));
    }

    @Test
    void noCarIsASuccessWithoutDamages() {
        StubVisionModelClient client = new StubVisionModelClient(List.of("{\"vehicle_visible\":false,\"overall_score\":0}"));
        DamageAssessment r = analyzer(client).analyze(new byte[]{1}, "image/jpeg", AnalysisProfile.GENERAL);
        assertTrue(r.isSuccess());
        assertEquals(Boolean.FALSE, r.getVehicleVisible());
        assertTrue(r.getDamages().isEmpty());
    }

    @Test
    void benchmarkOutputHasNoVehicleField() throws Exception {
        DamageAssessment r = analyzer(StubVisionModelClient.withDefaultAnswer()).analyze(new byte[]{1}, "image/jpeg");
        assertNull(r.getVehicleVisible());
        assertFalse(new ObjectMapper().writeValueAsString(r).contains("vehicle_visible"));
    }
}

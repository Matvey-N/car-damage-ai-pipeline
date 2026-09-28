package com.cardamage.core.pipeline;

import com.cardamage.core.model.DamageAssessment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SingleImageAnalyzerTest {

    private static final byte[] IMAGE = {1, 2, 3};
    private static final String INVALID_ENUM =
            "{\"damages\":[{\"damage_type\":\"rust\",\"part\":\"door\",\"severity\":\"minor\","
            + "\"action\":\"repair\",\"confidence\":0.5,\"bounding_box\":[0.1,0.1,0.2,0.2]}],\"overall_score\":10}";
    private static final String NO_DAMAGE = "{\"damages\":[],\"overall_score\":0}";

    private SingleImageAnalyzer analyzer(StubVisionModelClient stub) {
        return new SingleImageAnalyzer(stub, new ResponseParser(new ObjectMapper()),
                new ResponseFormatValidator(), 3, 0, ms -> { });
    }

    @Test
    void validAnswerOnFirstAttemptIsSuccess() {
        StubVisionModelClient stub = StubVisionModelClient.withDefaultAnswer();
        DamageAssessment result = analyzer(stub).analyze(IMAGE, "image/jpeg");

        assertTrue(result.isSuccess());
        assertEquals(1, result.getAttempts());
        assertEquals(1, result.getDamages().size());
        assertEquals(1, stub.getCalls());
    }

    @Test
    void noDamageIsSuccessNotError() {
        DamageAssessment result = analyzer(new StubVisionModelClient(List.of(NO_DAMAGE)))
                .analyze(IMAGE, "image/png");
        assertTrue(result.isSuccess());
        assertTrue(result.getDamages().isEmpty());
        assertNull(result.getErrorMessage());
    }

    @Test
    void invalidAnswerIsRetriedAndThenSucceeds() {
        StubVisionModelClient stub = new StubVisionModelClient(
                List.of("not json at all", INVALID_ENUM, StubVisionModelClient.DEFAULT_ANSWER));
        DamageAssessment result = analyzer(stub).analyze(IMAGE, "image/jpeg");

        assertTrue(result.isSuccess());
        assertEquals(3, result.getAttempts());
        assertEquals(3, stub.getCalls());
    }

    @Test
    void retryPromptContainsPreviousViolations() {
        StubVisionModelClient stub = new StubVisionModelClient(
                List.of(INVALID_ENUM, StubVisionModelClient.DEFAULT_ANSWER));
        analyzer(stub).analyze(IMAGE, "image/jpeg");

        List<String> prompts = stub.getReceivedPrompts();
        assertEquals(DamagePrompt.BASE, prompts.get(0));
        assertTrue(prompts.get(1).contains("unknown value 'rust'"));
    }

    @Test
    void failedCallsAreRetried() {
        StubVisionModelClient stub = new StubVisionModelClient(
                Arrays.asList(null, StubVisionModelClient.DEFAULT_ANSWER));
        DamageAssessment result = analyzer(stub).analyze(IMAGE, "image/jpeg");
        assertTrue(result.isSuccess());
        assertEquals(2, result.getAttempts());
    }

    @Test
    void allAttemptsFailingGivesWellFormedErrorResult() {
        StubVisionModelClient stub = new StubVisionModelClient(List.of(INVALID_ENUM));
        DamageAssessment result = analyzer(stub).analyze(IMAGE, "image/jpeg");

        assertFalse(result.isSuccess());
        assertEquals(DamageAssessment.STATUS_ERROR, result.getStatus());
        assertTrue(result.getDamages().isEmpty());
        assertEquals(3, result.getAttempts());
        assertEquals(3, stub.getCalls());
        assertTrue(result.getErrorMessage().contains("unknown value 'rust'"));
    }

    @Test
    void errorResultSerializesToValidJson() throws Exception {
        DamageAssessment result = analyzer(new StubVisionModelClient(List.of("garbage")))
                .analyze(IMAGE, "image/jpeg");
        String json = new ObjectMapper().writeValueAsString(result);
        DamageAssessment roundTrip = new ObjectMapper().readValue(json, DamageAssessment.class);
        assertEquals("error", roundTrip.getStatus());
    }

    @Test
    void badInputIsNotRetried() {
        StubVisionModelClient stub = StubVisionModelClient.withDefaultAnswer();
        assertThrows(IllegalArgumentException.class, () -> analyzer(stub).analyze(IMAGE, "image/gif"));
        assertThrows(IllegalArgumentException.class, () -> analyzer(stub).analyze(new byte[0], "image/jpeg"));
        assertEquals(0, stub.getCalls());
    }

    @Test
    void backoffGrowsBetweenAttempts() {
        List<Long> sleeps = new java.util.ArrayList<>();
        SingleImageAnalyzer a = new SingleImageAnalyzer(new StubVisionModelClient(List.of("x")),
                new ResponseParser(new ObjectMapper()), new ResponseFormatValidator(),
                3, 1000, sleeps::add);
        a.analyze(IMAGE, "image/jpeg");
        assertEquals(List.of(1000L, 2000L), sleeps);
    }
}

package com.cardamage.core.pipeline;

import com.cardamage.core.model.DamageAssessment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResponseParserTest {

    private final ResponseParser parser = new ResponseParser(new ObjectMapper());

    @Test
    void parsesPlainJson() {
        DamageAssessment a = parser.parse(StubVisionModelClient.DEFAULT_ANSWER);
        assertEquals(1, a.getDamages().size());
        assertEquals("dent", a.getDamages().get(0).getDamageType());
        assertEquals(35.0, a.getOverallScore());
        assertNull(a.getStatus(), "status is set by the pipeline, not taken from the model");
    }

    @Test
    void stripsMarkdownFence() {
        String fenced = "```json\n" + StubVisionModelClient.DEFAULT_ANSWER + "\n```";
        assertEquals(1, parser.parse(fenced).getDamages().size());
    }

    @Test
    void rejectsProse() {
        assertThrows(InvalidModelResponseException.class,
                () -> parser.parse("I can see a dent on the door."));
    }

    @Test
    void rejectsBrokenJson() {
        assertThrows(InvalidModelResponseException.class,
                () -> parser.parse("{\"damages\": [ {\"damage_type\": \"dent\" "));
    }

    @Test
    void rejectsEmptyAnswer() {
        assertThrows(InvalidModelResponseException.class, () -> parser.parse("   "));
    }

    @Test
    void wrongTypeForFieldIsRejected() {
        assertThrows(InvalidModelResponseException.class,
                () -> parser.parse("{\"damages\": \"none\", \"overall_score\": 0}"));
    }
}

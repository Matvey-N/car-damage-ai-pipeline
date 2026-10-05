package com.cardamage.core.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Returns pre-defined answers instead of calling a real model.
 *
 * Used in unit tests and as the default client when the app runs with
 * pipeline.model-client=stub, so the whole pipeline can be developed and
 * demonstrated before CarDD access and API use are cleared.
 *
 * Answers are returned in order; after the last one, the last answer is
 * repeated. A null entry simulates a failed call (ModelCallException).
 */
public class StubVisionModelClient implements VisionModelClient {

    public static final String DEFAULT_ANSWER = """
            {
              "damages": [
                {
                  "damage_type": "scratch",
                  "part": "door",
                  "severity": "moderate",
                  "action": "repair",
                  "confidence": 0.85,
                  "description": "STUB: scratch on front door",
                  "bounding_box": [0.30, 0.40, 0.20, 0.15]
                }
              ],
              "overall_score": 35
            }
            """;

    private final List<String> answers;
    private final List<String> receivedPrompts = new ArrayList<>();
    private int calls = 0;

    public StubVisionModelClient(List<String> answers) {
        if (answers == null || answers.isEmpty()) {
            throw new IllegalArgumentException("at least one answer is required");
        }
        this.answers = new ArrayList<>(answers);
    }

    public static StubVisionModelClient withDefaultAnswer() {
        return new StubVisionModelClient(List.of(DEFAULT_ANSWER));
    }

    @Override
    public synchronized String analyze(byte[] image, String mediaType, String prompt) {
        receivedPrompts.add(prompt);
        String answer = answers.get(Math.min(calls, answers.size() - 1));
        calls++;
        if (answer == null) {
            throw new ModelCallException("stub: simulated call failure");
        }
        return answer;
    }

    public synchronized int getCalls() {
        return calls;
    }

    public synchronized List<String> getReceivedPrompts() {
        return new ArrayList<>(receivedPrompts);
    }
}

package com.cardamage.core.pipeline;

/**
 * One call to a vision model: image + prompt in, raw text answer out.
 *
 * Implementations: AnthropicVisionModelClient (real API, in the web
 * package) and StubVisionModelClient (canned answers, for tests and for
 * development before CarDD access is granted).
 */
public interface VisionModelClient {

    /**
     * @return the model's raw text answer (expected to be JSON)
     * @throws ModelCallException if the call itself failed (network, HTTP
     *         error, truncated/empty answer)
     */
    String analyze(byte[] image, String mediaType, String prompt);
}

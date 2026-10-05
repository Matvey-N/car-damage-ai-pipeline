package com.cardamage.core.pipeline;

/**
 * Prepares an uploaded image before it is sent to the model.
 * The bounding boxes the model returns are normalized to [0, 1], so they
 * stay valid when the image is scaled down (the aspect ratio is kept).
 */
public interface ImagePreprocessor {

    /** Leaves the image unchanged. */
    ImagePreprocessor NONE = (bytes, mediaType) -> new PreparedImage(bytes, mediaType);

    /** @throws IllegalArgumentException if the image cannot be read */
    PreparedImage prepare(byte[] bytes, String mediaType);
}

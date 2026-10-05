package com.cardamage.core.pipeline;

/** Image bytes as they are sent to the model. */
public record PreparedImage(byte[] bytes, String mediaType) {
}

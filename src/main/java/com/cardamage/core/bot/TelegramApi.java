package com.cardamage.core.bot;

import java.util.List;

/** The few Telegram Bot API calls the demo bot needs. */
public interface TelegramApi {

    /**
     * One incoming message. fileId is set if the message carries an image
     * (a photo, or a JPEG/PNG sent as a file); text is set for text messages.
     */
    record Update(long updateId, long chatId, String text, String fileId, String mediaType) {
    }

    /** Long polling: waits for new messages with update_id >= offset. */
    List<Update> getUpdates(long offset) throws Exception;

    void sendMessage(long chatId, String text) throws Exception;

    byte[] downloadFile(String fileId) throws Exception;
}

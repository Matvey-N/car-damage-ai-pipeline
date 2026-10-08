package com.cardamage.web;

import com.cardamage.core.bot.TelegramApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Telegram Bot API over plain HTTPS (JDK HttpClient, no extra library).
 * Uses long polling (getUpdates), so no public address or hosting is needed.
 */
public class TelegramHttpApi implements TelegramApi {

    private static final int POLL_SECONDS = 30;

    private final String apiBase;
    private final String fileBase;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public TelegramHttpApi(String baseUrl, String token, ObjectMapper mapper) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiBase = base + "/bot" + token + "/";
        this.fileBase = base + "/file/bot" + token + "/";
        this.mapper = mapper;
    }

    @Override
    public List<Update> getUpdates(long offset) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("offset", offset);
        body.put("timeout", POLL_SECONDS);
        body.put("allowed_updates", List.of("message"));
        JsonNode result = call("getUpdates", body, POLL_SECONDS + 15);

        List<Update> updates = new ArrayList<>();
        for (JsonNode u : result) {
            long updateId = u.path("update_id").asLong();
            JsonNode message = u.path("message");
            long chatId = message.path("chat").path("id").asLong();
            String text = message.hasNonNull("text") ? message.get("text").asText() : null;
            String fileId = null;
            String mediaType = null;

            JsonNode photo = message.path("photo");
            JsonNode document = message.path("document");
            if (photo.isArray() && !photo.isEmpty()) {
                // sizes are listed from smallest to largest; Telegram photos are JPEG
                fileId = photo.get(photo.size() - 1).path("file_id").asText();
                mediaType = "image/jpeg";
            } else if (document.hasNonNull("file_id")) {
                // an image sent "as a file" keeps its original quality
                fileId = document.get("file_id").asText();
                mediaType = document.path("mime_type").asText("");
            }
            updates.add(new Update(updateId, chatId, text, fileId, mediaType));
        }
        return updates;
    }

    @Override
    public void sendMessage(long chatId, String text) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        call("sendMessage", body, 30);
    }

    @Override
    public void sendWebAppButton(long chatId, String text, String buttonText, String url) throws Exception {
        Map<String, Object> button = Map.of("text", buttonText, "web_app", Map.of("url", url));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.put("reply_markup", Map.of("inline_keyboard", List.of(List.of(button))));
        call("sendMessage", body, 30);
    }

    @Override
    public void setMenuButton(String buttonText, String url) throws Exception {
        Map<String, Object> menu = Map.of("type", "web_app", "text", buttonText, "web_app", Map.of("url", url));
        call("setChatMenuButton", Map.of("menu_button", menu), 30);
    }

    @Override
    public byte[] downloadFile(String fileId) throws Exception {
        String path = call("getFile", Map.of("file_id", fileId), 30).path("file_path").asText();
        HttpRequest request = HttpRequest.newBuilder(URI.create(fileBase + path))
                .timeout(Duration.ofSeconds(120)).GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Telegram file download failed: HTTP " + response.statusCode());
        }
        return response.body();
    }

    /** Never put the request URL into an exception or a log: it contains the bot token. */
    private JsonNode call(String method, Map<String, Object> body, int timeoutSeconds) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + method))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = mapper.readTree(response.body());
        if (response.statusCode() != 200 || !json.path("ok").asBoolean(false)) {
            throw new IllegalStateException("Telegram " + method + " failed: HTTP " + response.statusCode()
                    + " " + json.path("description").asText(""));
        }
        return json.path("result");
    }
}

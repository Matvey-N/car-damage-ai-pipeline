package com.cardamage.core.bot;

import com.cardamage.core.bot.TelegramApi.Update;
import com.cardamage.core.demo.DamageMergeService;
import com.cardamage.core.demo.PriceEstimator;
import com.cardamage.core.model.Damage;
import com.cardamage.core.model.DamageAssessment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DemoBotTest {

    /** Records what the bot sends; every file "downloads" as its own id. */
    private static class FakeTelegram implements TelegramApi {
        final List<String> sent = new ArrayList<>();

        @Override
        public List<Update> getUpdates(long offset) {
            return List.of();
        }

        @Override
        public void sendMessage(long chatId, String text) {
            sent.add(chatId + ": " + text);
        }

        @Override
        public byte[] downloadFile(String fileId) {
            return fileId.getBytes();
        }

        @Override
        public void sendWebAppButton(long chatId, String text, String buttonText, String url) {
            sent.add(chatId + ": [button " + buttonText + " -> " + url + "] " + text);
        }

        @Override
        public void setMenuButton(String buttonText, String url) {
        }

        @Override
        public void sendDocument(long chatId, String fileName, byte[] content, String caption) {
            sent.add(chatId + ": [file " + fileName + "] " + caption);
        }

        String last() {
            return sent.get(sent.size() - 1);
        }
    }

    private static final long CHAT = 7;
    private FakeTelegram telegram;
    private DemoBot bot;
    private long nextId = 1;

    @BeforeEach
    void setUp() {
        telegram = new FakeTelegram();
        DemoBot.PhotoAnalyzer analyzer = (image, mediaType) -> {
            String name = new String(image);
            if (name.equals("bad-format")) {
                throw new IllegalArgumentException("unsupported media type");
            }
            if (name.equals("model-failed")) {
                return DamageAssessment.error("no valid answer", 3);
            }
            return DamageAssessment.success(List.of(
                    new Damage("lamp_broken", "light", "severe", "replacement", 0.9, List.of(0.1, 0.1, 0.2, 0.2)),
                    new Damage("scratch", "door", "minor", "repair", 0.6, List.of(0.5, 0.5, 0.2, 0.1))), 50, 1);
        };
        bot = new DemoBot(telegram, analyzer, new DamageMergeService(),
                PriceEstimator.fromClasspath(new ObjectMapper()));
    }

    private void photo(String fileId) throws Exception {
        bot.handle(new Update(nextId++, CHAT, null, fileId, "image/jpeg"));
    }

    private void text(String text) throws Exception {
        bot.handle(new Update(nextId++, CHAT, text, null, null));
    }

    @Test
    void startExplainsWhatToDo() throws Exception {
        text("/start");
        assertTrue(telegram.last().contains("/report"));
        assertTrue(telegram.last().contains("демонстрация"));
    }

    @Test
    void reportNeedsAtLeastThreePhotos() throws Exception {
        photo("a");
        photo("b");
        text("/report");
        assertTrue(telegram.last().contains("минимум 3"), telegram.last());
    }

    @Test
    void threePhotosGiveMergedReportWithPrices() throws Exception {
        photo("a");
        photo("b");
        photo("c");
        assertTrue(telegram.last().contains("Фото 3 принято"));
        text("/report@some_bot");
        String report = telegram.last();
        assertTrue(report.contains("Отчёт по 3 фото"), report);
        // the same two damages on three photos are merged into two entries
        assertTrue(report.contains("1. ") && report.contains("2. ") && !report.contains("3. "), report);
        assertTrue(report.contains("замена") && report.contains("ремонт"));
        // light replacement 150-600 + door repair 200-450
        assertTrue(report.contains("Итого ориентировочно: 350–1050 EUR"), report);
        assertTrue(report.contains("не реальная смета"));

        text("/report");
        assertTrue(telegram.last().contains("Принято фото: 0"), "the session is cleared after a report");
    }

    @Test
    void failedPhotosAreNotCounted() throws Exception {
        photo("bad-format");
        assertTrue(telegram.last().contains("JPEG или PNG"));
        photo("model-failed");
        assertTrue(telegram.last().contains("не дала корректный ответ"));
        photo("a");
        assertTrue(telegram.last().contains("Фото 1 принято"));
    }

    @Test
    void noMoreThanTenPhotos() throws Exception {
        for (int i = 0; i < 10; i++) {
            photo("p" + i);
        }
        photo("one-too-many");
        assertTrue(telegram.last().contains("максимум"));
        text("/report");
        assertTrue(telegram.last().contains("Отчёт по 10 фото"));
    }

    @Test
    void appCommandSendsTheMiniAppButtonWhenConfigured() throws Exception {
        text("/app");
        assertTrue(telegram.last().contains("не настроен"));
        bot = new DemoBot(telegram, (image, mediaType) -> DamageAssessment.success(List.of(), 0, 1),
                new DamageMergeService(), PriceEstimator.fromClasspath(new ObjectMapper()),
                "https://example.trycloudflare.com/miniapp/index.html");
        text("/app");
        assertTrue(telegram.last().contains("[button Открыть осмотр -> https://example.trycloudflare.com/miniapp/index.html]"));
        text("/start");
        assertTrue(telegram.last().contains("/app"));
    }

    @Test
    void startResetsTheSession() throws Exception {
        photo("a");
        photo("b");
        photo("c");
        text("/start");
        text("/report");
        assertTrue(telegram.last().contains("Принято фото: 0"));
    }
}

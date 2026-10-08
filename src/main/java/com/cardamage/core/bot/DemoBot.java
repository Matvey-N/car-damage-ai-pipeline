package com.cardamage.core.bot;

import com.cardamage.core.bot.TelegramApi.Update;
import com.cardamage.core.demo.DamageMergeService;
import com.cardamage.core.demo.PriceEstimator;
import com.cardamage.core.model.Damage;
import com.cardamage.core.model.DamageAssessment;
import com.cardamage.core.model.Labels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DEMO only (TZ section 12, scenario B): a minimal Telegram front end.
 *
 * The user sends 3-10 photos of one car, then /report. Every photo goes
 * through the same single-image pipeline as the benchmark; the per-photo
 * results are merged by DamageMergeService and a demo price range is added.
 * Sessions are kept in memory only.
 *
 * No Telegram or Spring classes here: the bot talks to Telegram through
 * the TelegramApi interface, so it can be tested with a fake.
 */
public class DemoBot {

    public static final int MIN_PHOTOS = 3;
    public static final int MAX_PHOTOS = 10;

    /** The single-image pipeline (SingleImageAnalyzer::analyze). */
    @FunctionalInterface
    public interface PhotoAnalyzer {
        DamageAssessment analyze(byte[] image, String mediaType);
    }

    private static final Locale RU = Locale.forLanguageTag("ru");

    static final String HELP = "Это демонстрация исследовательского прототипа, а не оценка для страховой или сервиса.\n\n"
            + "Пришлите от " + MIN_PHOTOS + " до " + MAX_PHOTOS + " фотографий одного автомобиля (по одной или альбомом), "
            + "затем отправьте /report.\n/start — начать заново.";

    private final TelegramApi telegram;
    private final PhotoAnalyzer analyzer;
    private final DamageMergeService mergeService;
    private final PriceEstimator prices;
    private final Map<Long, List<DamageAssessment>> sessions = new HashMap<>();
    private final String miniAppUrl;

    public DemoBot(TelegramApi telegram, PhotoAnalyzer analyzer,
                   DamageMergeService mergeService, PriceEstimator prices) {
        this(telegram, analyzer, mergeService, prices, null);
    }

    /** miniAppUrl: HTTPS address of the Mini App page, or null if it is not set up. */
    public DemoBot(TelegramApi telegram, PhotoAnalyzer analyzer,
                   DamageMergeService mergeService, PriceEstimator prices, String miniAppUrl) {
        this.miniAppUrl = miniAppUrl == null || miniAppUrl.isBlank() ? null : miniAppUrl.trim();
        this.telegram = telegram;
        this.analyzer = analyzer;
        this.mergeService = mergeService;
        this.prices = prices;
    }

    /** Handles one incoming message. Never throws for a problem with that message. */
    public void handle(Update update) throws Exception {
        long chat = update.chatId();
        if (update.fileId() != null) {
            handlePhoto(chat, update);
            return;
        }
        String text = update.text() == null ? "" : update.text().trim();
        String command = text.split("[\\s@]", 2)[0].toLowerCase(Locale.ROOT);
        switch (command) {
            case "/start" -> {
                sessions.remove(chat);
                telegram.sendMessage(chat, help());
            }
            case "/report" -> handleReport(chat);
            case "/app" -> {
                if (miniAppUrl == null) {
                    telegram.sendMessage(chat, "Mini App не настроен на этом сервере. Пришлите фото прямо сюда.");
                } else {
                    telegram.sendWebAppButton(chat, "Приложение для осмотра: рамки повреждений на фото, пошаговая съёмка, "
                            + "сравнение при выдаче и возврате, история и PDF-отчёт.",
                            "Открыть осмотр", miniAppUrl);
                }
            }
            default -> telegram.sendMessage(chat, help());
        }
    }

    String help() {
        return miniAppUrl == null ? HELP : HELP + "\n/app — приложение: рамки повреждений на фото, пошаговая съёмка, "
                + "осмотр при выдаче и возврате арендной машины, история осмотров и PDF-отчёт.";
    }

    private void handlePhoto(long chat, Update update) throws Exception {
        List<DamageAssessment> photos = sessions.computeIfAbsent(chat, k -> new ArrayList<>());
        if (photos.size() >= MAX_PHOTOS) {
            telegram.sendMessage(chat, "Уже принято " + MAX_PHOTOS + " фото — это максимум. "
                    + "Отправьте /report или /start, чтобы начать заново.");
            return;
        }
        DamageAssessment result;
        try {
            byte[] image = telegram.downloadFile(update.fileId());
            result = analyzer.analyze(image, update.mediaType());
        } catch (IllegalArgumentException e) {
            telegram.sendMessage(chat, "Не удалось прочитать изображение (нужен JPEG или PNG). Фото не учтено.");
            return;
        }
        if (!result.isSuccess()) {
            telegram.sendMessage(chat, "Не удалось проанализировать это фото "
                    + "(модель не дала корректный ответ после " + result.getAttempts() + " попыток). Фото не учтено.");
            return;
        }
        if (Boolean.FALSE.equals(result.getVehicleVisible())) {
            telegram.sendMessage(chat, "На этом фото не видно автомобиля — фото не учтено.");
            return;
        }
        photos.add(result);
        int n = photos.size();
        String next = n < MIN_PHOTOS
                ? "Нужно ещё минимум " + (MIN_PHOTOS - n) + "."
                : "Можно отправить /report или добавить ещё фото (до " + MAX_PHOTOS + ").";
        telegram.sendMessage(chat, "Фото " + n + " принято: найдено повреждений — "
                + result.getDamages().size() + ". " + next);
    }

    private void handleReport(long chat) throws Exception {
        List<DamageAssessment> photos = sessions.getOrDefault(chat, List.of());
        if (photos.size() < MIN_PHOTOS) {
            telegram.sendMessage(chat, "Принято фото: " + photos.size() + ". Для отчёта нужно минимум "
                    + MIN_PHOTOS + ".");
            return;
        }
        DamageAssessment merged = mergeService.merge(photos);
        telegram.sendMessage(chat, formatReport(merged, photos.size()));
        sessions.remove(chat);
    }

    String formatReport(DamageAssessment merged, int photoCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("Отчёт по ").append(photoCount).append(" фото\n\n");
        List<Damage> damages = merged.getDamages();
        if (damages.isEmpty()) {
            sb.append("Видимых повреждений не найдено.\n");
        }
        int i = 1;
        for (Damage d : damages) {
            sb.append(i++).append(". ")
                    .append(Labels.part(d.getPart())).append(": ")
                    .append(Labels.type(d.getDamageType()).toLowerCase(RU)).append("\n   ")
                    .append(Labels.severity(d.getSeverity())).append(", ")
                    .append(Labels.action(d.getAction()))
                    .append(", уверенность ").append(Math.round(d.getConfidence() * 100)).append("%");
            PriceEstimator.Range r = prices.priceOf(d);
            if (r != null) {
                sb.append("\n   ориентир: ").append(r.min()).append("–").append(r.max())
                        .append(" ").append(prices.currency());
            }
            sb.append("\n");
        }
        if (!damages.isEmpty()) {
            PriceEstimator.Range total = prices.total(damages);
            sb.append("\nИтого ориентировочно: ").append(total.min()).append("–").append(total.max())
                    .append(" ").append(prices.currency()).append("\n");
        }
        sb.append("\nЭто демонстрация. Цены — условный справочник, не реальная смета. "
                + "Модель пропускает часть мелких повреждений (см. отчёт о benchmark).");
        return sb.toString();
    }
}

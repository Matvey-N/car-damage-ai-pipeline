package com.cardamage.web;

import com.cardamage.core.bot.DemoBot;
import com.cardamage.core.bot.TelegramApi;
import com.cardamage.core.demo.DamageMergeService;
import com.cardamage.core.demo.PriceEstimator;
import com.cardamage.core.pipeline.AnalysisProfile;
import com.cardamage.core.pipeline.SingleImageAnalyzer;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runs the demo Telegram bot inside the service, in one background thread.
 * Disabled unless TELEGRAM_BOT_TOKEN is set, so the benchmark and the tests
 * are not affected.
 */
@Component
public class TelegramBotRunner {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotRunner.class);
    private static final long ERROR_PAUSE_MS = 5000;

    private final String token;
    private final String miniAppUrl;
    private final boolean polling;
    private final String baseUrl;
    private final SingleImageAnalyzer analyzer;
    private final DamageMergeService mergeService;
    private final ObjectMapper mapper;
    private volatile boolean running;
    private Thread thread;

    public TelegramBotRunner(@Value("${telegram.bot-token:}") String token,
                             @Value("${telegram.base-url:https://api.telegram.org}") String baseUrl,
                             @Value("${telegram.miniapp.url:}") String miniAppUrl,
                             @Value("${telegram.polling:true}") boolean polling,
                             SingleImageAnalyzer analyzer,
                             DamageMergeService mergeService,
                             ObjectMapper mapper) {
        this.token = token == null ? "" : token.trim();
        this.miniAppUrl = miniAppUrl == null ? "" : miniAppUrl.trim();
        this.polling = polling;
        this.baseUrl = baseUrl;
        this.analyzer = analyzer;
        this.mergeService = mergeService;
        this.mapper = mapper;
    }

    @PostConstruct
    public void start() {
        if (token.isEmpty() || !polling) {
            log.info("Telegram demo bot is off (TELEGRAM_BOT_TOKEN is not set or telegram.polling=false)");
            return;
        }
        TelegramApi telegram = new TelegramHttpApi(baseUrl, token, mapper);
        DemoBot bot = new DemoBot(telegram,
                (image, mediaType) -> analyzer.analyze(image, mediaType, AnalysisProfile.GENERAL), mergeService, PriceEstimator.fromClasspath(mapper),
                miniAppUrl.isEmpty() ? null : miniAppUrl);
        if (!miniAppUrl.isEmpty()) {
            if (!miniAppUrl.startsWith("https://")) {
                log.warn("TELEGRAM_MINIAPP_URL must start with https:// - Telegram refuses other addresses");
            } else {
                try {
                    telegram.setMenuButton("Осмотр", miniAppUrl);
                    log.info("Mini App menu button set");
                } catch (Exception e) {
                    log.warn("Could not set the Mini App menu button: {}", e.getMessage());
                }
            }
        }
        running = true;
        thread = new Thread(() -> poll(telegram, bot), "telegram-demo-bot");
        thread.setDaemon(true);
        thread.start();
        log.info("Telegram demo bot started (long polling)");
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void poll(TelegramApi telegram, DemoBot bot) {
        long offset = 0;
        while (running) {
            try {
                for (TelegramApi.Update update : telegram.getUpdates(offset)) {
                    offset = update.updateId() + 1;
                    try {
                        bot.handle(update);
                    } catch (InterruptedException e) {
                        throw e;
                    } catch (Exception e) {
                        // messages only: a stack trace or URL could expose the token
                        log.warn("Telegram bot: could not handle a message: {}", e.getMessage());
                        trySend(telegram, update.chatId());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Telegram bot: polling failed, retrying: {}", e.getMessage());
                try {
                    Thread.sleep(ERROR_PAUSE_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void trySend(TelegramApi telegram, long chatId) {
        try {
            telegram.sendMessage(chatId, "Произошла ошибка при обработке сообщения. Попробуйте ещё раз.");
        } catch (Exception ignored) {
            // nothing more can be done for this message
        }
    }
}

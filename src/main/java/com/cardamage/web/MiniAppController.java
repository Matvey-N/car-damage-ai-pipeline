package com.cardamage.web;

import com.cardamage.core.demo.DamageMergeService;
import com.cardamage.core.demo.PriceEstimator;
import com.cardamage.core.miniapp.InitDataValidator;
import com.cardamage.core.miniapp.RateLimiter;
import com.cardamage.core.model.Damage;
import com.cardamage.core.model.DamageAssessment;
import com.cardamage.core.pipeline.SingleImageAnalyzer;
import com.cardamage.core.pipeline.TiledImageAnalyzer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backend of the Telegram Mini App (DEMO, secondary). The page itself is
 * static: src/main/resources/static/miniapp/index.html.
 *
 *   GET  /api/v1/miniapp/config   - is the Mini App enabled, limits
 *   POST /api/v1/miniapp/analyze  - 1-10 photos; header X-Telegram-Init-Data
 *
 * Every request must carry Telegram's signed initData (checked with the bot
 * token), and each user may analyze a limited number of photos per hour, so
 * the API key cannot be spent by someone who only knows the tunnel address.
 * The answer lists every photo with its boxes (for drawing) plus a merged
 * summary with the demo price range.
 */
@RestController
@RequestMapping("/api/v1/miniapp")
public class MiniAppController {

    static final int MAX_PHOTOS = 10;
    private static final long INIT_DATA_MAX_AGE_SECONDS = 24 * 3600;

    private final SingleImageAnalyzer analyzer;
    private final TiledImageAnalyzer tiledAnalyzer;
    private final DamageMergeService mergeService;
    private final PriceEstimator prices;
    private final InitDataValidator validator;   // null = Mini App disabled (no bot token)
    private final RateLimiter limiter;
    private final String clientType;

    public MiniAppController(SingleImageAnalyzer analyzer,
                             TiledImageAnalyzer tiledAnalyzer,
                             DamageMergeService mergeService,
                             ObjectMapper mapper,
                             @Value("${telegram.bot-token:}") String botToken,
                             @Value("${telegram.miniapp.photos-per-hour:30}") int photosPerHour,
                             @Value("${pipeline.model-client}") String clientType) {
        this.analyzer = analyzer;
        this.tiledAnalyzer = tiledAnalyzer;
        this.mergeService = mergeService;
        this.prices = PriceEstimator.fromClasspath(mapper);
        this.validator = botToken == null || botToken.isBlank() ? null
                : new InitDataValidator(botToken, INIT_DATA_MAX_AGE_SECONDS);
        this.limiter = new RateLimiter(photosPerHour, 3600);
        this.clientType = clientType;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("enabled", validator != null);
        c.put("max_photos", MAX_PHOTOS);
        c.put("photos_per_hour", limiter.limit());
        c.put("model_client", clientType);
        c.put("tiled_mode", tiledAnalyzer.describe());
        return c;
    }

    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyze(
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData,
            @RequestParam("images") MultipartFile[] images,
            @RequestParam(value = "mode", defaultValue = "whole") String mode) throws IOException {
        if (validator == null) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "Mini App is off: the service has no TELEGRAM_BOT_TOKEN");
        }
        long now = System.currentTimeMillis() / 1000;
        long userId = validator.validate(initData, now);
        if (images.length < 1 || images.length > MAX_PHOTOS) {
            throw new IllegalArgumentException("send 1-" + MAX_PHOTOS + " photos, got " + images.length);
        }
        if (!mode.equals("whole") && !mode.equals("tiled")) {
            throw new IllegalArgumentException("unknown mode '" + mode + "'");
        }
        int units = mode.equals("tiled") ? images.length * 5 : images.length;
        if (!limiter.tryAcquire(userId, units, now)) {
            return error(HttpStatus.TOO_MANY_REQUESTS, "Limit reached: " + limiter.limit()
                    + " photo analyses per hour (detailed mode counts 5 per photo). Try again later.");
        }

        List<Map<String, Object>> photos = new ArrayList<>();
        List<DamageAssessment> results = new ArrayList<>();
        for (MultipartFile image : images) {
            Map<String, Object> photo = new LinkedHashMap<>();
            photo.put("name", image.getOriginalFilename());
            try {
                DamageAssessment r = mode.equals("tiled")
                        ? tiledAnalyzer.analyze(image.getBytes(), image.getContentType())
                        : analyzer.analyze(image.getBytes(), image.getContentType());
                results.add(r);
                photo.put("status", r.getStatus());
                photo.put("damages", r.getDamages());
                if (r.getErrorMessage() != null) {
                    photo.put("error_message", r.getErrorMessage());
                }
            } catch (IllegalArgumentException e) {
                photo.put("status", "error");
                photo.put("damages", List.of());
                photo.put("error_message", "not a readable JPEG or PNG");
            }
            photos.add(photo);
        }

        DamageAssessment merged = mergeService.merge(results);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", merged.getStatus());
        summary.put("damages", merged.getDamages());
        List<Map<String, Object>> priced = new ArrayList<>();
        for (Damage d : merged.getDamages()) {
            PriceEstimator.Range r = prices.priceOf(d);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("min", r == null ? null : r.min());
            p.put("max", r == null ? null : r.max());
            priced.add(p);
        }
        summary.put("prices", priced);
        PriceEstimator.Range total = prices.total(merged.getDamages());
        summary.put("total", Map.of("min", total.min(), "max", total.max(), "currency", prices.currency()));
        summary.put("note", "Demo: prices come from an invented reference table, not a real estimate. "
                + "The model misses part of the small damages (see the benchmark report).");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", mode);
        body.put("photos", photos);
        body.put("summary", summary);
        return ResponseEntity.ok(body);
    }

    @ExceptionHandler(InitDataValidator.InvalidInitDataException.class)
    public ResponseEntity<Map<String, Object>> notFromTelegram(InitDataValidator.InvalidInitDataException e) {
        return error(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badInput(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("error_message", message);
        return ResponseEntity.status(status).body(body);
    }
}

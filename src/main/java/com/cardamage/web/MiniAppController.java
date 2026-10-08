package com.cardamage.web;

import com.cardamage.core.demo.DamageMergeService;
import com.cardamage.core.demo.PriceEstimator;
import com.cardamage.core.inspect.BeforeAfterComparator;
import com.cardamage.core.inspect.InspectionStore;
import com.cardamage.core.inspect.InspectionSummary;
import com.cardamage.core.inspect.Views;
import com.cardamage.core.miniapp.AnalysisJobs;
import com.cardamage.core.miniapp.InitDataValidator;
import com.cardamage.core.miniapp.RateLimiter;
import com.cardamage.core.model.Damage;
import com.cardamage.core.model.DamageAssessment;
import com.cardamage.core.pipeline.AnalysisProfile;
import com.cardamage.core.pipeline.RelookAnalyzer;
import com.cardamage.core.pipeline.ResponseFormatValidator;
import com.cardamage.core.pipeline.SingleImageAnalyzer;
import com.cardamage.core.pipeline.TiledImageAnalyzer;
import com.cardamage.core.report.ReportRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Backend of the Telegram Mini App (DEMO, secondary). The page is static:
 * src/main/resources/static/miniapp/index.html.
 *
 *   GET    /api/v1/miniapp/config                                 enabled?, limits, views
 *   POST   /api/v1/miniapp/analyze                                photos -> stored inspection (async=true: job)
 *   GET    /api/v1/miniapp/jobs, /jobs/{id}                       background analyses and their progress
 *   GET    /api/v1/miniapp/inspections                            the user's inspections, newest first
 *   GET    /api/v1/miniapp/inspections/{id}                       one inspection (+ comparison for a return)
 *   GET    /api/v1/miniapp/inspections/{id}/photos/{index}        the photo
 *   PUT    /api/v1/miniapp/inspections/{id}/photos/{index}/damages  the user's correction
 *   DELETE /api/v1/miniapp/inspections/{id}
 *   GET    /api/v1/miniapp/inspections/{id}/report.pdf            PDF report
 *   POST   /api/v1/miniapp/inspections/{id}/report                PDF report sent to the chat with the bot
 *
 * Every request carries Telegram's signed initData (header X-Telegram-Init-Data);
 * a user only ever sees his own inspections. Analyses are limited per user and hour.
 */
@RestController
@RequestMapping("/api/v1/miniapp")
public class MiniAppController {

    static final int MAX_PHOTOS = 12;
    private static final long INIT_DATA_MAX_AGE_SECONDS = 24 * 3600;
    private static final Set<String> KINDS = Set.of("single", "before", "after");
    /** Mini App works with real photos of any car. */
    static final AnalysisProfile PROFILE = AnalysisProfile.GENERAL;
    /** Mode -> model calls per photo (counted by the hourly limit). */
    static final Map<String, Integer> MODE_COST = Map.of("whole", 1, "relook", 2, "tiled", 5);
    /** No poll for this long = the user has left the page; he gets a chat message when the job ends. */
    private static final long LEFT_AFTER_SECONDS = 20;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.forLanguageTag("ru"));

    private final SingleImageAnalyzer analyzer;
    private final TiledImageAnalyzer tiledAnalyzer;
    private final RelookAnalyzer relookAnalyzer;
    private final ExecutorService photoPool;
    private final AnalysisJobs jobs;
    private final DamageMergeService mergeService;
    private final InspectionStore store;
    private final PriceEstimator prices;
    private final ReportRenderer reports = new ReportRenderer();
    private final ResponseFormatValidator validator = ResponseFormatValidator.general();
    private final InitDataValidator initData;   // null = Mini App disabled (no bot token)
    private final TelegramHttpApi telegram;     // null without a token
    private final RateLimiter limiter;
    private final int keepPerUser;
    private final String clientType;
    private final String miniAppUrl;

    public MiniAppController(SingleImageAnalyzer analyzer,
                             TiledImageAnalyzer tiledAnalyzer,
                             RelookAnalyzer relookAnalyzer,
                             AnalysisExecutors executors,
                             DamageMergeService mergeService,
                             InspectionStore store,
                             ObjectMapper mapper,
                             @Value("${telegram.bot-token:}") String botToken,
                             @Value("${telegram.base-url:https://api.telegram.org}") String telegramUrl,
                             @Value("${telegram.miniapp.photos-per-hour:30}") int photosPerHour,
                             @Value("${pipeline.storage.inspections-per-user:50}") int keepPerUser,
                             @Value("${pipeline.model-client}") String clientType,
                             @Value("${telegram.miniapp.url:}") String miniAppUrl,
                             @Value("${pipeline.jobs.per-user:1}") int jobsPerUser,
                             @Value("${pipeline.jobs.keep-minutes:60}") int keepMinutes) {
        this.analyzer = analyzer;
        this.tiledAnalyzer = tiledAnalyzer;
        this.relookAnalyzer = relookAnalyzer;
        this.photoPool = executors.photos();
        this.miniAppUrl = miniAppUrl == null || miniAppUrl.isBlank() ? null : miniAppUrl.trim();
        this.jobs = new AnalysisJobs(executors.jobs(), jobsPerUser, keepMinutes * 60L,
                MiniAppController::now, this::notifyIfLeft);
        this.mergeService = mergeService;
        this.store = store;
        this.prices = PriceEstimator.fromClasspath(mapper);
        boolean enabled = botToken != null && !botToken.isBlank();
        this.initData = enabled ? new InitDataValidator(botToken, INIT_DATA_MAX_AGE_SECONDS) : null;
        this.telegram = enabled ? new TelegramHttpApi(telegramUrl, botToken.trim(), mapper) : null;
        this.limiter = new RateLimiter(photosPerHour, 3600);
        this.keepPerUser = keepPerUser;
        this.clientType = clientType;
    }

    // ------------------------------------------------------------------ endpoints

    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("enabled", initData != null);
        c.put("max_photos", MAX_PHOTOS);
        c.put("photos_per_hour", limiter.limit());
        c.put("model_client", clientType);
        c.put("tiled_mode", tiledAnalyzer.describe());
        c.put("modes", MODE_COST);
        c.put("profile", PROFILE.name() + " (prompt " + PROFILE.promptVersion() + ")");
        c.put("views", Views.NAMES);
        return c;
    }

    /**
     * Starts the analysis of 1-12 photos. With async=true (the page uses it) the answer
     * comes at once: 202 and a job id to follow with GET /jobs/{id}; otherwise the
     * request waits for the stored inspection. Photos are analyzed in parallel.
     */
    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyze(
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String auth,
            @RequestParam("images") MultipartFile[] images,
            @RequestParam(value = "views", required = false) List<String> views,
            @RequestParam(value = "mode", defaultValue = "whole") String mode,
            @RequestParam(value = "kind", defaultValue = "single") String kind,
            @RequestParam(value = "before_id", required = false) String beforeId,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "async", defaultValue = "false") boolean async) throws IOException {
        long user = user(auth);
        if (images.length < 1 || images.length > MAX_PHOTOS) {
            throw new IllegalArgumentException("send 1-" + MAX_PHOTOS + " photos, got " + images.length);
        }
        if (!MODE_COST.containsKey(mode)) {
            throw new IllegalArgumentException("unknown mode '" + mode + "' (expected " + MODE_COST.keySet() + ")");
        }
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("unknown kind '" + kind + "'");
        }
        if (views != null && views.size() != images.length) {
            throw new IllegalArgumentException("views must list one view per photo");
        }
        if (views != null && views.stream().anyMatch(v -> !Views.isKnown(v))) {
            throw new IllegalArgumentException("unknown view in " + views);
        }
        if (kind.equals("after")) {
            InspectionStore.Inspection before = owned(beforeId, user);
            if (!before.kind().equals("before")) {
                throw new IllegalArgumentException("before_id must point to a pickup (before) inspection");
            }
        } else {
            beforeId = null;
        }
        // the uploaded files are only valid during this request: copy them first
        List<Input> inputs = new ArrayList<>();
        for (int i = 0; i < images.length; i++) {
            inputs.add(new Input(images[i].getBytes(), images[i].getContentType(), views == null ? null : views.get(i)));
        }
        int units = images.length * MODE_COST.get(mode);
        if (!limiter.tryAcquire(user, units, now())) {
            return error(HttpStatus.TOO_MANY_REQUESTS, "Limit reached: " + limiter.limit()
                    + " photo analyses per hour (careful mode counts 2 per photo, detailed mode 5). Try again later.");
        }
        String cleanTitle = title == null || title.isBlank() ? null : title.strip().substring(0, Math.min(80, title.strip().length()));
        Plan plan = new Plan(user, inputs, mode, kind, cleanTitle, beforeId);

        if (async) {
            AnalysisJobs.Job job;
            try {
                job = jobs.submit(user, inputs.size(), photoDone -> execute(plan, photoDone));
            } catch (AnalysisJobs.TooManyJobs e) {
                limiter.release(user, units);
                return error(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
            }
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(jobView(job));
        }
        try {
            return ResponseEntity.ok(view(owned(execute(plan, () -> { }), user)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error(HttpStatus.SERVICE_UNAVAILABLE, "interrupted");
        }
    }

    /** Progress of a background analysis; when done, the whole inspection is included. */
    @GetMapping("/jobs/{id}")
    public Map<String, Object> job(@RequestHeader(value = "X-Telegram-Init-Data", required = false) String auth,
                                   @PathVariable String id) {
        long user = user(auth);
        AnalysisJobs.Job job = jobs.get(id, user).orElseThrow(() -> new NotFound("job not found"));
        Map<String, Object> m = jobView(job);
        if (job.state() == AnalysisJobs.State.DONE) {
            store.find(job.inspectionId()).filter(i -> i.userId() == user).ifPresent(i -> m.put("inspection", view(i)));
        }
        return m;
    }

    /** Unfinished analyses of the user: the page shows them when it is reopened. */
    @GetMapping("/jobs")
    public List<Map<String, Object>> activeJobs(@RequestHeader(value = "X-Telegram-Init-Data", required = false) String auth) {
        return jobs.active(user(auth)).stream().map(MiniAppController::jobView).toList();
    }

    // ------------------------------------------------------------------ analysis

    private record Input(byte[] bytes, String contentType, String view) {
    }

    private record Plan(long user, List<Input> inputs, String mode, String kind, String title, String beforeId) {
    }

    /** Analyzes all photos in parallel, then stores them in their order; returns the inspection id. */
    private String execute(Plan plan, Runnable photoDone) throws InterruptedException {
        List<Future<DamageAssessment>> futures = new ArrayList<>();
        for (Input in : plan.inputs()) {
            futures.add(photoPool.submit(() -> {
                try {
                    return analyzeOne(in, plan.mode());
                } finally {
                    photoDone.run();
                }
            }));
        }
        List<DamageAssessment> results = new ArrayList<>();
        for (Future<DamageAssessment> f : futures) {
            try {
                results.add(f.get());
            } catch (ExecutionException e) {
                results.add(DamageAssessment.error("internal error: " + e.getCause().getMessage(), 0));
            }
        }
        InspectionStore.Inspection inspection = store.create(plan.user(), now(), plan.mode(), plan.kind(),
                plan.title(), plan.beforeId());
        for (int i = 0; i < results.size(); i++) {
            Input in = plan.inputs().get(i);
            String ext = "image/png".equals(in.contentType()) ? "png" : "jpg";
            store.addPhoto(inspection.id(), i, in.view(), in.bytes(), ext, results.get(i));
        }
        store.trim(plan.user(), keepPerUser);
        return inspection.id();
    }

    private DamageAssessment analyzeOne(Input in, String mode) {
        try {
            return switch (mode) {
                case "tiled" -> tiledAnalyzer.analyze(in.bytes(), in.contentType(), PROFILE);
                case "relook" -> relookAnalyzer.analyze(in.bytes(), in.contentType(), PROFILE);
                default -> analyzer.analyze(in.bytes(), in.contentType(), PROFILE);
            };
        } catch (IllegalArgumentException e) {
            return DamageAssessment.error("not a readable JPEG or PNG", 0);
        }
    }

    private static Map<String, Object> jobView(AnalysisJobs.Job job) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("job_id", job.id());
        m.put("status", job.state().name().toLowerCase(Locale.ROOT));
        m.put("done", job.done());
        m.put("total", job.total());
        m.put("created_at", job.createdAt());
        if (job.inspectionId() != null) {
            m.put("inspection_id", job.inspectionId());
        }
        if (job.error() != null) {
            m.put("error_message", job.error());
        }
        return m;
    }

    /** If the user closed the page before the end, the bot tells him in the chat that the result is ready. */
    private void notifyIfLeft(AnalysisJobs.Job job) {
        if (telegram == null || now() - job.lastSeenAt() < LEFT_AFTER_SECONDS) {
            return;
        }
        String text;
        if (job.state() == AnalysisJobs.State.DONE) {
            int damages = InspectionSummary.allDamages(store.photos(job.inspectionId())).size();
            text = "Анализ готов: " + job.total() + " фото, отмечено повреждений: " + damages
                    + ". Осмотр сохранён в «Мои осмотры».";
        } else {
            text = "Анализ фото не удался: " + job.error() + ". Попробуйте ещё раз.";
        }
        try {
            if (miniAppUrl != null && job.state() == AnalysisJobs.State.DONE) {
                telegram.sendWebAppButton(job.userId(), text, "Открыть осмотр", miniAppUrl);
            } else {
                telegram.sendMessage(job.userId(), text);
            }
        } catch (Exception ignored) {
            // the result is stored anyway; the notification is only a convenience
        }
    }

    @GetMapping("/inspections")
    public List<Map<String, Object>> list(@RequestHeader(value = "X-Telegram-Init-Data", required = false) String auth) {
        long user = user(auth);
        List<Map<String, Object>> out = new ArrayList<>();
        List<InspectionStore.Inspection> all = store.list(user);
        for (InspectionStore.Inspection i : all) {
            List<InspectionStore.Photo> photos = store.photos(i.id());
            InspectionSummary s = summary(photos);
            Map<String, Object> m = header(i);
            m.put("photos", photos.size());
            m.put("damages", s.damages().size());
            m.put("total", total(s.total(), s.currency()));
            m.put("has_return", all.stream().anyMatch(o -> i.id().equals(o.beforeId())));
            out.add(m);
        }
        return out;
    }

    @GetMapping("/inspections/{id}")
    public Map<String, Object> get(@RequestHeader(value = "X-Telegram-Init-Data", required = false) String auth,
                                   @PathVariable String id) {
        return view(owned(id, user(auth)));
    }

    @GetMapping("/inspections/{id}/photos/{index}")
    public ResponseEntity<byte[]> photo(@RequestHeader(value = "X-Telegram-Init-Data", required = false) String auth,
                                        @PathVariable String id, @PathVariable int index) {
        owned(id, user(auth));
        byte[] bytes = store.photoBytes(id, index).orElseThrow(() -> new NotFound("no such photo"));
        boolean png = bytes.length > 3 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P';
        return ResponseEntity.ok().contentType(png ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG).body(bytes);
    }

    /**
     * The user's correction of one photo: the full list of damages as they should be.
     * Damages can be removed or their type, part, severity and action changed; the
     * boxes and confidences come from the model and must stay valid.
     */
    @PutMapping("/inspections/{id}/photos/{index}/damages")
    public Map<String, Object> correct(@RequestHeader(value = "X-Telegram-Init-Data", required = false) String auth,
                                       @PathVariable String id, @PathVariable int index,
                                       @RequestBody List<Damage> damages) {
        long user = user(auth);
        InspectionStore.Inspection inspection = owned(id, user);
        if (store.photos(id).stream().noneMatch(p -> p.index() == index)) {
            throw new NotFound("no such photo");
        }
        List<String> problems = validator.validate(DamageAssessment.success(damages, 0, 1));
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("invalid correction: " + String.join("; ", problems));
        }
        store.correct(id, index, user, now(), damages);
        return view(inspection);
    }

    @DeleteMapping("/inspections/{id}")
    public Map<String, Object> delete(@RequestHeader(value = "X-Telegram-Init-Data", required = false) String auth,
                                      @PathVariable String id) {
        owned(id, user(auth));
        store.delete(id);
        return Map.of("status", "deleted");
    }

    @GetMapping("/inspections/{id}/report.pdf")
    public ResponseEntity<byte[]> pdf(@RequestHeader(value = "X-Telegram-Init-Data", required = false) String auth,
                                      @PathVariable String id) {
        byte[] pdf = reports.pdf(report(owned(id, user(auth))));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "attachment; filename=\"" + fileName(id) + "\"").body(pdf);
    }

    @PostMapping("/inspections/{id}/report")
    public ResponseEntity<Map<String, Object>> sendReport(
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String auth, @PathVariable String id) {
        long user = user(auth);
        InspectionStore.Inspection inspection = owned(id, user);
        byte[] pdf = reports.pdf(report(inspection));
        try {
            telegram.sendDocument(user, fileName(id), pdf, "Отчёт об осмотре автомобиля (демо)");
        } catch (Exception e) {
            return error(HttpStatus.BAD_GATEWAY, "Could not send the report to the chat: " + e.getMessage());
        }
        return ResponseEntity.ok(Map.of("status", "sent"));
    }

    // ------------------------------------------------------------------ building the answers

    private Map<String, Object> view(InspectionStore.Inspection i) {
        List<InspectionStore.Photo> photos = store.photos(i.id());
        Map<String, Object> m = header(i);
        List<BeforeAfterComparator.Finding> findings = comparison(i, photos);

        List<Map<String, Object>> photoList = new ArrayList<>();
        for (InspectionStore.Photo p : photos) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("index", p.index());
            pm.put("view", p.view());
            pm.put("view_name", Views.name(p.view()));
            pm.put("status", p.status());
            if (p.error() != null) {
                pm.put("error_message", p.error());
            }
            if (p.vehicleVisible() != null) {
                pm.put("vehicle_visible", p.vehicleVisible());
            }
            pm.put("edited", p.edited());
            pm.put("damages", p.damages());
            if (findings != null) {
                pm.put("comparison", findings.stream().filter(f -> f.afterPhotoIndex() == p.index())
                        .map(f -> f.status().name().toLowerCase(Locale.ROOT)).toList());
            }
            photoList.add(pm);
        }
        m.put("photos", photoList);

        InspectionSummary s = summary(photos);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("damages", s.damages());
        summary.put("prices", s.prices().stream().map(r -> r == null ? null : Map.of("min", r.min(), "max", r.max())).toList());
        summary.put("total", total(s.total(), s.currency()));
        m.put("summary", summary);

        if (findings != null) {
            List<Damage> fresh = findings.stream().filter(f -> f.status() == BeforeAfterComparator.Status.NEW)
                    .map(BeforeAfterComparator.Finding::damage).toList();
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("before_id", i.beforeId());
            c.put("new", fresh.size());
            c.put("not_compared", findings.stream().filter(f -> f.status() == BeforeAfterComparator.Status.NOT_COMPARED).count());
            c.put("new_total", total(prices.total(fresh), prices.currency()));
            m.put("comparison", c);
        }
        return m;
    }

    private List<BeforeAfterComparator.Finding> comparison(InspectionStore.Inspection i, List<InspectionStore.Photo> photos) {
        if (!"after".equals(i.kind()) || i.beforeId() == null) {
            return null;
        }
        return BeforeAfterComparator.compare(store.photos(i.beforeId()), photos);
    }

    private Map<String, Object> header(InspectionStore.Inspection i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id());
        m.put("created_at", i.createdAt());
        m.put("kind", i.kind());
        m.put("mode", i.mode());
        m.put("title", i.title());
        m.put("before_id", i.beforeId());
        return m;
    }

    private InspectionSummary summary(List<InspectionStore.Photo> photos) {
        return InspectionSummary.of(InspectionSummary.allDamages(photos), mergeService, prices);
    }

    private static Map<String, Object> total(PriceEstimator.Range r, String currency) {
        return Map.of("min", r.min(), "max", r.max(), "currency", currency);
    }

    private ReportRenderer.Report report(InspectionStore.Inspection i) {
        List<InspectionStore.Photo> photos = store.photos(i.id());
        List<BeforeAfterComparator.Finding> findings = comparison(i, photos);
        InspectionSummary s = summary(photos);
        List<ReportRenderer.PhotoPart> parts = new ArrayList<>();
        for (InspectionStore.Photo p : photos) {
            List<Boolean> flags = findings == null ? null : findings.stream()
                    .filter(f -> f.afterPhotoIndex() == p.index())
                    .map(f -> f.status() == BeforeAfterComparator.Status.NEW).toList();
            String title = "Фото " + (p.index() + 1) + (p.view() == null ? "" : " · " + Views.name(p.view()))
                    + ("success".equals(p.status()) ? "" : " · не проанализировано");
            parts.add(new ReportRenderer.PhotoPart(title, store.photoBytes(i.id(), p.index()).orElse(new byte[0]),
                    "success".equals(p.status()) ? p.damages() : List.of(), flags));
        }
        String kind = switch (i.kind()) {
            case "before" -> "при выдаче";
            case "after" -> "при возврате";
            default -> "осмотр";
        };
        String when = DATE.format(Instant.ofEpochSecond(i.createdAt()).atZone(ZoneId.systemDefault()));
        String subtitle = when + " · " + kind + (i.title() == null ? "" : " · " + i.title());
        Integer fresh = null;
        PriceEstimator.Range freshTotal = null;
        if (findings != null) {
            List<Damage> d = findings.stream().filter(f -> f.status() == BeforeAfterComparator.Status.NEW)
                    .map(BeforeAfterComparator.Finding::damage).toList();
            fresh = d.size();
            freshTotal = prices.total(d);
        }
        return new ReportRenderer.Report("Отчёт об осмотре автомобиля", subtitle, s.damages(), s.prices(),
                s.total(), s.currency(), fresh, freshTotal, parts);
    }

    private static String fileName(String id) {
        return "car-inspection-" + id.substring(0, 8) + ".pdf";
    }

    // ------------------------------------------------------------------ access

    private long user(String auth) {
        if (initData == null) {
            throw new Disabled();
        }
        return initData.validate(auth, now());
    }

    private InspectionStore.Inspection owned(String id, long user) {
        if (id == null) {
            throw new NotFound("inspection not found");
        }
        InspectionStore.Inspection i = store.find(id).orElseThrow(() -> new NotFound("inspection not found"));
        if (i.userId() != user) {
            throw new NotFound("inspection not found");   // same answer as missing: do not reveal other users' ids
        }
        return i;
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    static class NotFound extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotFound(String message) {
            super(message);
        }
    }

    static class Disabled extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    @ExceptionHandler(Disabled.class)
    public ResponseEntity<Map<String, Object>> disabled() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "Mini App is off: the service has no TELEGRAM_BOT_TOKEN");
    }

    @ExceptionHandler(NotFound.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFound e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
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

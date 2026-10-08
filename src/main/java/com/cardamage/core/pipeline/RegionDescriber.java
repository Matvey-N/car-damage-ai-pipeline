package com.cardamage.core.pipeline;

import com.cardamage.core.model.Damage;
import com.cardamage.core.model.DamageAssessment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Hybrid scheme, second stage: a detector has already found the regions; the
 * model only describes them.
 *
 * The regions are drawn on the image as numbered rectangles and also listed
 * by coordinates in the prompt. For every region the model returns the damage
 * type, part, severity, action and its confidence, or damage_type "none" if
 * there is no damage there (so the model can also reject a false detection).
 * The boxes stay the detector's: the model is not asked to localize.
 *
 * Validation, retry with the previous violations and the error fallback work
 * as in SingleImageAnalyzer.
 */
public class RegionDescriber {

    public static final String PROMPT_VERSION = "r1";
    public static final int MAX_REGIONS = 30;

    private static final String PROMPT = """
            You are inspecting a photo of a car. An automatic detector has marked regions that may
            contain damage. They are drawn on the image as red numbered rectangles and listed below
            as [x, y, w, h], fractions of the image size (x, y = top-left corner).

            %s

            For EVERY region, decide what it shows and return ONLY one JSON object, no prose, no markdown:

            {"regions": [{"region": 1, "damage_type": ..., "part": ..., "severity": ..., "action": ..., "confidence": ...}]}

            - damage_type: "glass_shatter" (any breakage of window or windshield glass), "lamp_broken"
              (broken or cracked headlight or tail light), "crack" (crack in a body panel, bumper or other
              non-glass part), "scratch" (scratch or scrape in paint or surface), or "none" if the region
              shows no damage.
            - part: "bumper" | "door" | "light" | "window" | "windshield" | "hood" | "fender" | "mirror" |
              "wheel" | "other". "light" = headlight or tail light; "fender" includes quarter panels;
              "bumper" includes the front and rear panels.
            - severity: minor = small scratch or scuff (under about 5 cm), or a short crack that does not
              spread; moderate = long or deep scratch with paint damage, or a crack in a part still in one
              piece; severe = shattered or broken glass, a broken light, or a crack that threatens the part.
            - action: repair for scratches and cracks in a part that is not broken; replacement for broken
              glass, broken lights and heavily damaged parts.
            - confidence: your probability (0 to 1) that the region shows damage of the stated type.
            - For damage_type "none", set part, severity and action to null.
            - Return exactly one entry per region number, nothing for areas outside the regions.
            """;

    private static final Set<String> DAMAGE_TYPES_OR_NONE;

    static {
        Set<String> s = new HashSet<>(ResponseFormatValidator.DAMAGE_TYPES);
        s.add("none");
        DAMAGE_TYPES_OR_NONE = Set.copyOf(s);
    }

    private final VisionModelClient client;
    private final ObjectMapper mapper;
    private final int maxAttempts;
    private final ImagePreprocessor preprocessor;

    public RegionDescriber(VisionModelClient client, ObjectMapper mapper, int maxAttempts, ImagePreprocessor preprocessor) {
        this.client = client;
        this.mapper = mapper;
        this.maxAttempts = maxAttempts;
        this.preprocessor = preprocessor;
    }

    public DamageAssessment describe(byte[] image, String mediaType, List<List<Double>> regions) {
        if (image == null || image.length == 0) {
            throw new IllegalArgumentException("image is empty");
        }
        if (mediaType == null || !SingleImageAnalyzer.SUPPORTED_MEDIA_TYPES.contains(mediaType)) {
            throw new IllegalArgumentException("unsupported media type: " + mediaType);
        }
        checkRegions(regions);
        if (regions.isEmpty()) {
            return DamageAssessment.success(new ArrayList<>(), 0, 0);
        }

        PreparedImage prepared = preprocessor.prepare(drawRegions(image, regions), "image/png");
        String basePrompt = String.format(PROMPT, listRegions(regions));
        List<String> failureLog = new ArrayList<>();
        List<String> lastViolations = List.of();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String prompt = lastViolations.isEmpty() ? basePrompt
                    : basePrompt + "\nYour previous answer was rejected for these reasons: "
                    + String.join("; ", lastViolations) + "\nReturn a corrected JSON object only.\n";
            try {
                String raw = client.analyze(prepared.bytes(), prepared.mediaType(), prompt);
                List<String> violations = new ArrayList<>();
                List<Damage> damages = parse(raw, regions, violations);
                if (violations.isEmpty()) {
                    return DamageAssessment.success(damages, 0, attempt);
                }
                lastViolations = violations;
                failureLog.add("attempt " + attempt + ": " + String.join("; ", violations));
            } catch (ModelCallException e) {
                lastViolations = List.of();
                failureLog.add("attempt " + attempt + ": model call failed: " + e.getMessage());
            }
        }
        return DamageAssessment.error("No valid answer after " + maxAttempts + " attempts. "
                + String.join(" | ", failureLog), maxAttempts);
    }

    static void checkRegions(List<List<Double>> regions) {
        if (regions == null) {
            throw new IllegalArgumentException("regions are missing");
        }
        if (regions.size() > MAX_REGIONS) {
            throw new IllegalArgumentException("at most " + MAX_REGIONS + " regions, got " + regions.size());
        }
        for (List<Double> r : regions) {
            if (r == null || r.size() != 4 || r.stream().anyMatch(v -> v == null || v < 0 || v > 1)
                    || r.get(2) <= 0 || r.get(3) <= 0) {
                throw new IllegalArgumentException("each region must be [x, y, w, h] with values in [0, 1], got " + r);
            }
        }
    }

    /** Parses and checks the answer; problems go to violations. Region boxes are taken from the detector. */
    List<Damage> parse(String raw, List<List<Double>> regions, List<String> violations) {
        JsonNode root;
        try {
            root = mapper.readTree(ResponseParser.stripMarkdownFence(raw == null ? "" : raw.trim()));
        } catch (IOException e) {
            violations.add("answer is not valid JSON");
            return List.of();
        }
        JsonNode list = root == null ? null : root.get("regions");
        if (list == null || !list.isArray()) {
            violations.add("'regions' array is missing");
            return List.of();
        }
        List<Damage> damages = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (JsonNode e : list) {
            int n = e.path("region").asInt(-1);
            if (n < 1 || n > regions.size()) {
                violations.add("unknown region number " + e.path("region"));
                continue;
            }
            if (!seen.add(n)) {
                violations.add("region " + n + " appears twice");
                continue;
            }
            String type = e.path("damage_type").asText(null);
            if (type == null || !DAMAGE_TYPES_OR_NONE.contains(type)) {
                violations.add("region " + n + ": unknown damage_type '" + type + "'");
                continue;
            }
            double conf = e.path("confidence").asDouble(-1);
            if (!e.path("confidence").isNumber() || conf < 0 || conf > 1) {
                violations.add("region " + n + ": confidence must be a number in [0, 1]");
                continue;
            }
            if (type.equals("none")) {
                continue;
            }
            String part = e.path("part").asText(null);
            String severity = e.path("severity").asText(null);
            String action = e.path("action").asText(null);
            if (part == null || !ResponseFormatValidator.PARTS.contains(part)
                    || severity == null || !ResponseFormatValidator.SEVERITIES.contains(severity)
                    || action == null || !ResponseFormatValidator.ACTIONS.contains(action)) {
                violations.add("region " + n + ": part, severity or action missing or not allowed");
                continue;
            }
            damages.add(new Damage(type, part, severity, action, conf, regions.get(n - 1)));
        }
        if (violations.isEmpty() && seen.size() != regions.size()) {
            violations.add("expected an entry for every region 1.." + regions.size() + ", got " + seen.size());
        }
        return damages;
    }

    static String listRegions(List<List<Double>> regions) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < regions.size(); i++) {
            List<Double> r = regions.get(i);
            sb.append(String.format(java.util.Locale.ROOT, "Region %d: [%.3f, %.3f, %.3f, %.3f]%n",
                    i + 1, r.get(0), r.get(1), r.get(2), r.get(3)));
        }
        return sb.toString().trim();
    }

    static byte[] drawRegions(byte[] bytes, List<List<Double>> regions) {
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new IllegalArgumentException("image could not be read: " + e.getMessage(), e);
        }
        if (source == null) {
            throw new IllegalArgumentException("image could not be read (not a valid JPEG or PNG)");
        }
        int w = source.getWidth();
        int h = source.getHeight();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.drawImage(source, 0, 0, null);
        float stroke = Math.max(2f, Math.max(w, h) / 500f);
        int fontSize = Math.max(14, Math.max(w, h) / 45);
        g.setStroke(new BasicStroke(stroke));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
        for (int i = 0; i < regions.size(); i++) {
            List<Double> r = regions.get(i);
            int x = (int) Math.round(r.get(0) * w);
            int y = (int) Math.round(r.get(1) * h);
            int rw = (int) Math.round(r.get(2) * w);
            int rh = (int) Math.round(r.get(3) * h);
            g.setColor(Color.RED);
            g.drawRect(x, y, rw, rh);
            String label = String.valueOf(i + 1);
            int ty = Math.max(fontSize, y - (int) stroke);
            g.setColor(Color.BLACK);
            g.drawString(label, x + 2, ty + 2);
            g.setColor(Color.YELLOW);
            g.drawString(label, x, ty);
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new IllegalStateException("image encoding failed", e);
        }
        return out.toByteArray();
    }
}

package com.cardamage.web;

import com.cardamage.core.demo.DamageMergeService;
import com.cardamage.core.model.DamageAssessment;
import com.cardamage.core.pipeline.DamagePrompt;
import com.cardamage.core.pipeline.RegionDescriber;
import com.cardamage.core.pipeline.SingleImageAnalyzer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cardamage.core.pipeline.TiledImageAnalyzer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
 * HTTP entry points.
 *
 *   POST /api/v1/analyze       - ONE image (main path, used by the benchmark);
 *                                ?mode=tiled also analyzes overlapping tiles (more calls)
 *   POST /api/v1/describe      - ONE image + regions found by a detector: the model only
 *                                describes them (hybrid scheme, research)
 *   POST /api/v1/demo/analyze  - 3-10 photos of one car (demo, secondary)
 *   GET  /api/v1/info          - which model / prompt version is active
 *
 * HTTP status: 200 for status=success, 502 for status=error (model gave
 * no valid answer after all attempts), 400 for bad input. The body is
 * always a DamageAssessment JSON object.
 */
@RestController
@RequestMapping("/api/v1")
public class AnalysisController {

    private static final int DEMO_MIN_IMAGES = 3;
    private static final int DEMO_MAX_IMAGES = 10;

    private final SingleImageAnalyzer analyzer;
    private final TiledImageAnalyzer tiledAnalyzer;
    private final RegionDescriber regionDescriber;
    private final ObjectMapper objectMapper;
    private final DamageMergeService mergeService;
    private final String clientType;
    private final String model;
    private final int maxAttempts;

    public AnalysisController(SingleImageAnalyzer analyzer,
                              TiledImageAnalyzer tiledAnalyzer,
                              RegionDescriber regionDescriber,
                              ObjectMapper objectMapper,
                              DamageMergeService mergeService,
                              @Value("${pipeline.model-client}") String clientType,
                              @Value("${anthropic.model}") String model,
                              @Value("${pipeline.max-attempts}") int maxAttempts) {
        this.analyzer = analyzer;
        this.tiledAnalyzer = tiledAnalyzer;
        this.regionDescriber = regionDescriber;
        this.objectMapper = objectMapper;
        this.mergeService = mergeService;
        this.clientType = clientType;
        this.model = model;
        this.maxAttempts = maxAttempts;
    }

    @PostMapping("/analyze")
    public ResponseEntity<DamageAssessment> analyzeSingle(@RequestParam("image") MultipartFile image,
                                                          @RequestParam(value = "mode", defaultValue = "whole") String mode)
            throws IOException {
        return switch (mode) {
            case "whole" -> respond(analyzer.analyze(image.getBytes(), image.getContentType()));
            case "tiled" -> respond(tiledAnalyzer.analyze(image.getBytes(), image.getContentType()));
            default -> throw new IllegalArgumentException("unknown mode '" + mode + "' (expected 'whole' or 'tiled')");
        };
    }

    /** regions: JSON array of [x, y, w, h] boxes normalized to [0, 1], e.g. [[0.1,0.2,0.05,0.04]]. */
    @PostMapping("/describe")
    public ResponseEntity<DamageAssessment> describeRegions(@RequestParam("image") MultipartFile image,
                                                            @RequestParam("regions") String regions)
            throws IOException {
        List<List<Double>> boxes;
        try {
            boxes = objectMapper.readValue(regions, new TypeReference<List<List<Double>>>() { });
        } catch (IOException e) {
            throw new IllegalArgumentException("regions must be a JSON array of [x, y, w, h] boxes");
        }
        return respond(regionDescriber.describe(image.getBytes(), image.getContentType(), boxes));
    }

    @PostMapping("/demo/analyze")
    public ResponseEntity<DamageAssessment> analyzeDemo(@RequestParam("images") MultipartFile[] images)
            throws IOException {
        if (images.length < DEMO_MIN_IMAGES || images.length > DEMO_MAX_IMAGES) {
            throw new IllegalArgumentException("demo scenario expects " + DEMO_MIN_IMAGES + "-"
                    + DEMO_MAX_IMAGES + " images, got " + images.length);
        }
        List<DamageAssessment> perPhoto = new ArrayList<>();
        for (MultipartFile image : images) {
            perPhoto.add(analyzer.analyze(image.getBytes(), image.getContentType()));
        }
        return respond(mergeService.merge(perPhoto));
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("model_client", clientType);
        info.put("model", "stub".equals(clientType) ? "stub (no model is called)" : model);
        info.put("prompt_version", DamagePrompt.VERSION);
        info.put("max_attempts", maxAttempts);
        info.put("tiled_mode", tiledAnalyzer.describe());
        info.put("region_prompt_version", RegionDescriber.PROMPT_VERSION);
        return info;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<DamageAssessment> badInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(DamageAssessment.error(e.getMessage(), 0));
    }

    private static ResponseEntity<DamageAssessment> respond(DamageAssessment result) {
        return ResponseEntity.status(result.isSuccess() ? HttpStatus.OK : HttpStatus.BAD_GATEWAY).body(result);
    }
}

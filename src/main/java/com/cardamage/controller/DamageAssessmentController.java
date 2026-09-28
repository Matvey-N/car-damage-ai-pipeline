package com.cardamage.controller;

import com.cardamage.exception.ValidationException;
import com.cardamage.model.AssessmentEntity;
import com.cardamage.model.DamageAssessment;
import com.cardamage.repository.AssessmentRepository;
import com.cardamage.service.DamageAnalysisService;
import com.cardamage.service.DamageMergeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * DEMO scenario only: 3-10 photos of one car, uploaded by an end user
 * (normally via the Telegram bot, but exposed here as a plain REST
 * endpoint so the bot itself can stay a thin client). This is explicitly
 * NOT the benchmark path - see TZ section 12. The benchmark runner
 * (single CarDD image at a time, IoU matching against ground truth) is a
 * separate offline script, not a REST endpoint, since it isn't a
 * user-facing feature.
 */
@RestController
@RequestMapping("/api/v1/assessment")
public class DamageAssessmentController {

    private static final int MIN_IMAGES = 3;
    private static final int MAX_IMAGES = 10;
    private static final long MAX_IMAGE_BYTES = 5_000_000L; // 5 MB per image

    private final DamageAnalysisService damageAnalysisService;
    private final DamageMergeService damageMergeService;
    private final AssessmentRepository assessmentRepository;
    private final ObjectMapper objectMapper;

    public DamageAssessmentController(DamageAnalysisService damageAnalysisService,
                                       DamageMergeService damageMergeService,
                                       AssessmentRepository assessmentRepository,
                                       ObjectMapper objectMapper) {
        this.damageAnalysisService = damageAnalysisService;
        this.damageMergeService = damageMergeService;
        this.assessmentRepository = assessmentRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/analyze")
    public ResponseEntity<DamageAssessment> analyze(
            @RequestParam("images") MultipartFile[] images,
            @RequestParam(value = "make", required = false) String make,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "year", required = false) Integer year) {

        if (images.length < MIN_IMAGES || images.length > MAX_IMAGES) {
            throw new ValidationException(
                    "Please upload between " + MIN_IMAGES + " and " + MAX_IMAGES + " images (demo scenario only)");
        }

        List<DamageAssessment> perImageResults = new ArrayList<>();
        for (MultipartFile image : images) {
            validateImage(image);
            try {
                String base64 = Base64.getEncoder().encodeToString(image.getBytes());
                perImageResults.add(damageAnalysisService.analyzeImage(base64, image.getContentType()));
            } catch (Exception e) {
                perImageResults.add(DamageAssessment.error("Failed to read/analyze one of the uploaded images: " + e.getMessage()));
            }
        }

        DamageAssessment merged = damageMergeService.merge(perImageResults);

        persistSession(merged, make, model, year, images.length);

        return ResponseEntity.ok(merged);
    }

    private void validateImage(MultipartFile file) {
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new ValidationException("Image too large (max 5MB): " + file.getOriginalFilename());
        }
        String contentType = file.getContentType();
        if (!"image/jpeg".equals(contentType) && !"image/png".equals(contentType)) {
            throw new ValidationException("Only JPEG and PNG are supported: " + file.getOriginalFilename());
        }
    }

    private void persistSession(DamageAssessment result, String make, String model, Integer year, int imageCount) {
        try {
            AssessmentEntity entity = new AssessmentEntity();
            entity.setSessionId(UUID.randomUUID().toString());
            entity.setVehicleMake(make);
            entity.setVehicleModel(model);
            entity.setVehicleYear(year);
            entity.setImageCount(imageCount);
            entity.setResultJson(objectMapper.writeValueAsString(result));
            entity.setCreatedAt(Instant.now());
            assessmentRepository.save(entity);
        } catch (Exception e) {
            // Persistence failure should not break the response to the user.
            // Logged rather than swallowed silently in the real implementation.
        }
    }
}

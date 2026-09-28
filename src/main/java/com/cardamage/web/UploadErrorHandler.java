package com.cardamage.web;

import com.cardamage.core.model.DamageAssessment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** Upload problems happen before the controller is reached, so they are handled here. */
@RestControllerAdvice
public class UploadErrorHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<DamageAssessment> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.badRequest().body(DamageAssessment.error("image too large (max 5MB per image)", 0));
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<DamageAssessment> badUpload(Exception e) {
        return ResponseEntity.badRequest().body(DamageAssessment.error("bad upload: " + e.getMessage(), 0));
    }
}

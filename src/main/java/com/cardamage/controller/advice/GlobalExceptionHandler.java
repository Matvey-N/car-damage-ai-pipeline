package com.cardamage.controller.advice;

import com.cardamage.exception.ValidationException;
import com.cardamage.model.DamageAssessment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<DamageAssessment> handleValidation(ValidationException e) {
        return ResponseEntity.badRequest().body(DamageAssessment.error(e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<DamageAssessment> handleUnexpected(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(DamageAssessment.error("Internal server error: " + e.getMessage()));
    }
}

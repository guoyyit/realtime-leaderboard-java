package com.github.guoyyit.realtimeleaderboardjava.web;

import com.github.guoyyit.realtimeleaderboardjava.service.RateLimitedException;
import com.github.guoyyit.realtimeleaderboardjava.web.dto.ErrorView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(annotations = RestController.class)
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorView> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorView("INVALID_ARGUMENT", ex.getMessage()));
    }

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ErrorView> tooManyRequests(RateLimitedException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ErrorView("RATE_LIMITED", ex.getMessage()));
    }
}

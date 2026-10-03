package com.github.guoyyit.realtimeleaderboardjava.service;

/**
 * 触发限流时抛出。
 */
public class RateLimitedException extends RuntimeException {
    public RateLimitedException(String message) {
        super(message);
    }
}

package com.github.guoyyit.realtimeleaderboardjava.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简单的每用户令牌桶限流器，用于拒绝超高频提交。
 * 限流只影响请求是否被接受，不会触碰榜单数据，因此被拒绝不会破坏榜单完整性。
 */
public class RateLimiter {

    private final double permitsPerSecond;
    private final int burstCapacity;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(double permitsPerSecond, int burstCapacity) {
        this.permitsPerSecond = permitsPerSecond;
        this.burstCapacity = burstCapacity;
    }

    /** 尝试为 key 获取一个许可，失败表示超限。 */
    public boolean tryAcquire(String key) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(burstCapacity));
        synchronized (bucket) {
            bucket.refill(permitsPerSecond, burstCapacity);
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos = System.nanoTime();

        private Bucket(double tokens) {
            this.tokens = tokens;
        }

        private void refill(double permitsPerSecond, int burstCapacity) {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            tokens = Math.min(burstCapacity, tokens + elapsedSeconds * permitsPerSecond);
            lastRefillNanos = now;
        }
    }
}

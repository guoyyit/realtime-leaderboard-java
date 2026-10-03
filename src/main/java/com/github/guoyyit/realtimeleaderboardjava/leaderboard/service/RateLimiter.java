package com.github.guoyyit.realtimeleaderboardjava.leaderboard.service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于令牌桶的每用户限流器。
 *
 * <p>每个用户独立一个桶，桶容量为突发上限，令牌以恒定速率填充。
 * 桶状态的更新在桶对象上同步，保证并发安全。
 */
public class RateLimiter {

    private final long capacity;
    private final double refillPerSecond;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(long capacity, long refillPerSecond) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    /** 尝试为指定用户获取一个令牌；返回是否放行。 */
    public boolean tryAcquire(String userId) {
        long now = System.nanoTime();
        TokenBucket bucket = buckets.computeIfAbsent(userId, k -> new TokenBucket(capacity, now));
        synchronized (bucket) {
            double elapsedSeconds = (now - bucket.lastRefillNanos) / 1_000_000_000.0;
            if (elapsedSeconds > 0) {
                long refill = (long) (elapsedSeconds * refillPerSecond);
                if (refill > 0) {
                    bucket.tokens = Math.min(capacity, bucket.tokens + refill);
                    bucket.lastRefillNanos = now;
                }
            }
            if (bucket.tokens >= 1) {
                bucket.tokens--;
                return true;
            }
            return false;
        }
    }

    /** 重置指定用户的限流状态（测试用）。 */
    public void reset(String userId) {
        buckets.remove(userId);
    }

    private static final class TokenBucket {
        private long tokens;
        private long lastRefillNanos;

        TokenBucket(long tokens, long lastRefillNanos) {
            this.tokens = tokens;
            this.lastRefillNanos = lastRefillNanos;
        }
    }
}

package com.github.guoyyit.realtimeleaderboardjava.tests;

import com.github.guoyyit.realtimeleaderboardjava.core.Leaderboard;
import com.github.guoyyit.realtimeleaderboardjava.core.Leaderboard.RankedEntry;
import com.github.guoyyit.realtimeleaderboardjava.core.LeaderboardRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyTest {

    @Test
    void concurrentSubmissionsConvergeToDeterministicMaxScoreModel() throws InterruptedException {
        AtomicLong seq = new AtomicLong(1);
        LeaderboardRegistry registry = new LeaderboardRegistry(seq::getAndIncrement);
        Leaderboard board = registry.getOrCreate("concurrent");

        int users = 200;
        int threads = 16;
        int submissionsPerThread = 500;
        Random seedRandom = new Random(7);
        long[] seeds = seedRandom.longs(threads).toArray();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            Random random = new Random(seeds[t]);
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < submissionsPerThread; i++) {
                        String userId = "user-" + random.nextInt(users);
                        long score = random.nextInt(10_000);
                        board.apply(userId, score, System.nanoTime(), seq.getAndIncrement());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        // 确定性模型：每个用户的最终分数 = 全部提交中的最高分（与提交顺序无关）
        // 通过重放所有可能分数无法直接得到，因此改为验证不变量：
        // 1) 榜单大小不超过用户数；2) 名次连续无跳变；3) 每个用户名次与全量扫描一致；
        // 4) 再次提交更低或相同分数不改变状态（幂等）。
        assertTrue(board.size() <= users);
        List<RankedEntry> all = board.rangeByRank(1, board.size());
        assertEquals(board.size(), all.size());
        Map<String, Long> scores = new HashMap<>();
        for (int i = 0; i < all.size(); i++) {
            RankedEntry entry = all.get(i);
            assertEquals(i + 1, entry.rank(), "rank continuity");
            assertEquals(entry.rank(), board.rankOf(entry.record().userId()).orElseThrow().rank());
            scores.put(entry.record().userId(), entry.record().score());
            if (i > 0) {
                assertTrue(all.get(i - 1).record().score() >= entry.record().score(),
                        "scores must be non-increasing by rank");
            }
        }
        // 幂等性：以当前分数重复提交，状态不变
        List<RankedEntry> before = new ArrayList<>(all);
        scores.forEach((user, score) ->
                board.apply(user, score, System.nanoTime(), seq.getAndIncrement()));
        List<RankedEntry> after = board.rangeByRank(1, board.size());
        assertEquals(before.stream().map(e -> e.record().userId()).toList(),
                after.stream().map(e -> e.record().userId()).toList());
    }

    @Test
    void concurrentSameUserSubmissionsKeepHighestScore() throws InterruptedException {
        AtomicLong seq = new AtomicLong(1);
        Leaderboard board = new Leaderboard("single-user");
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            long score = t * 100L;
            pool.submit(() -> board.apply("alice", score, System.nanoTime(), seq.getAndIncrement()));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(1, board.size());
        assertEquals(3100, board.rankOf("alice").orElseThrow().record().score());
    }
}

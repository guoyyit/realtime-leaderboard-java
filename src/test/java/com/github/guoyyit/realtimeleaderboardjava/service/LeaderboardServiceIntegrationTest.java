package com.github.guoyyit.realtimeleaderboardjava.service;

import com.github.guoyyit.realtimeleaderboardjava.leaderboard.model.PageResult;
import com.github.guoyyit.realtimeleaderboardjava.leaderboard.model.ScoreEntry;
import com.github.guoyyit.realtimeleaderboardjava.leaderboard.model.SubmitResult;
import com.github.guoyyit.realtimeleaderboardjava.leaderboard.model.SubmitStatus;
import com.github.guoyyit.realtimeleaderboardjava.leaderboard.service.LeaderboardConfig;
import com.github.guoyyit.realtimeleaderboardjava.leaderboard.service.LeaderboardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排行榜核心服务集成测试。
 */
class LeaderboardServiceIntegrationTest {

    @TempDir
    Path tempDir;

    private LeaderboardService service;

    @BeforeEach
    void setUp() {
        service = createService(c -> {
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
    }

    private LeaderboardService createService(Consumer<LeaderboardConfig> configurer) {
        LeaderboardConfig config = new LeaderboardConfig();
        config.setDataDir(tempDir);
        configurer.accept(config);
        return new LeaderboardService(config);
    }

    // ==================== 并发提交 ====================

    @Test
    void concurrentSubmissions_sameUser_deterministic() throws Exception {
        // 排除限流干扰，专注验证并发提交的确定性
        service = createService(c -> {
            c.setRateLimitCapacity(10000);
            c.setRateLimitPerSecond(10000);
        });
        int threads = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicReference<Throwable> error = new AtomicReference<>();
        for (int i = 0; i < threads; i++) {
            final int seq = i + 1;
            final long score = seq * 10L;
            executor.submit(() -> {
                try {
                    service.submit("b1", "u1", score, seq);
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();
        if (error.get() != null) {
            throw new AssertionError("concurrent submit failed", error.get());
        }
        // 最终分数 = seq 最大者（100）的分数 = 1000
        ScoreEntry entry = service.userRank("b1", "u1");
        assertEquals(1000L, entry.score());
        assertEquals(100L, entry.seq());
    }

    @Test
    void concurrentSubmissions_differentUsers_rankingCorrect() throws Exception {
        // 排除限流干扰
        service = createService(c -> {
            c.setRateLimitCapacity(10000);
            c.setRateLimitPerSecond(10000);
        });
        int threads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    service.submit("b1", "u" + idx, idx * 10L, 1);
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();
        PageResult result = service.range("b1", 1, 50);
        assertEquals(50, result.totalUsers());
        assertEquals(50, result.entries().size());
        // 验证排名：分数从高到低，名次连续
        for (int i = 0; i < result.entries().size() - 1; i++) {
            assertTrue(result.entries().get(i).score() > result.entries().get(i + 1).score());
            assertEquals(i + 1, result.entries().get(i).rank());
        }
    }

    // ==================== 重复提交与乱序到达 ====================

    @Test
    void duplicateSubmission_sameSeq_ignored() {
        service.submit("b1", "u1", 100, 1);
        SubmitResult result = service.submit("b1", "u1", 200, 1);
        assertEquals(SubmitStatus.DUPLICATE_IGNORED, result.status());
        assertEquals(100L, result.score());
        assertEquals(1, result.seq());
    }

    @Test
    void outOfOrderArrival_lowerSeq_ignored() {
        service.submit("b1", "u1", 200, 2);
        SubmitResult result = service.submit("b1", "u1", 100, 1);
        assertEquals(SubmitStatus.OUT_OF_ORDER_IGNORED, result.status());
        assertEquals(200L, result.score());
        assertEquals(2, result.seq());
    }

    @Test
    void sequentialSubmissions_higherSeqWins() {
        service.submit("b1", "u1", 100, 1);
        service.submit("b1", "u1", 200, 2);
        SubmitResult result = service.submit("b1", "u1", 300, 3);
        assertEquals(SubmitStatus.ACCEPTED, result.status());
        assertEquals(300L, result.score());
        assertEquals(3, result.seq());
    }

    // ==================== 同分并列 ====================

    @Test
    void ties_denseRanking() {
        service.submit("b1", "a", 100, 1);
        service.submit("b1", "b", 90, 1);
        service.submit("b1", "c", 90, 1);
        service.submit("b1", "d", 80, 1);

        assertEquals(1, service.userRank("b1", "a").rank());
        assertEquals(2, service.userRank("b1", "b").rank());
        assertEquals(2, service.userRank("b1", "c").rank());
        assertEquals(3, service.userRank("b1", "d").rank());
    }

    @Test
    void ties_sameScoreUsersOrderedByUserId() {
        service.submit("b1", "charlie", 90, 1);
        service.submit("b1", "alice", 90, 1);
        service.submit("b1", "bob", 90, 1);

        PageResult result = service.range("b1", 1, 10);
        assertEquals(3, result.entries().size());
        assertEquals("alice", result.entries().get(0).userId());
        assertEquals("bob", result.entries().get(1).userId());
        assertEquals("charlie", result.entries().get(2).userId());
    }

    // ==================== 多榜单隔离 ====================

    @Test
    void multipleBoards_isolated() {
        service.submit("b1", "u1", 100, 1);
        service.submit("b2", "u1", 200, 1);

        assertEquals(100L, service.userRank("b1", "u1").score());
        assertEquals(200L, service.userRank("b2", "u1").score());
        assertEquals(1, service.userRank("b1", "u1").rank());
        assertEquals(1, service.userRank("b2", "u1").rank());

        // b1 只有 u1，b2 只有 u1，互不干扰
        assertEquals(1, service.range("b1", 1, 10).totalUsers());
        assertEquals(1, service.range("b2", 1, 10).totalUsers());
    }

    @Test
    void updateOneBoard_doesNotAffectOther() {
        service.submit("b1", "u1", 100, 1);
        service.submit("b2", "u1", 200, 1);

        // 更新 b1 中 u1 的分数，b2 不受影响
        service.submit("b1", "u1", 300, 2);
        assertEquals(300L, service.userRank("b1", "u1").score());
        assertEquals(200L, service.userRank("b2", "u1").score());
    }

    // ==================== 分页边界 ====================

    @Test
    void paginationBoundaries() {
        // 空榜单
        PageResult empty = service.range("b1", 1, 10);
        assertEquals(0, empty.entries().size());
        assertEquals(0, empty.totalUsers());

        for (int i = 1; i <= 5; i++) {
            service.submit("b1", "u" + i, i * 10, 1);
        }

        // start < 1 钳制到 1
        PageResult r1 = service.range("b1", -5, 2);
        assertEquals(1, r1.startRank());
        assertEquals(2, r1.endRank());
        assertEquals(2, r1.entries().size());

        // end > totalRanks 钳制到 totalRanks
        PageResult r2 = service.range("b1", 4, 100);
        assertEquals(4, r2.startRank());
        assertEquals(5, r2.endRank());
        assertEquals(2, r2.entries().size());

        // start > end 返回空
        PageResult r3 = service.range("b1", 10, 20);
        assertEquals(0, r3.entries().size());

        // 用户不存在
        assertNull(service.userRank("b1", "nonexistent"));

        // neighbors 用户不存在
        assertEquals(0, service.neighbors("b1", "nonexistent", 3).size());

        // 不存在的榜单
        assertNull(service.userRank("nonexistent", "u1"));
        assertEquals(0, service.range("nonexistent", 1, 10).entries().size());
    }

    @Test
    void neighbors_returnsNearbyUsers() {
        for (int i = 1; i <= 10; i++) {
            service.submit("b1", "u" + i, i * 10, 1);
        }
        // u5 分数 50，名次 6（分数从高到低：u10=100 第1, u9=90 第2, ..., u5=50 第6）
        var neighbors = service.neighbors("b1", "u5", 2);
        // 名次在 [4, 8] 之间的用户：u7(70,第4), u6(60,第5), u5(50,第6), u4(40,第7), u3(30,第8)
        assertEquals(5, neighbors.size());
        assertEquals("u7", neighbors.get(0).userId());
        assertEquals("u6", neighbors.get(1).userId());
        assertEquals("u5", neighbors.get(2).userId());
        assertEquals("u4", neighbors.get(3).userId());
        assertEquals("u3", neighbors.get(4).userId());
    }

    // ==================== 同分分页 ====================

    @Test
    void ties_rangePagination_returnsAllTiedUsers() {
        service.submit("b1", "a", 100, 1);
        service.submit("b1", "b", 90, 1);
        service.submit("b1", "c", 90, 1);
        service.submit("b1", "d", 80, 1);

        // 名次 1..2 包含 100 分（1人）和 90 分（2人），共 3 人
        PageResult result = service.range("b1", 1, 2);
        assertEquals(3, result.entries().size());
        assertEquals("a", result.entries().get(0).userId());
        assertEquals("b", result.entries().get(1).userId());
        assertEquals("c", result.entries().get(2).userId());
        // b 和 c 同名次
        assertEquals(result.entries().get(1).rank(), result.entries().get(2).rank());
        assertEquals(2, result.entries().get(1).rank());
    }

    @Test
    void ties_neighbors_keepsTiedUsersTogether() {
        service.submit("b1", "a", 100, 1);
        service.submit("b1", "b", 90, 1);
        service.submit("b1", "c", 90, 1);
        service.submit("b1", "d", 80, 1);

        // 查询 b 的附近（range=0，即同名次用户）
        var neighbors = service.neighbors("b1", "b", 0);
        assertEquals(2, neighbors.size());
        assertEquals("b", neighbors.get(0).userId());
        assertEquals("c", neighbors.get(1).userId());
    }

    // ==================== 性能验证 ====================

    @Test
    void largeBoard_queryTimeControlled() {
        // 排除限流干扰
        service = createService(c -> {
            c.setRateLimitCapacity(100000);
            c.setRateLimitPerSecond(100000);
        });

        int n = 10000;
        for (int i = 0; i < n; i++) {
            service.submit("b1", "u" + i, i, 1);
        }

        // 名次查询：O(log n)
        long start = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            service.userRank("b1", "u" + (i * 7));
        }
        long rankTime = System.currentTimeMillis() - start;

        // 区间查询：O(log n + k)
        start = System.currentTimeMillis();
        PageResult rangeResult = service.range("b1", 1, 100);
        long rangeTime = System.currentTimeMillis() - start;

        assertEquals(100, rangeResult.entries().size());
        assertEquals(n, rangeResult.totalUsers());

        System.out.println("[PERF] rank query 1000 times on " + n + " users: " + rankTime + "ms");
        System.out.println("[PERF] range query 100 ranks on " + n + " users: " + rangeTime + "ms");
        // 验证查询耗时可控（O(log n) 名次查询应远快于全量重排）
        assertTrue(rankTime < 2000, "rank query too slow: " + rankTime + "ms");
        assertTrue(rangeTime < 500, "range query too slow: " + rangeTime + "ms");
    }

    // ==================== 重启恢复 ====================

    @Test
    void restartRecovery_stateConsistent() throws Exception {
        service.submit("b1", "u1", 100, 1);
        service.submit("b1", "u2", 90, 1);
        service.submit("b2", "u1", 200, 1);
        service.close();

        // 模拟重启：创建新实例
        LeaderboardService restored = createService(c -> {
        });
        try {
            assertEquals(100L, restored.userRank("b1", "u1").score());
            assertEquals(90L, restored.userRank("b1", "u2").score());
            assertEquals(200L, restored.userRank("b2", "u1").score());
            assertEquals(1, restored.userRank("b1", "u1").rank());
            assertEquals(2, restored.userRank("b1", "u2").rank());
            assertEquals(1, restored.userRank("b2", "u1").rank());
            assertEquals(2, restored.range("b1", 1, 10).totalUsers());
            assertEquals(1, restored.range("b2", 1, 10).totalUsers());
        } finally {
            restored.close();
        }
    }

    @Test
    void restartRecovery_withSnapshot_stateConsistent() throws Exception {
        service = createService(c -> c.setSnapshotEvery(5));
        for (int i = 1; i <= 20; i++) {
            service.submit("b1", "u" + i, i * 10, 1);
        }
        service.close();

        LeaderboardService restored = createService(c -> c.setSnapshotEvery(5));
        try {
            PageResult result = restored.range("b1", 1, 100);
            assertEquals(20, result.totalUsers());
            assertEquals(20, result.entries().size());
            // 验证排名正确
            assertEquals(1, restored.userRank("b1", "u20").rank());
            assertEquals(2, restored.userRank("b1", "u19").rank());
            assertEquals(20, restored.userRank("b1", "u1").rank());
        } finally {
            restored.close();
        }
    }

    @Test
    void restartRecovery_duplicateSubmissions_idempotent() throws Exception {
        // 重复提交相同 seq，重启后状态不变
        service.submit("b1", "u1", 100, 1);
        service.submit("b1", "u1", 200, 1); // 相同 seq，忽略
        service.close();

        LeaderboardService restored = createService(c -> {
        });
        try {
            assertEquals(100L, restored.userRank("b1", "u1").score());
            assertEquals(1, restored.userRank("b1", "u1").seq());
        } finally {
            restored.close();
        }
    }

    // ==================== 限流 ====================

    @Test
    void rateLimiting_burstRejected() {
        service = createService(c -> {
            c.setRateLimitCapacity(3);
            c.setRateLimitPerSecond(1);
        });

        int accepted = 0;
        int rejected = 0;
        for (int i = 0; i < 10; i++) {
            SubmitResult result = service.submit("b1", "u1", 100 + i, i + 1);
            if (result.status() == SubmitStatus.ACCEPTED) {
                accepted++;
            }
            if (result.status() == SubmitStatus.RATE_LIMITED) {
                rejected++;
            }
        }
        assertEquals(3, accepted);
        assertTrue(rejected > 0);
    }

    // ==================== 非法输入 ====================

    @Test
    void invalidInput_rejectedAndStateIntact() {
        assertThrows(IllegalArgumentException.class, () -> service.submit("", "u1", 100, 1));
        assertThrows(IllegalArgumentException.class, () -> service.submit("b1", "", 100, 1));
        assertThrows(IllegalArgumentException.class, () -> service.submit("b1", "u1", -1, 1));
        assertThrows(IllegalArgumentException.class, () -> service.submit("b1", "u1", 100, 0));
        assertThrows(IllegalArgumentException.class, () -> service.submit("b1", "u1", 100, -5));

        // 榜单未被破坏
        assertNull(service.userRank("b1", "u1"));
        assertEquals(0, service.range("b1", 1, 10).totalUsers());
    }

    @Test
    void invalidInput_doesNotCorruptExistingData() {
        service.submit("b1", "u1", 100, 1);
        assertThrows(IllegalArgumentException.class, () -> service.submit("b1", "u1", -1, 2));
        // 已有数据未被破坏
        assertEquals(100L, service.userRank("b1", "u1").score());
        assertEquals(1, service.userRank("b1", "u1").seq());
    }
}

package com.github.guoyyit.realtimeleaderboardjava.tests;

import com.github.guoyyit.realtimeleaderboardjava.config.LeaderboardProperties;
import com.github.guoyyit.realtimeleaderboardjava.core.LeaderboardRegistry;
import com.github.guoyyit.realtimeleaderboardjava.persistence.WalStore;
import com.github.guoyyit.realtimeleaderboardjava.service.LeaderboardService;
import com.github.guoyyit.realtimeleaderboardjava.service.RateLimitedException;
import com.github.guoyyit.realtimeleaderboardjava.service.RateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationAndRateLimitTest {

    @TempDir
    Path walDir;

    private LeaderboardService newService(WalStore wal, LeaderboardRegistry registry,
                                          RateLimiter limiter, long maxScore) {
        LeaderboardProperties props = new LeaderboardProperties(
                walDir.toString(), maxScore, 10, 5, 200, 50);
        return new LeaderboardService(registry, wal, limiter, props);
    }

    @Test
    void invalidInputsAreRejectedAndLeaveNoPartialState() {
        WalStore wal = new WalStore(walDir);
        try {
            LeaderboardRegistry registry = new LeaderboardRegistry(new AtomicLong(1)::getAndIncrement);
            LeaderboardService service = newService(wal, registry, new RateLimiter(1000, 1000), 1000);

            assertThrows(IllegalArgumentException.class, () -> service.submit("b", "alice", -1));
            assertThrows(IllegalArgumentException.class, () -> service.submit("b", "alice", 1001));
            assertThrows(IllegalArgumentException.class, () -> service.submit("b", null, 10));
            assertThrows(IllegalArgumentException.class, () -> service.submit("b", "bad user!", 10));
            assertThrows(IllegalArgumentException.class, () -> service.submit("bad/board", "alice", 10));
            assertThrows(IllegalArgumentException.class, () -> service.rangeByRank("b", 0, 10));
            assertThrows(IllegalArgumentException.class, () -> service.rangeByRank("b", 1, 0));
            assertThrows(IllegalArgumentException.class, () -> service.rangeByRank("b", 1, 201));
            assertThrows(IllegalArgumentException.class, () -> service.neighborhoodOf("b", "alice", -1));

            // 所有非法提交均未留下任何状态
            assertTrue(registry.boardIds().isEmpty());
            assertTrue(wal.readAll().isEmpty());
        } finally {
            wal.close();
        }
    }

    @Test
    void excessiveSubmissionsAreRateLimitedWithoutCorruptingBoard() {
        WalStore wal = new WalStore(walDir);
        try {
            LeaderboardRegistry registry = new LeaderboardRegistry(new AtomicLong(1)::getAndIncrement);
            // 每秒 0.001 个许可、突发 3：前 3 次放行，之后拒绝
            LeaderboardService service = newService(wal, registry, new RateLimiter(0.001, 3), 1000);

            service.submit("b", "alice", 100);
            service.submit("b", "alice", 200);
            service.submit("b", "alice", 300);
            assertThrows(RateLimitedException.class, () -> service.submit("b", "alice", 400));
            assertThrows(RateLimitedException.class, () -> service.submit("b", "alice", 500));

            // 被拒绝的提交未生效，榜单保持最后一次合法状态
            assertEquals(300, service.neighborhoodOf("b", "alice", 0).orElseThrow()
                    .get(0).record().score());
            assertEquals(3, wal.readAll().size());
            // 限流按用户隔离：其它用户不受影响
            service.submit("b", "bob", 50);
            assertEquals(50, service.neighborhoodOf("b", "bob", 0).orElseThrow()
                    .get(0).record().score());
        } finally {
            wal.close();
        }
    }
}

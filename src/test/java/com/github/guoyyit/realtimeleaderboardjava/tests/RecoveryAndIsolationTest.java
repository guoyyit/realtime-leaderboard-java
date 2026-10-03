package com.github.guoyyit.realtimeleaderboardjava.tests;

import com.github.guoyyit.realtimeleaderboardjava.config.LeaderboardProperties;
import com.github.guoyyit.realtimeleaderboardjava.core.Leaderboard.RankedEntry;
import com.github.guoyyit.realtimeleaderboardjava.core.LeaderboardRegistry;
import com.github.guoyyit.realtimeleaderboardjava.persistence.WalStore;
import com.github.guoyyit.realtimeleaderboardjava.service.LeaderboardService;
import com.github.guoyyit.realtimeleaderboardjava.service.RateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryAndIsolationTest {

    @TempDir
    Path walDir;

    private LeaderboardProperties properties() {
        return new LeaderboardProperties(walDir.toString(), 1_000_000_000L, 10_000, 100_000, 200, 50);
    }

    private LeaderboardService newService(WalStore walStore, LeaderboardRegistry registry) {
        return new LeaderboardService(registry, walStore,
                new RateLimiter(10_000, 100_000), properties());
    }

    private static List<String> snapshot(LeaderboardService service, String boardId) {
        return service.rangeByRank(boardId, 1, 200).stream()
                .map(e -> e.rank() + ":" + e.record().userId() + "=" + e.record().score())
                .toList();
    }

    @Test
    void restartRebuildsIdenticalRankingsFromWal() {
        List<String> before;
        List<String> beforeOther;
        WalStore wal = new WalStore(walDir);
        try {
            LeaderboardRegistry registry = new LeaderboardRegistry(new AtomicLong(1)::getAndIncrement);
            LeaderboardService service = newService(wal, registry);
            service.submit("weekly", "alice", 500);
            service.submit("weekly", "bob", 700);
            service.submit("weekly", "alice", 900);   // 提分
            service.submit("weekly", "alice", 100);   // 乱序低分，不生效
            service.submit("weekly", "carol", 900);   // 与 alice 同分，后达成
            service.submit("monthly", "alice", 50);   // 另一榜单
            before = snapshot(service, "weekly");
            beforeOther = snapshot(service, "monthly");
        } finally {
            wal.close();
        }

        // 模拟重启：新建 WalStore 与注册表，重放全部历史
        WalStore reopened = new WalStore(walDir);
        try {
            AtomicLong seq = new AtomicLong(1);
            LeaderboardRegistry rebuilt = new LeaderboardRegistry(seq::getAndIncrement);
            for (var entry : reopened.readAll()) {
                rebuilt.replay(entry.boardId(), entry.record());
            }
            rebuilt.nextSeqAfterReplay();
            LeaderboardService service = newService(reopened, rebuilt);
            assertEquals(before, snapshot(service, "weekly"));
            assertEquals(beforeOther, snapshot(service, "monthly"));
            // 同分 tie-break 语义恢复后仍成立：alice 先达成 900，名次靠前
            List<RankedEntry> top = service.rangeByRank("weekly", 1, 2);
            assertEquals("alice", top.get(0).record().userId());
            assertEquals("carol", top.get(1).record().userId());
        } finally {
            reopened.close();
        }
    }

    @Test
    void boardsAreIsolatedPerPeriod() {
        WalStore wal = new WalStore(walDir);
        try {
            LeaderboardRegistry registry = new LeaderboardRegistry(new AtomicLong(1)::getAndIncrement);
            LeaderboardService service = newService(wal, registry);
            service.submit("weekly", "alice", 100);
            service.submit("monthly", "alice", 900);
            service.submit("weekly", "bob", 200);

            // 同一用户在不同榜单成绩独立
            assertEquals(100, service.neighborhoodOf("weekly", "alice", 0).orElseThrow()
                    .get(0).record().score());
            assertEquals(900, service.neighborhoodOf("monthly", "alice", 0).orElseThrow()
                    .get(0).record().score());
            // weekly 的更新不影响 monthly
            service.submit("weekly", "carol", 1000);
            assertEquals(1, service.rangeByRank("monthly", 1, 10).size());
            assertEquals(3, service.rangeByRank("weekly", 1, 10).size());
            // 不存在的榜单返回确定性空结果
            assertTrue(service.rangeByRank("nonexistent", 1, 10).isEmpty());
            assertTrue(service.neighborhoodOf("nonexistent", "alice", 2).isEmpty());
        } finally {
            wal.close();
        }
    }
}

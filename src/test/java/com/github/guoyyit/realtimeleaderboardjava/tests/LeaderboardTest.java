package com.github.guoyyit.realtimeleaderboardjava.tests;

import com.github.guoyyit.realtimeleaderboardjava.core.Leaderboard;
import com.github.guoyyit.realtimeleaderboardjava.core.Leaderboard.RankedEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaderboardTest {

    private final AtomicLong seq = new AtomicLong(1);

    private Leaderboard newBoard() {
        return new Leaderboard("test");
    }

    @Test
    void higherScoreRanksFirst() {
        Leaderboard board = newBoard();
        board.apply("alice", 100, 1000, seq.getAndIncrement());
        board.apply("bob", 200, 1000, seq.getAndIncrement());
        assertEquals(1, board.rankOf("bob").orElseThrow().rank());
        assertEquals(2, board.rankOf("alice").orElseThrow().rank());
    }

    @Test
    void tiesAreBrokenByEarlierAchieveTimeThenSeq() {
        Leaderboard board = newBoard();
        // 同分：先达成者名次靠前
        board.apply("alice", 100, 2000, seq.getAndIncrement());
        board.apply("bob", 100, 1000, seq.getAndIncrement());
        assertEquals(1, board.rankOf("bob").orElseThrow().rank());
        assertEquals(2, board.rankOf("alice").orElseThrow().rank());

        // 同分同时间戳：序号小者靠前，排序全序确定
        Leaderboard board2 = newBoard();
        board2.apply("x", 50, 1000, 1);
        board2.apply("y", 50, 1000, 2);
        assertEquals(1, board2.rankOf("x").orElseThrow().rank());
        assertEquals(2, board2.rankOf("y").orElseThrow().rank());
    }

    @Test
    void duplicateAndOutOfOrderSubmissionsNeverLowerScore() {
        Leaderboard board = newBoard();
        board.apply("alice", 300, 3000, seq.getAndIncrement());
        // 乱序到达的旧分数不得覆盖
        board.apply("alice", 100, 1000, seq.getAndIncrement());
        // 重复提交不得累计
        board.apply("alice", 300, 4000, seq.getAndIncrement());
        board.apply("alice", 300, 4000, seq.getAndIncrement());
        assertEquals(300, board.rankOf("alice").orElseThrow().record().score());
        assertEquals(1, board.size());
    }

    @Test
    void scoreImprovementUpdatesRank() {
        Leaderboard board = newBoard();
        board.apply("alice", 100, 1000, seq.getAndIncrement());
        board.apply("bob", 200, 1000, seq.getAndIncrement());
        board.apply("alice", 300, 2000, seq.getAndIncrement());
        assertEquals(1, board.rankOf("alice").orElseThrow().rank());
        assertEquals(300, board.rankOf("alice").orElseThrow().record().score());
        assertEquals(2, board.rankOf("bob").orElseThrow().rank());
    }

    @Test
    void rangeClipsAndHandlesEmptyBoard() {
        Leaderboard board = newBoard();
        assertTrue(board.rangeByRank(1, 10).isEmpty());
        assertTrue(board.rankOf("nobody").isEmpty());
        assertTrue(board.neighborhood("nobody", 2).isEmpty());

        for (int i = 1; i <= 5; i++) {
            board.apply("u" + i, i * 100L, 1000, seq.getAndIncrement());
        }
        // 越界裁剪
        List<RankedEntry> tail = board.rangeByRank(4, 100);
        assertEquals(List.of(4, 5), tail.stream().map(RankedEntry::rank).toList());
        assertTrue(board.rangeByRank(6, 10).isEmpty());
        // 名次与排序一致：u5(500) 第一
        assertEquals("u5", board.rangeByRank(1, 1).get(0).record().userId());
    }

    @Test
    void neighborhoodIncludesSelfAndClipsAtBoundaries() {
        Leaderboard board = newBoard();
        for (int i = 1; i <= 5; i++) {
            board.apply("u" + i, i * 100L, 1000, seq.getAndIncrement());
        }
        // u3 排名第 3，前后各 1 名
        List<RankedEntry> around = board.neighborhood("u3", 1).orElseThrow();
        assertEquals(List.of(2, 3, 4), around.stream().map(RankedEntry::rank).toList());
        // 榜首用户：前方裁剪
        List<RankedEntry> top = board.neighborhood("u5", 2).orElseThrow();
        assertEquals(List.of(1, 2, 3), top.stream().map(RankedEntry::rank).toList());
    }

    @Test
    void ranksAreConsistentWithinAndAcrossQueries() {
        Leaderboard board = newBoard();
        for (int i = 1; i <= 100; i++) {
            board.apply("u" + i, i % 10L, 1000 + i, seq.getAndIncrement());
        }
        // 分页扫描全榜：名次连续、无重复、无跳变
        List<RankedEntry> all = board.rangeByRank(1, 100);
        for (int i = 0; i < all.size(); i++) {
            assertEquals(i + 1, all.get(i).rank());
            assertEquals(all.get(i).rank(),
                    board.rankOf(all.get(i).record().userId()).orElseThrow().rank());
        }
    }
}

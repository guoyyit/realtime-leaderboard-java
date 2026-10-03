package com.github.guoyyit.realtimeleaderboardjava.core;

import com.github.guoyyit.realtimeleaderboardjava.model.ScoreRecord;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 多榜单注册表：按 boardId 隔离维护多个 {@link Leaderboard}，互不干扰。
 * 序号分配器全局单调递增，保证同一榜单内排序全序；不同榜单之间互不影响。
 */
public class LeaderboardRegistry {

    private final ConcurrentHashMap<String, Leaderboard> boards = new ConcurrentHashMap<>();
    private final LongSupplier seqSupplier;
    private final AtomicLong maxReplayedSeq = new AtomicLong(0);

    public LeaderboardRegistry(LongSupplier seqSupplier) {
        this.seqSupplier = seqSupplier;
    }

    public Leaderboard getOrCreate(String boardId) {
        return boards.computeIfAbsent(boardId, Leaderboard::new);
    }

    public Optional<Leaderboard> get(String boardId) {
        return Optional.ofNullable(boards.get(boardId));
    }

    /**
     * 重放一条历史记录（重启恢复用），不分配新序号，沿用记录中持久化的序号，
     * 并推进本地序号水位，避免恢复后新分配的序号与历史冲突。
     */
    public void replay(String boardId, ScoreRecord record) {
        getOrCreate(boardId).apply(record.userId(), record.score(), record.updatedAtMs(), record.seq());
        maxReplayedSeq.accumulateAndGet(record.seq(), Math::max);
    }

    /** 恢复完成后返回新提交应使用的起始序号。 */
    public long nextSeqAfterReplay() {
        return maxReplayedSeq.get() + 1;
    }

    public long nextSeq() {
        return seqSupplier.getAsLong();
    }

    public List<String> boardIds() {
        return List.copyOf(boards.keySet());
    }
}

package com.github.guoyyit.realtimeleaderboardjava.service;

import com.github.guoyyit.realtimeleaderboardjava.config.LeaderboardProperties;
import com.github.guoyyit.realtimeleaderboardjava.core.Leaderboard;
import com.github.guoyyit.realtimeleaderboardjava.core.Leaderboard.RankedEntry;
import com.github.guoyyit.realtimeleaderboardjava.core.LeaderboardRegistry;
import com.github.guoyyit.realtimeleaderboardjava.model.ScoreRecord;
import com.github.guoyyit.realtimeleaderboardjava.persistence.WalStore;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 榜单应用服务：负责参数校验、限流、WAL 落盘与内存榜单更新的原子编排。
 * 编排顺序为：校验 -> 限流 -> WAL 落盘 -> 内存更新。
 * WAL 落盘失败时内存不产生任何变更；内存更新失败时 WAL 中的多余记录
 * 在重放时因"同用户取最高分"语义而幂等无害，因此不会留下部分更新或脏数据。
 */
public class LeaderboardService {

    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final LeaderboardRegistry registry;
    private final WalStore walStore;
    private final RateLimiter rateLimiter;
    private final LeaderboardProperties properties;

    public LeaderboardService(LeaderboardRegistry registry, WalStore walStore,
                              RateLimiter rateLimiter, LeaderboardProperties properties) {
        this.registry = registry;
        this.walStore = walStore;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /** 提交分数，返回更新后的名次条目。 */
    public RankedEntry submit(String boardId, String userId, long score) {
        validateId("boardId", boardId);
        validateId("userId", userId);
        if (score < 0 || score > properties.maxScore()) {
            throw new IllegalArgumentException(
                    "score must be between 0 and " + properties.maxScore() + ": " + score);
        }
        if (!rateLimiter.tryAcquire(userId)) {
            throw new RateLimitedException("submit rate exceeded for user: " + userId);
        }
        long seq = registry.nextSeq();
        ScoreRecord submitted = new ScoreRecord(userId, score, System.currentTimeMillis(), seq);
        walStore.append(boardId, submitted);
        Leaderboard board = registry.getOrCreate(boardId);
        board.apply(userId, score, submitted.updatedAtMs(), seq);
        return board.rankOf(userId).orElseThrow();
    }

    /** 按名次区间（1 开始）分页查询；榜单不存在或区间越界时返回确定性的空结果。 */
    public List<RankedEntry> rangeByRank(String boardId, int fromRank, int size) {
        validateId("boardId", boardId);
        if (fromRank < 1) {
            throw new IllegalArgumentException("fromRank must be >= 1: " + fromRank);
        }
        if (size < 1 || size > properties.maxPageSize()) {
            throw new IllegalArgumentException(
                    "size must be between 1 and " + properties.maxPageSize() + ": " + size);
        }
        Optional<Leaderboard> board = registry.get(boardId);
        if (board.isEmpty()) {
            return List.of();
        }
        return board.get().rangeByRank(fromRank, fromRank + size - 1);
    }

    /** 查询指定用户的名次及前后各 context 名邻居；用户或榜单不存在时返回 empty。 */
    public Optional<List<RankedEntry>> neighborhoodOf(String boardId, String userId, int context) {
        validateId("boardId", boardId);
        validateId("userId", userId);
        if (context < 0 || context > properties.maxContext()) {
            throw new IllegalArgumentException(
                    "context must be between 0 and " + properties.maxContext() + ": " + context);
        }
        return registry.get(boardId).flatMap(board -> board.neighborhood(userId, context));
    }

    private static void validateId(String field, String value) {
        if (value == null || !ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    field + " must match " + ID_PATTERN.pattern() + ": " + value);
        }
    }
}

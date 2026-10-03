package com.github.guoyyit.realtimeleaderboardjava.leaderboard.service;

import com.github.guoyyit.realtimeleaderboardjava.leaderboard.model.PageResult;
import com.github.guoyyit.realtimeleaderboardjava.leaderboard.model.ScoreEntry;
import com.github.guoyyit.realtimeleaderboardjava.leaderboard.model.SubmitResult;
import com.github.guoyyit.realtimeleaderboardjava.leaderboard.model.SubmitStatus;
import com.github.guoyyit.realtimeleaderboardjava.leaderboard.persistence.LeaderboardStore;
import com.github.guoyyit.realtimeleaderboardjava.leaderboard.skiplist.AugmentedScoreSkipList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 排行榜核心服务：分数写入、名次查询、分页与附近排名。
 *
 * <p><b>提交语义</b>：每次提交携带单调递增的 seq，服务端对同一用户同一榜单只保留 seq 最大的分数（LWW）。
 * 因此重复提交（相同 seq）被忽略，乱序到达（更小 seq）也被忽略，最终状态与到达顺序无关，并发下确定。
 *
 * <p><b>名次语义</b>：稠密名次（同分同名次，名次连续不跳变），同分内按 userId 升序排列。
 *
 * <p><b>并发模型</b>：每个榜单一把读写锁。写入路径为「校验 -> 限流 -> 追加 WAL -> 更新内存」，
 * WAL 先于内存更新，WAL 失败则内存不变，保证不留下部分更新。
 *
 * <p><b>恢复</b>：启动时加载快照并回放 WAL，重建结果与增量维护一致（seq LWW 保证幂等）。
 */
@Service
public class LeaderboardService {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardService.class);

    private final LeaderboardConfig config;
    private final RateLimiter rateLimiter;
    private final LeaderboardStore store;
    private final Map<String, Board> boards = new ConcurrentHashMap<>();

    public LeaderboardService(LeaderboardConfig config) {
        this.config = config;
        this.rateLimiter = new RateLimiter(config.getRateLimitCapacity(), config.getRateLimitPerSecond());
        try {
            this.store = new LeaderboardStore(config.getDataDir(), config.isWalFsync());
            restore();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to initialize leaderboard store", e);
        }
    }

    private void restore() throws IOException {
        Map<String, Map<String, LeaderboardStore.UserScoreState>> restored = store.load();
        for (Map.Entry<String, Map<String, LeaderboardStore.UserScoreState>> boardEntry : restored.entrySet()) {
            Board board = new Board(boardEntry.getKey());
            for (Map.Entry<String, LeaderboardStore.UserScoreState> userEntry : boardEntry.getValue().entrySet()) {
                LeaderboardStore.UserScoreState state = userEntry.getValue();
                board.skiplist.addUser(state.score(), userEntry.getKey());
                board.userScores.put(userEntry.getKey(), new UserScore(state.score(), state.seq()));
            }
            boards.put(board.boardId, board);
        }
    }

    /** 提交分数。 */
    public SubmitResult submit(String boardId, String userId, long score, long seq) {
        validate(boardId, userId, score, seq);
        if (!rateLimiter.tryAcquire(userId)) {
            return SubmitResult.of(SubmitStatus.RATE_LIMITED, userId, score, seq, 0);
        }

        Board board = boards.computeIfAbsent(boardId, Board::new);
        SubmitResult result;
        board.lock.writeLock().lock();
        try {
            UserScore existing = board.userScores.get(userId);
            if (existing != null && existing.seq >= seq) {
                SubmitStatus status = existing.seq == seq
                        ? SubmitStatus.DUPLICATE_IGNORED
                        : SubmitStatus.OUT_OF_ORDER_IGNORED;
                return SubmitResult.of(status, userId, existing.score, existing.seq, board.rankOf(userId));
            }

            try {
                store.append(new LeaderboardStore.WalEntry(boardId, userId, score, seq));
            } catch (IOException e) {
                throw new UncheckedIOException("failed to append WAL", e);
            }

            if (existing != null) {
                board.skiplist.removeUser(existing.score, userId);
            }
            board.skiplist.addUser(score, userId);
            board.userScores.put(userId, new UserScore(score, seq));
            result = SubmitResult.of(SubmitStatus.ACCEPTED, userId, score, seq, board.rankOf(userId));
        } finally {
            board.lock.writeLock().unlock();
        }

        if (store.walLinesSinceSnapshot() >= config.getSnapshotEvery()) {
            triggerSnapshot();
        }
        return result;
    }

    /** 按稠密名次区间分页查询（名次 1 起，闭区间）。 */
    public PageResult range(String boardId, long startRank, long endRank) {
        Board board = boards.get(boardId);
        if (board == null) {
            return new PageResult(List.of(), 0, 0, startRank, endRank);
        }
        board.lock.readLock().lock();
        try {
            long totalRanks = board.skiplist.distinctScoreCount();
            long totalUsers = board.skiplist.userCount();
            long clampedStart = Math.max(1, startRank);
            long clampedEnd = Math.min(totalRanks, endRank);
            if (totalRanks == 0 || clampedStart > clampedEnd) {
                return new PageResult(List.of(), totalUsers, totalRanks, clampedStart, clampedEnd);
            }
            List<ScoreEntry> entries = new ArrayList<>();
            for (Long score : board.skiplist.scoresByRank(clampedStart, clampedEnd)) {
                for (String userId : board.skiplist.usersAt(score)) {
                    UserScore us = board.userScores.get(userId);
                    entries.add(new ScoreEntry(userId, score, us.seq, board.skiplist.rankOfScore(score)));
                }
            }
            return new PageResult(entries, totalUsers, totalRanks, clampedStart, clampedEnd);
        } finally {
            board.lock.readLock().unlock();
        }
    }

    /** 查询指定用户的成绩与名次；用户不存在或榜单不存在时返回 null。 */
    public ScoreEntry userRank(String boardId, String userId) {
        Board board = boards.get(boardId);
        if (board == null) {
            return null;
        }
        board.lock.readLock().lock();
        try {
            UserScore us = board.userScores.get(userId);
            if (us == null) {
                return null;
            }
            return new ScoreEntry(userId, us.score, us.seq, board.skiplist.rankOfScore(us.score));
        } finally {
            board.lock.readLock().unlock();
        }
    }

    /** 查询指定用户附近的排名（稠密名次在 [rank-range, rank+range] 内的所有用户）。 */
    public List<ScoreEntry> neighbors(String boardId, String userId, long range) {
        Board board = boards.get(boardId);
        if (board == null || range < 0) {
            return List.of();
        }
        board.lock.readLock().lock();
        try {
            UserScore us = board.userScores.get(userId);
            if (us == null) {
                return List.of();
            }
            long rank = board.skiplist.rankOfScore(us.score);
            long start = Math.max(1, rank - range);
            long end = Math.min(board.skiplist.distinctScoreCount(), rank + range);
            List<ScoreEntry> entries = new ArrayList<>();
            for (Long score : board.skiplist.scoresByRank(start, end)) {
                for (String uid : board.skiplist.usersAt(score)) {
                    UserScore u = board.userScores.get(uid);
                    entries.add(new ScoreEntry(uid, score, u.seq, board.skiplist.rankOfScore(score)));
                }
            }
            return entries;
        } finally {
            board.lock.readLock().unlock();
        }
    }

    /** 列出所有榜单标识。 */
    public List<String> listBoards() {
        return List.copyOf(boards.keySet());
    }

    /** 关闭底层持久化资源（用于测试模拟重启）。 */
    public void close() throws IOException {
        store.close();
    }

    private void validate(String boardId, String userId, long score, long seq) {
        if (boardId == null || boardId.isBlank()) {
            throw new IllegalArgumentException("boardId must not be blank");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (score < config.getScoreMin() || score > config.getScoreMax()) {
            throw new IllegalArgumentException(
                    "score out of range [" + config.getScoreMin() + ", " + config.getScoreMax() + "]: " + score);
        }
        if (seq <= 0) {
            throw new IllegalArgumentException("seq must be positive: " + seq);
        }
    }

    /** 触发全量快照：按 boardId 排序获取所有读锁，保证快照一致性，且快照期间阻塞写入。 */
    private void triggerSnapshot() {
        List<Board> boardList = new ArrayList<>(boards.values());
        boardList.sort(Comparator.comparing(b -> b.boardId));
        boardList.forEach(b -> b.lock.readLock().lock());
        try {
            Map<String, Map<String, LeaderboardStore.UserScoreState>> data = new HashMap<>();
            for (Board board : boardList) {
                Map<String, LeaderboardStore.UserScoreState> users = new HashMap<>();
                for (Map.Entry<String, UserScore> entry : board.userScores.entrySet()) {
                    users.put(entry.getKey(),
                            new LeaderboardStore.UserScoreState(entry.getValue().score, entry.getValue().seq));
                }
                data.put(board.boardId, users);
            }
            store.snapshot(data);
        } catch (IOException e) {
            log.error("snapshot failed, will retry on next trigger", e);
        } finally {
            boardList.forEach(b -> b.lock.readLock().unlock());
        }
    }

    private record UserScore(long score, long seq) {
    }

    private static final class Board {
        final String boardId;
        final ConcurrentHashMap<String, UserScore> userScores = new ConcurrentHashMap<>();
        final AugmentedScoreSkipList skiplist = new AugmentedScoreSkipList();
        final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        Board(String boardId) {
            this.boardId = boardId;
        }

        long rankOf(String userId) {
            UserScore us = userScores.get(userId);
            return us == null ? 0 : skiplist.rankOfScore(us.score);
        }
    }
}

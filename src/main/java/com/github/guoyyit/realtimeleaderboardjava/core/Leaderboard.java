package com.github.guoyyit.realtimeleaderboardjava.core;

import com.github.guoyyit.realtimeleaderboardjava.model.ScoreRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 单个榜单：维护 userId -> 成绩 的映射以及按 (分数降序, 达成时间升序, 序号升序) 排序的名次树。
 * 名次从 1 开始；同分时先达成者名次靠前；序号保证排序为全序，名次稳定不跳变。
 * 所有 public 方法 synchronized，保证并发提交下状态一致、结果确定。
 */
public class Leaderboard {

    /** 名次比较器：分数降序 -> 达成时间升序 -> 服务端序号升序（全序，无并列歧义）。 */
    private static final Comparator<ScoreRecord> ORDER = Comparator
            .comparingLong(ScoreRecord::score).reversed()
            .thenComparingLong(ScoreRecord::updatedAtMs)
            .thenComparingLong(ScoreRecord::seq);

    private final String boardId;
    private final Map<String, ScoreRecord> scoresByUser = new HashMap<>();
    private final RankTree<ScoreRecord> rankTree = new RankTree<>(ORDER);

    public Leaderboard(String boardId) {
        this.boardId = boardId;
    }

    public String boardId() {
        return boardId;
    }

    public synchronized int size() {
        return scoresByUser.size();
    }

    /**
     * 应用一次分数提交。幂等且对乱序安全：仅当提交的分数高于该用户当前分数时更新；
     * 重复或更低的提交直接返回当前生效记录，不改变任何状态。
     *
     * @return 更新后的生效记录
     */
    public synchronized ScoreRecord apply(String userId, long score, long nowMs, long seq) {
        ScoreRecord current = scoresByUser.get(userId);
        if (current != null && score <= current.score()) {
            return current;
        }
        ScoreRecord next = new ScoreRecord(userId, score, nowMs, seq);
        if (current != null) {
            rankTree.remove(current);
        }
        rankTree.insert(next);
        scoresByUser.put(userId, next);
        return next;
    }

    /** 查询用户名次（从 1 开始），不存在返回 empty。 */
    public synchronized Optional<RankedEntry> rankOf(String userId) {
        ScoreRecord record = scoresByUser.get(userId);
        if (record == null) {
            return Optional.empty();
        }
        int rank = rankTree.rankOf(record).orElseThrow() + 1;
        return Optional.of(new RankedEntry(rank, record));
    }

    /** 按名次区间（1 开始，闭区间）查询，越界部分自动裁剪，空榜单返回空列表。 */
    public synchronized List<RankedEntry> rangeByRank(int fromRank, int toRank) {
        int from = Math.max(1, fromRank);
        int to = Math.min(rankTree.size(), toRank);
        List<RankedEntry> result = new ArrayList<>(Math.max(0, to - from + 1));
        for (int rank = from; rank <= to; rank++) {
            Optional<ScoreRecord> record = rankTree.kth(rank - 1);
            if (record.isPresent()) {
                result.add(new RankedEntry(rank, record.get()));
            }
        }
        return result;
    }

    /** 查询指定用户前后各 context 名的邻居（含自身），用户不存在返回 empty。 */
    public synchronized Optional<List<RankedEntry>> neighborhood(String userId, int context) {
        Optional<RankedEntry> self = rankOf(userId);
        if (self.isEmpty()) {
            return Optional.empty();
        }
        int rank = self.get().rank();
        return Optional.of(rangeByRank(rank - context, rank + context));
    }

    /** 带名次的榜单条目。 */
    public record RankedEntry(int rank, ScoreRecord record) {
    }
}

package com.github.guoyyit.realtimeleaderboardjava.leaderboard.skiplist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * 按分数降序排列的增强跳表（参考 Redis zskiplist 实现）。
 *
 * <p>每个节点代表一个不同的分数，节点内维护该分数下的用户集合（按 userId 升序）。
 * 通过维护每一层的 span（跨越的节点数），支持 O(log n) 的更新与名次查询。
 *
 * <p>名次语义为稠密名次：名次 = 分数严格大于当前分数的不同分数个数 + 1。
 * 即同分同名次，名次连续不跳变。
 */
public class AugmentedScoreSkipList {

    private static final int MAX_LEVEL = 32;
    private static final double P = 0.25;

    /** 跳表节点：同一分数的所有用户聚合在一个节点中。 */
    public static final class Node {
        final long score;
        final TreeSet<String> users = new TreeSet<>();
        Node[] forward;
        long[] span;

        Node(long score, int level) {
            this.score = score;
            this.forward = new Node[level];
            this.span = new long[level];
        }

        public long score() {
            return score;
        }

        public Set<String> users() {
            return Collections.unmodifiableSet(users);
        }
    }

    private final Node head = new Node(Long.MIN_VALUE, MAX_LEVEL);
    private int maxLevel = 1;
    private long distinctScoreCount;
    private long userCount;
    private final Random random = new Random();

    public AugmentedScoreSkipList() {
    }

    private int randomLevel() {
        int level = 1;
        while (random.nextDouble() < P && level < MAX_LEVEL) {
            level++;
        }
        return level;
    }

    private Node findNode(long score) {
        Node x = head;
        for (int i = maxLevel - 1; i >= 0; i--) {
            while (x.forward[i] != null && x.forward[i].score > score) {
                x = x.forward[i];
            }
        }
        Node candidate = x.forward[0];
        if (candidate != null && candidate.score == score) {
            return candidate;
        }
        return null;
    }

    /** 新增用户到指定分数；若该分数节点不存在则创建。返回该分数节点。 */
    public Node addUser(long score, String userId) {
        Node existing = findNode(score);
        if (existing != null) {
            if (existing.users.add(userId)) {
                userCount++;
            }
            return existing;
        }

        Node[] update = new Node[MAX_LEVEL];
        long[] rank = new long[MAX_LEVEL];
        Node x = head;
        for (int i = maxLevel - 1; i >= 0; i--) {
            rank[i] = (i == maxLevel - 1) ? 0 : rank[i + 1];
            while (x.forward[i] != null && x.forward[i].score > score) {
                rank[i] += x.span[i];
                x = x.forward[i];
            }
            update[i] = x;
        }

        int level = randomLevel();
        if (level > maxLevel) {
            for (int i = maxLevel; i < level; i++) {
                rank[i] = 0;
                update[i] = head;
                update[i].span[i] = distinctScoreCount;
            }
            maxLevel = level;
        }

        Node newNode = new Node(score, level);
        for (int i = 0; i < level; i++) {
            newNode.forward[i] = update[i].forward[i];
            update[i].forward[i] = newNode;
            newNode.span[i] = update[i].span[i] - (rank[0] - rank[i]);
            update[i].span[i] = (rank[0] - rank[i]) + 1;
        }
        for (int i = level; i < maxLevel; i++) {
            update[i].span[i]++;
        }

        distinctScoreCount++;
        userCount++;
        newNode.users.add(userId);
        return newNode;
    }

    /** 从指定分数移除用户；若节点变空则删除节点。返回是否移除成功。 */
    public boolean removeUser(long score, String userId) {
        Node target = findNode(score);
        if (target == null || !target.users.remove(userId)) {
            return false;
        }
        userCount--;
        if (!target.users.isEmpty()) {
            return true;
        }

        Node[] update = new Node[MAX_LEVEL];
        Node x = head;
        for (int i = maxLevel - 1; i >= 0; i--) {
            while (x.forward[i] != null && x.forward[i].score > score) {
                x = x.forward[i];
            }
            update[i] = x;
        }

        for (int i = 0; i < maxLevel; i++) {
            if (update[i].forward[i] == target) {
                update[i].span[i] += target.span[i] - 1;
                update[i].forward[i] = target.forward[i];
            } else {
                update[i].span[i]--;
            }
        }

        while (maxLevel > 1 && head.forward[maxLevel - 1] == null) {
            maxLevel--;
        }
        distinctScoreCount--;
        return true;
    }

    /** 指定分数的稠密名次（1 起）；分数不存在时返回 0。 */
    public long rankOfScore(long score) {
        long rank = 0;
        Node x = head;
        for (int i = maxLevel - 1; i >= 0; i--) {
            while (x.forward[i] != null && x.forward[i].score > score) {
                rank += x.span[i];
                x = x.forward[i];
            }
        }
        Node candidate = x.forward[0];
        if (candidate != null && candidate.score == score) {
            return rank + 1;
        }
        return 0;
    }

    /** 返回名次落在 [start, end] 范围内的分数（降序）。 */
    public List<Long> scoresByRank(long start, long end) {
        List<Long> result = new ArrayList<>();
        if (start > end || start < 1) {
            return result;
        }
        Node x = head;
        long rank = 0;
        for (int i = maxLevel - 1; i >= 0; i--) {
            while (x.forward[i] != null && rank + x.span[i] < start) {
                rank += x.span[i];
                x = x.forward[i];
            }
        }
        x = x.forward[0];
        long currentRank = start;
        while (x != null && currentRank <= end) {
            result.add(x.score);
            x = x.forward[0];
            currentRank++;
        }
        return result;
    }

    /** 不同分数的个数。 */
    public long distinctScoreCount() {
        return distinctScoreCount;
    }

    /** 总用户数。 */
    public long userCount() {
        return userCount;
    }

    /** 指定分数下的用户集合（不可变视图）；分数不存在时返回空集。 */
    public Set<String> usersAt(long score) {
        Node node = findNode(score);
        return node == null ? Set.of() : node.users();
    }
}

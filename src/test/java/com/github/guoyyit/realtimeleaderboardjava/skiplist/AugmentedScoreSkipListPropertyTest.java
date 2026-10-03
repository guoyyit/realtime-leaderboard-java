package com.github.guoyyit.realtimeleaderboardjava.skiplist;

import com.github.guoyyit.realtimeleaderboardjava.leaderboard.skiplist.AugmentedScoreSkipList;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 增强跳表与朴素 TreeMap 实现的对照属性测试。
 */
class AugmentedScoreSkipListPropertyTest {

    /** 朴素实现：TreeMap 降序分数 -> 用户集合。 */
    static class NaiveSkipList {
        final TreeMap<Long, TreeSet<String>> map = new TreeMap<>((a, b) -> Long.compare(b, a));

        void addUser(long score, String userId) {
            map.computeIfAbsent(score, s -> new TreeSet<>()).add(userId);
        }

        boolean removeUser(long score, String userId) {
            TreeSet<String> users = map.get(score);
            if (users == null || !users.remove(userId)) {
                return false;
            }
            if (users.isEmpty()) {
                map.remove(score);
            }
            return true;
        }

        long rankOfScore(long score) {
            if (!map.containsKey(score)) {
                return 0;
            }
            return map.headMap(score).size() + 1;
        }

        List<Long> scoresByRank(long start, long end) {
            List<Long> result = new ArrayList<>();
            if (start > end || start < 1) {
                return result;
            }
            long rank = 1;
            for (Long score : map.keySet()) {
                if (rank > end) {
                    break;
                }
                if (rank >= start) {
                    result.add(score);
                }
                rank++;
            }
            return result;
        }

        long distinctScoreCount() {
            return map.size();
        }

        long userCount() {
            return map.values().stream().mapToLong(Set::size).sum();
        }

        Set<String> usersAt(long score) {
            TreeSet<String> users = map.get(score);
            return users == null ? Set.of() : Set.copyOf(users);
        }
    }

    @RepeatedTest(20)
    void randomOperations_matchNaive() {
        long seed = new Random().nextLong();
        Random rnd = new Random(seed);
        AugmentedScoreSkipList skiplist = new AugmentedScoreSkipList();
        NaiveSkipList naive = new NaiveSkipList();

        for (int i = 0; i < 5000; i++) {
            long score = rnd.nextInt(50);
            String userId = "u" + rnd.nextInt(30);
            int op = rnd.nextInt(10);
            if (op < 6) {
                skiplist.addUser(score, userId);
                naive.addUser(score, userId);
            } else if (op < 8) {
                boolean r1 = skiplist.removeUser(score, userId);
                boolean r2 = naive.removeUser(score, userId);
                assertEquals(r2, r1, "remove mismatch at i=" + i + " seed=" + seed);
            } else if (op == 8) {
                long r1 = skiplist.rankOfScore(score);
                long r2 = naive.rankOfScore(score);
                assertEquals(r2, r1, "rank mismatch at i=" + i + " seed=" + seed);
            } else {
                long start = rnd.nextInt(60) - 5;
                long end = rnd.nextInt(60) - 5;
                List<Long> l1 = skiplist.scoresByRank(start, end);
                List<Long> l2 = naive.scoresByRank(start, end);
                assertEquals(l2, l1, "range mismatch at i=" + i + " seed=" + seed);
            }
        }

        assertEquals(naive.distinctScoreCount(), skiplist.distinctScoreCount(), "distinctScoreCount seed=" + seed);
        assertEquals(naive.userCount(), skiplist.userCount(), "userCount seed=" + seed);
        for (long s = 0; s < 50; s++) {
            assertEquals(naive.usersAt(s), skiplist.usersAt(s), "usersAt score=" + s + " seed=" + seed);
        }
    }

    @Test
    void emptyList_queriesReturnDefaults() {
        AugmentedScoreSkipList skiplist = new AugmentedScoreSkipList();
        assertEquals(0, skiplist.rankOfScore(100));
        assertEquals(List.of(), skiplist.scoresByRank(1, 10));
        assertEquals(0, skiplist.distinctScoreCount());
        assertEquals(0, skiplist.userCount());
        assertEquals(Set.of(), skiplist.usersAt(100));
    }

    @Test
    void ties_sameScoreShareRankAndUsersOrdered() {
        AugmentedScoreSkipList skiplist = new AugmentedScoreSkipList();
        skiplist.addUser(100, "charlie");
        skiplist.addUser(90, "alice");
        skiplist.addUser(90, "bob");
        skiplist.addUser(80, "dave");

        assertEquals(1, skiplist.rankOfScore(100));
        assertEquals(2, skiplist.rankOfScore(90));
        assertEquals(3, skiplist.rankOfScore(80));
        assertEquals(4, skiplist.userCount());
        assertEquals(3, skiplist.distinctScoreCount());
        // 同分用户按 userId 升序
        assertEquals(new TreeSet<>(Set.of("alice", "bob")), skiplist.usersAt(90));
        // 区间分页：名次 1..2 包含 100 分和 90 分（含两人）
        assertEquals(List.of(100L, 90L), skiplist.scoresByRank(1, 2));
    }
}

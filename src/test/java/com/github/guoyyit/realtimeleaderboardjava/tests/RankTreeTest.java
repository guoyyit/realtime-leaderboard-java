package com.github.guoyyit.realtimeleaderboardjava.tests;

import com.github.guoyyit.realtimeleaderboardjava.core.RankTree;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankTreeTest {

    @Test
    void emptyTreeHasNoRanks() {
        RankTree<Integer> tree = new RankTree<>(Integer::compareTo);
        assertEquals(0, tree.size());
        assertTrue(tree.rankOf(1).isEmpty());
        assertTrue(tree.kth(0).isEmpty());
        assertTrue(tree.range(0, 10).isEmpty());
    }

    @Test
    void matchesReferenceImplementationUnderRandomOperations() {
        Random random = new Random(42);
        RankTree<Integer> tree = new RankTree<>(Integer::compareTo);
        TreeSet<Integer> reference = new TreeSet<>();

        for (int op = 0; op < 20_000; op++) {
            int value = random.nextInt(500);
            if (random.nextBoolean()) {
                if (reference.add(value)) {
                    tree.insert(value);
                }
            } else {
                if (reference.remove(value)) {
                    tree.remove(value);
                }
            }
            if (op % 100 == 0) {
                assertEquals(reference.size(), tree.size());
                List<Integer> expected = new ArrayList<>(reference);
                for (int rank = 0; rank < expected.size(); rank++) {
                    int r = rank;
                    assertEquals(expected.get(rank), tree.kth(rank).orElseThrow(),
                            "kth(" + r + ")");
                    assertEquals(rank, tree.rankOf(expected.get(rank)).orElseThrow());
                }
                assertEquals(expected, tree.range(0, expected.size()));
            }
        }
    }

    @Test
    void rangeClipsOutOfBounds() {
        RankTree<Integer> tree = new RankTree<>(Integer::compareTo);
        for (int i = 0; i < 10; i++) {
            tree.insert(i);
        }
        assertEquals(List.of(0, 1, 2), tree.range(-5, 3));
        assertEquals(List.of(8, 9), tree.range(8, 100));
        assertEquals(List.of(), tree.range(10, 20));
    }
}

package com.github.guoyyit.realtimeleaderboardjava.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 有序统计树（size-augmented AVL）：支持 O(log n) 的插入、删除、按 key 求名次、按名次取元素。
 * key 必须按 comparator 构成全序且互不重复（由调用方保证）。
 * 非线程安全，由外层 {@link Leaderboard} 串行化访问。
 */
public class RankTree<T> {

    private final Comparator<T> comparator;
    private Node root;
    private int size;

    public RankTree(Comparator<T> comparator) {
        this.comparator = comparator;
    }

    public int size() {
        return size;
    }

    public boolean contains(T key) {
        Node node = root;
        while (node != null) {
            int cmp = comparator.compare(key, keyOf(node));
            if (cmp == 0) {
                return true;
            }
            node = cmp < 0 ? node.left : node.right;
        }
        return false;
    }

    public void insert(T key) {
        root = insert(root, key);
        size++;
    }

    public void remove(T key) {
        root = remove(root, key);
        size--;
    }

    /** 返回 key 的名次（从 0 开始），不存在时返回 empty。 */
    public Optional<Integer> rankOf(T key) {
        int rank = 0;
        Node node = root;
        while (node != null) {
            int cmp = comparator.compare(key, keyOf(node));
            if (cmp == 0) {
                return Optional.of(rank + sizeOf(node.left));
            }
            if (cmp < 0) {
                node = node.left;
            } else {
                rank += sizeOf(node.left) + 1;
                node = node.right;
            }
        }
        return Optional.empty();
    }

    /** 返回名次为 rank（从 0 开始）的元素，越界返回 empty。 */
    public Optional<T> kth(int rank) {
        if (rank < 0 || rank >= size) {
            return Optional.empty();
        }
        Node node = root;
        int remaining = rank;
        while (node != null) {
            int leftSize = sizeOf(node.left);
            if (remaining < leftSize) {
                node = node.left;
            } else if (remaining == leftSize) {
                return Optional.of(keyOf(node));
            } else {
                remaining -= leftSize + 1;
                node = node.right;
            }
        }
        return Optional.empty();
    }

    /** 返回 [fromInclusive, toExclusive) 名次区间内的元素，按排序顺序。 */
    public List<T> range(int fromInclusive, int toExclusive) {
        int from = Math.max(0, fromInclusive);
        int to = Math.min(size, toExclusive);
        List<T> result = new ArrayList<>(Math.max(0, to - from));
        for (int i = from; i < to; i++) {
            kth(i).ifPresent(result::add);
        }
        return result;
    }

    // ---- 内部实现 ----

    private static final class Node {
        private final Object key;
        private Node left;
        private Node right;
        private int height = 1;
        private int subtreeSize = 1;

        @SuppressWarnings("unchecked")
        Node(Object key) {
            this.key = key;
        }
    }

    @SuppressWarnings("unchecked")
    private T keyOf(Node node) {
        return (T) node.key;
    }

    private static int heightOf(Node node) {
        return node == null ? 0 : node.height;
    }

    private static int sizeOf(Node node) {
        return node == null ? 0 : node.subtreeSize;
    }

    private static void update(Node node) {
        node.height = 1 + Math.max(heightOf(node.left), heightOf(node.right));
        node.subtreeSize = 1 + sizeOf(node.left) + sizeOf(node.right);
    }

    private static int balanceFactor(Node node) {
        return heightOf(node.left) - heightOf(node.right);
    }

    private static Node rotateRight(Node y) {
        Node x = y.left;
        y.left = x.right;
        x.right = y;
        update(y);
        update(x);
        return x;
    }

    private static Node rotateLeft(Node x) {
        Node y = x.right;
        x.right = y.left;
        y.left = x;
        update(x);
        update(y);
        return y;
    }

    private static Node rebalance(Node node) {
        update(node);
        int balance = balanceFactor(node);
        if (balance > 1) {
            if (balanceFactor(node.left) < 0) {
                node.left = rotateLeft(node.left);
            }
            return rotateRight(node);
        }
        if (balance < -1) {
            if (balanceFactor(node.right) > 0) {
                node.right = rotateRight(node.right);
            }
            return rotateLeft(node);
        }
        return node;
    }

    private Node insert(Node node, T key) {
        if (node == null) {
            return new Node(key);
        }
        int cmp = comparator.compare(key, keyOf(node));
        if (cmp < 0) {
            node.left = insert(node.left, key);
        } else if (cmp > 0) {
            node.right = insert(node.right, key);
        } else {
            throw new IllegalArgumentException("duplicate key: " + key);
        }
        return rebalance(node);
    }

    private Node remove(Node node, T key) {
        if (node == null) {
            throw new IllegalArgumentException("key not found: " + key);
        }
        int cmp = comparator.compare(key, keyOf(node));
        if (cmp < 0) {
            node.left = remove(node.left, key);
        } else if (cmp > 0) {
            node.right = remove(node.right, key);
        } else {
            if (node.left == null) {
                return node.right;
            }
            if (node.right == null) {
                return node.left;
            }
            Node successor = node.right;
            while (successor.left != null) {
                successor = successor.left;
            }
            Node detached = remove(node.right, keyOf(successor));
            successor.left = node.left;
            successor.right = detached;
            node = successor;
        }
        return rebalance(node);
    }
}

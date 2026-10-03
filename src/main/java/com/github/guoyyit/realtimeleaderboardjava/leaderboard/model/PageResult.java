package com.github.guoyyit.realtimeleaderboardjava.leaderboard.model;

import java.util.List;

/**
 * 名次区间分页结果。
 *
 * @param entries   本页成绩记录（按名次升序，同分内按 userId 升序）
 * @param totalUsers 榜单总用户数
 * @param totalRanks 榜单总名次数（不同分数的个数）
 * @param startRank  实际生效的起始名次（越界时被钳制）
 * @param endRank    实际生效的结束名次（越界时被钳制）
 */
public record PageResult(List<ScoreEntry> entries,
                         long totalUsers,
                         long totalRanks,
                         long startRank,
                         long endRank) {
}

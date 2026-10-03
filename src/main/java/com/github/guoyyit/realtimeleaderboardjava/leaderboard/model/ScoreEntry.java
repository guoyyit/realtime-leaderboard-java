package com.github.guoyyit.realtimeleaderboardjava.leaderboard.model;

/**
 * 榜单中的一条用户成绩记录。
 *
 * @param userId 用户标识
 * @param score  分数
 * @param seq    该成绩对应的提交序号
 * @param rank   稠密名次（1 起），查询时计算
 */
public record ScoreEntry(String userId, long score, long seq, long rank) {
}

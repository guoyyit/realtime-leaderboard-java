package com.github.guoyyit.realtimeleaderboardjava.model;

/**
 * 一条榜单成绩记录。
 *
 * @param userId      用户标识
 * @param score       当前生效分数（同一用户取历史提交中的最大值）
 * @param updatedAtMs 该分数首次达成的时间戳（毫秒），用于同分 tie-break
 * @param seq         服务端分配的单调递增序号，作为最终 tie-break，保证排序全序
 */
public record ScoreRecord(String userId, long score, long updatedAtMs, long seq) {
}

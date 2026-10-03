package com.github.guoyyit.realtimeleaderboardjava.leaderboard.model;

/**
 * 分数提交结果。
 *
 * @param status 处理状态
 * @param userId 用户标识
 * @param score  提交后该用户在榜单中的最终分数（若被忽略则为已有分数）
 * @param seq    最终生效的提交序号
 * @param rank   提交后该用户的名次；若用户不在榜单中则为 0
 */
public record SubmitResult(SubmitStatus status, String userId, long score, long seq, long rank) {

    public static SubmitResult of(SubmitStatus status, String userId, long score, long seq, long rank) {
        return new SubmitResult(status, userId, score, seq, rank);
    }
}

package com.github.guoyyit.realtimeleaderboardjava.leaderboard.model;

/**
 * 分数提交的处理结果状态。
 */
public enum SubmitStatus {
    /** 提交被接受并应用到榜单 */
    ACCEPTED,
    /** 重复提交（相同 seq）被忽略，榜单未变化 */
    DUPLICATE_IGNORED,
    /** 乱序到达（更小 seq）被忽略，榜单未变化 */
    OUT_OF_ORDER_IGNORED,
    /** 被限流拒绝，榜单未变化 */
    RATE_LIMITED,
    /** 输入非法被拒绝，榜单未变化 */
    REJECTED_INVALID
}

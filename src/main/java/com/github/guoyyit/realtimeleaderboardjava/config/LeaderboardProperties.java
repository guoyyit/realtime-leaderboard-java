package com.github.guoyyit.realtimeleaderboardjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 榜单配置项。
 *
 * @param walDir           WAL 持久化目录
 * @param maxScore         允许的分数上限（含）
 * @param rateLimitPerSecond 每用户每秒允许的提交次数
 * @param rateLimitBurst   每用户突发容量
 * @param maxPageSize      单次分页查询的最大条数
 * @param maxContext       邻居查询的最大上下文长度
 */
@ConfigurationProperties(prefix = "leaderboard")
public record LeaderboardProperties(
        String walDir,
        long maxScore,
        double rateLimitPerSecond,
        int rateLimitBurst,
        int maxPageSize,
        int maxContext) {
}

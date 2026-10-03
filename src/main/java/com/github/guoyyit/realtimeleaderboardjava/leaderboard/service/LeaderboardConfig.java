package com.github.guoyyit.realtimeleaderboardjava.leaderboard.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 排行榜配置项。
 */
@Component
public class LeaderboardConfig {

    /** 本地持久化数据目录 */
    @Value("${leaderboard.data-dir:./leaderboard-data}")
    private Path dataDir = Path.of("./leaderboard-data");

    /** 是否对 WAL 执行 fsync（更强持久性，更低性能） */
    @Value("${leaderboard.wal-fsync:false}")
    private boolean walFsync = false;

    /** 每写入多少条 WAL 后触发一次快照 */
    @Value("${leaderboard.snapshot-every:1000}")
    private long snapshotEvery = 1000;

    /** 每用户每秒允许的提交数（令牌桶填充速率） */
    @Value("${leaderboard.rate-limit.per-user-per-second:10}")
    private long rateLimitPerSecond = 10;

    /** 每用户令牌桶容量（突发上限） */
    @Value("${leaderboard.rate-limit.capacity:20}")
    private long rateLimitCapacity = 20;

    /** 允许的最低分数（含） */
    @Value("${leaderboard.score-min:0}")
    private long scoreMin = 0;

    /** 允许的最高分数（含） */
    @Value("${leaderboard.score-max:1000000000}")
    private long scoreMax = 1_000_000_000L;

    public Path getDataDir() {
        return dataDir;
    }

    public void setDataDir(Path dataDir) {
        this.dataDir = dataDir;
    }

    public boolean isWalFsync() {
        return walFsync;
    }

    public void setWalFsync(boolean walFsync) {
        this.walFsync = walFsync;
    }

    public long getSnapshotEvery() {
        return snapshotEvery;
    }

    public void setSnapshotEvery(long snapshotEvery) {
        this.snapshotEvery = snapshotEvery;
    }

    public long getRateLimitPerSecond() {
        return rateLimitPerSecond;
    }

    public void setRateLimitPerSecond(long rateLimitPerSecond) {
        this.rateLimitPerSecond = rateLimitPerSecond;
    }

    public long getRateLimitCapacity() {
        return rateLimitCapacity;
    }

    public void setRateLimitCapacity(long rateLimitCapacity) {
        this.rateLimitCapacity = rateLimitCapacity;
    }

    public long getScoreMin() {
        return scoreMin;
    }

    public void setScoreMin(long scoreMin) {
        this.scoreMin = scoreMin;
    }

    public long getScoreMax() {
        return scoreMax;
    }

    public void setScoreMax(long scoreMax) {
        this.scoreMax = scoreMax;
    }
}

package com.github.guoyyit.realtimeleaderboardjava.config;

import com.github.guoyyit.realtimeleaderboardjava.core.LeaderboardRegistry;
import com.github.guoyyit.realtimeleaderboardjava.persistence.WalStore;
import com.github.guoyyit.realtimeleaderboardjava.service.LeaderboardService;
import com.github.guoyyit.realtimeleaderboardjava.service.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

@Configuration
@EnableConfigurationProperties(LeaderboardProperties.class)
public class LeaderboardConfig {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardConfig.class);

    @Bean
    public RateLimiter rateLimiter(LeaderboardProperties properties) {
        return new RateLimiter(properties.rateLimitPerSecond(), properties.rateLimitBurst());
    }

    @Bean(destroyMethod = "close")
    public WalStore walStore(LeaderboardProperties properties) {
        return new WalStore(Path.of(properties.walDir()));
    }

    /**
     * 构建注册表并重放 WAL 恢复榜单。重放语义与增量写入一致（同用户取最高分），
     * 因此恢复结果与崩溃前内存状态一致；序号水位推进到历史最大值之后，避免序号冲突。
     */
    @Bean
    public LeaderboardRegistry leaderboardRegistry(WalStore walStore) {
        AtomicLong seq = new AtomicLong(1);
        LeaderboardRegistry registry = new LeaderboardRegistry(seq::getAndIncrement);
        var entries = walStore.readAll();
        for (var entry : entries) {
            registry.replay(entry.boardId(), entry.record());
        }
        seq.set(registry.nextSeqAfterReplay());
        log.info("replayed {} WAL entries, boards={}, nextSeq={}",
                entries.size(), registry.boardIds().size(), seq.get());
        return registry;
    }

    @Bean
    public LeaderboardService leaderboardService(LeaderboardRegistry registry, WalStore walStore,
                                                 RateLimiter rateLimiter, LeaderboardProperties properties) {
        return new LeaderboardService(registry, walStore, rateLimiter, properties);
    }
}

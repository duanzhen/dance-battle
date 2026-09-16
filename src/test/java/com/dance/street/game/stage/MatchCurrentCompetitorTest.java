package com.dance.street.game.stage;

import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.impl.MatchCurrentCompetitorStore;
import org.dromara.common.redis.utils.RedisUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 大屏/导播台「当前上场选手」标记走跨实例缓存(Redis),不落库。
 *
 * <p>此前该标记存在组件私有的 {@code ConcurrentHashMap} 里,分布式部署时
 * 导播台在 A 实例标记、大屏从 B 实例读就是空的。现在统一放进 {@link RedisUtils}
 * 这一层:distributed 形态下读的是同一个 Redis;Redis 不可用时(standalone/native)
 * RedisUtils 自动降级为进程内缓存。</p>
 *
 * <p>测试环境 Redis 是关的,跑的正是降级路径;这里断言值能从
 * {@link MatchCurrentCompetitorStore#KEY_PREFIX} 这个共享缓存键读回来——
 * 如果哪天有人把它改回组件私有 Map,这条会红。</p>
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class MatchCurrentCompetitorTest {

    private static final String DB_PATH = "target/match-current-competitor.db";

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
            "jdbc:sqlite:" + DB_PATH
                + "?date_class=text&date_string_format=yyyy-MM-dd HH:mm:ss.SSS");
    }

    @BeforeAll
    static void cleanDb() throws Exception {
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            Files.deleteIfExists(Path.of(DB_PATH + suffix));
        }
    }

    @Autowired
    private TTournamentMapper tournamentMapper;
    @Autowired
    private TStageMapper stageMapper;
    @Autowired
    private TMatchMapper matchMapper;
    @Autowired
    private TCompetitorMapper competitorMapper;
    @Autowired
    private ITStageLifecycleService lifecycleService;

    @Test
    void markGoesThroughSharedCacheAndCanBeCleared() {
        TTournament t = new TTournament();
        t.setName("当前上场标记");
        tournamentMapper.insert(t);

        TStage stage = new TStage();
        stage.setTournamentId(t.getId());
        stage.setName("海选");
        stage.setStageMode("AUDITION");
        stage.setStatus(StageConstants.STAGE_GAMING);
        stageMapper.insert(stage);

        TMatch match = new TMatch();
        match.setTournamentId(t.getId());
        match.setStageId(stage.getId());
        match.setName("海选赛");
        match.setStatus(StageConstants.MATCH_GAMING);
        match.setMatchMode("VOTING");
        matchMapper.insert(match);

        TCompetitor c = new TCompetitor();
        c.setTournamentId(t.getId());
        c.setStageId(stage.getId());
        c.setType(0L);
        c.setName("选手A");
        c.setNumber("1");
        c.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        competitorMapper.insert(c);

        lifecycleService.setMatchCurrentCompetitor(match.getId(), c.getId());

        // 关键断言:值落在共享缓存层(分布式=Redis,单机=RedisUtils 的本地降级),
        // 而不是某个组件自己的私有字段里
        assertEquals(String.valueOf(c.getId()),
            RedisUtils.getCacheObject(MatchCurrentCompetitorStore.KEY_PREFIX + match.getId()),
            "当前上场标记应写入跨实例缓存");
        assertEquals(c.getId(), lifecycleService.getMatchCurrentCompetitor(match.getId()));

        // 取消标记必须真正删掉键,否则大屏会一直高亮上一个选手
        lifecycleService.setMatchCurrentCompetitor(match.getId(), null);
        assertNull(RedisUtils.getCacheObject(MatchCurrentCompetitorStore.KEY_PREFIX + match.getId()),
            "取消标记应删除缓存键");
        assertNull(lifecycleService.getMatchCurrentCompetitor(match.getId()));
    }
}

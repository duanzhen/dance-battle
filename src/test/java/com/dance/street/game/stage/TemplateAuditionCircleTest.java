package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.bo.TTournamentTemplateBo;
import com.dance.street.game.domain.vo.TTournamentVo;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.StageRosterGroupCodec;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.service.ITTournamentService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模板建赛的海选圈约定:只建 <b>1 个圈</b>,模板创建的全部裁判默认都绑到这个圈上,
 * 晋级名额全给它;下一赛段的出口按"第 1 圈名次"取人(要分圈由主办方后面自己加圈)。
 */
@SpringBootTest(properties = {
    "app.redis.enabled=false",
    "app.schema-init.enabled=true"
})
class TemplateAuditionCircleTest {

    private static final String DB_PATH = "target/template-audition-circle.db";

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

    @Autowired private TStageMapper stageMapper;
    @Autowired private TMatchMapper matchMapper;
    @Autowired private TRefereeMapper refereeMapper;
    @Autowired private TMatchRefereeMapper matchRefereeMapper;
    @Autowired private ITTournamentService tournamentService;

    @Test
    void templateCreatesSingleCircleWithAllRefereesBound() {
        TTournamentTemplateBo bo = new TTournamentTemplateBo();
        bo.setName("模板建赛-单圈");
        bo.setTemplateCode("AUDITION_16");
        bo.setRefereeCount(2);
        TTournamentVo tournament = tournamentService.createByTemplate(bo);
        Long tid = tournament.getId();
        assertNotNull(tid);

        List<TStage> stages = stageMapper.selectList(Wrappers.<TStage>lambdaQuery()
            .eq(TStage::getTournamentId, tid)
            .orderByAsc(TStage::getId));
        TStage audition = stages.stream()
            .filter(s -> "AUDITION".equals(s.getStageMode()))
            .findFirst().orElseThrow(() -> new AssertionError("模板应包含海选赛段"));

        // 配置:1 个圈,晋级名额全部给这个圈
        RuleConfigHolder rc = RuleConfigParser.parse(audition.getRuleConfig());
        assertEquals(1, rc.getCircles(), "模板建赛只建 1 个圈");
        assertEquals(List.of(16), rc.getCircleAdvanceCounts(), "16 个晋级名额应全部给第 1 圈");

        // 圈场次已建出,且只有一个 ZONE-1
        List<TMatch> circles = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, audition.getId()));
        assertEquals(1, circles.size(), "模板建赛应恰好建出 1 个圈场次");
        assertEquals("ZONE-1", circles.get(0).getDisplayZone());

        // 模板创建的裁判全部绑到这个圈上
        List<TReferee> referees = refereeMapper.selectList(Wrappers.<TReferee>lambdaQuery()
            .eq(TReferee::getTournamentId, tid));
        assertEquals(2, referees.size());
        Set<Long> bound = matchRefereeMapper.selectList(Wrappers.<TMatchReferee>lambdaQuery()
                .eq(TMatchReferee::getMatchId, circles.get(0).getId()))
            .stream().map(TMatchReferee::getRefereeId).collect(Collectors.toSet());
        assertEquals(referees.stream().map(TReferee::getId).collect(Collectors.toSet()), bound,
            "模板创建的 2 名裁判都应默认绑到这唯一的圈上");

        // 下一赛段:出口按"第 1 圈名次"取人,默认的"全场名次"组已被替换
        TStage next = stages.stream()
            .filter(s -> !"AUDITION".equals(s.getStageMode()))
            .findFirst().orElseThrow(() -> new AssertionError("模板应包含下一赛段"));
        List<TStageRosterGroupBo> groups = StageRosterGroupCodec.parse(next.getRosterConfigJson());
        assertTrue(groups.stream().anyMatch(g -> "ZONE-1".equals(g.getZone())
                && Boolean.TRUE.equals(g.getRankByZone())
                && Integer.valueOf(1).equals(g.getRankStart())
                && Integer.valueOf(16).equals(g.getRankEnd())),
            "下一赛段出口应为「第 1 圈名次 1~16」,实际: " + groups);
        assertTrue(groups.stream().noneMatch(g -> Objects.equals(g.getSourceStageId(), audition.getId())
                && g.getZone() == null && g.getRankStart() == null && g.getRankEnd() == null),
            "默认的「全场名次」来源组应已被按圈出口替换");
    }
}

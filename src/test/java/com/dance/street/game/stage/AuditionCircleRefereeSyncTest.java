package com.dance.street.game.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TRefereeStage;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TStageConfigBo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TRefereeStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITStageService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 海选的裁判由「圈配置」决定:保存圈裁判后,赛段级绑定(t_referee_stage)跟着圈配置走。
 *
 * <p>赛段级绑定是裁判端赛段列表、大屏记分板裁判名、SSE 推送受众的取数来源。海选不再让
 * 主办方单独配「裁判组」(通用配置里已隐藏),所以必须由圈配置同步出这一份,否则会出现
 * 「圈上配了裁判,裁判端却看不到这个赛段」。</p>
 */
@SpringBootTest(properties = {"app.redis.enabled=false", "app.schema-init.enabled=true"})
class AuditionCircleRefereeSyncTest {

    private static final String DB_PATH = "target/audition-circle-referee-sync.db";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_PATH
            + "?date_class=text&date_string_format=yyyy-MM-dd HH:mm:ss.SSS");
    }

    @BeforeAll
    static void clean() throws Exception {
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            Files.deleteIfExists(Path.of(DB_PATH + suffix));
        }
    }

    @Autowired private TTournamentMapper tournamentMapper;
    @Autowired private TRefereeMapper refereeMapper;
    @Autowired private TRefereeStageMapper refereeStageMapper;
    @Autowired private ITStageService stageService;

    @Test
    void auditionStageRefereesFollowCircleConfig() {
        Long tid = newTournament("circle-referee");
        Long r1 = newReferee(tid, "裁判甲");
        Long r2 = newReferee(tid, "裁判乙");
        Long r3 = newReferee(tid, "裁判丙");
        TStageVo audition = newStage(tid, "海选", "AUDITION");

        // 第 1 圈绑甲/乙 → 赛段级绑定 = 甲/乙(裁判端据此看到本赛段)
        saveCircleReferees(audition.getId(), List.of(List.of(r1, r2)));
        assertEquals(List.of(r1, r2), stageRefereeIds(audition.getId()));

        // 改成只绑乙 → 跟着收敛,甲不再占用本赛段
        saveCircleReferees(audition.getId(), List.of(List.of(r2)));
        assertEquals(List.of(r2), stageRefereeIds(audition.getId()));

        // 多圈取并集
        saveCircleReferees(audition.getId(), List.of(List.of(r1), List.of(r2, r3)));
        assertEquals(List.of(r1, r2, r3), stageRefereeIds(audition.getId()));
    }

    /** 非海选赛段不参与同步:它的裁判仍由「裁判组」显式分配。 */
    @Test
    void nonAuditionStageKeepsItsOwnRefereeGroup() {
        Long tid = newTournament("knockout-referee");
        Long r1 = newReferee(tid, "裁判甲");
        TStageVo knockout = newStage(tid, "16强", "KNOCKOUT");

        TRefereeStage link = new TRefereeStage();
        link.setRefereeId(r1);
        link.setStageId(knockout.getId());
        link.setTournamentId(tid);
        refereeStageMapper.insert(link);

        saveCircleReferees(knockout.getId(), List.of());
        assertEquals(List.of(r1), stageRefereeIds(knockout.getId()),
            "淘汰赛的赛段级裁判不应被圈配置清掉");
    }

    /** 从未配过圈裁判(null)的海选:保持现状,不做任何同步。 */
    @Test
    void auditionStageWithoutCircleConfigIsUntouched() {
        Long tid = newTournament("audition-no-circle-config");
        Long r1 = newReferee(tid, "裁判甲");
        TStageVo audition = newStage(tid, "海选", "AUDITION");

        TRefereeStage link = new TRefereeStage();
        link.setRefereeId(r1);
        link.setStageId(audition.getId());
        link.setTournamentId(tid);
        refereeStageMapper.insert(link);

        TStageConfigBo cfg = new TStageConfigBo();
        cfg.setId(audition.getId());
        cfg.setRuleConfig("{\"advanceCondition\":\"score\",\"advanceCount\":8,\"circles\":1}");
        stageService.updateConfig(cfg);

        assertEquals(List.of(r1), stageRefereeIds(audition.getId()),
            "没有 circleRefereeIds 时不应改动赛段级裁判绑定");
    }

    // ===== 工具 =====

    private void saveCircleReferees(Long stageId, List<List<Long>> circleRefs) {
        StringBuilder json = new StringBuilder("{\"advanceCondition\":\"score\",\"advanceCount\":8,\"circles\":")
            .append(Math.max(1, circleRefs.size()))
            .append(",\"circleAdvanceCounts\":[8],\"circleRefereeIds\":[");
        for (int i = 0; i < circleRefs.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('[');
            List<Long> refs = circleRefs.get(i);
            for (int j = 0; j < refs.size(); j++) {
                if (j > 0) {
                    json.append(',');
                }
                json.append(refs.get(j));
            }
            json.append(']');
        }
        json.append("]}");
        TStageConfigBo cfg = new TStageConfigBo();
        cfg.setId(stageId);
        cfg.setRuleConfig(json.toString());
        stageService.updateConfig(cfg);
    }

    private List<Long> stageRefereeIds(Long stageId) {
        return refereeStageMapper.selectList(Wrappers.<TRefereeStage>lambdaQuery()
                .eq(TRefereeStage::getStageId, stageId))
            .stream().map(TRefereeStage::getRefereeId).sorted().toList();
    }

    private Long newTournament(String name) {
        TTournament t = new TTournament();
        t.setName(name);
        tournamentMapper.insert(t);
        return t.getId();
    }

    private Long newReferee(Long tournamentId, String name) {
        TReferee r = new TReferee();
        r.setTournamentId(tournamentId);
        r.setName(name);
        refereeMapper.insert(r);
        return r.getId();
    }

    private TStageVo newStage(Long tournamentId, String name, String mode) {
        TStageBo bo = new TStageBo();
        bo.setTournamentId(tournamentId);
        bo.setName(name);
        bo.setStageMode(mode);
        bo.setStatus(StageConstants.STAGE_DRAFT);
        bo.setTeamCountStart(0L);
        bo.setTeamCountEnd(8L);
        bo.setIsInitialized(0L);
        return stageService.insertByBo(bo);
    }
}

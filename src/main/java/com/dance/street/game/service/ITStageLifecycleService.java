package com.dance.street.game.service;

import com.dance.street.game.domain.bo.CalculateAdvancementBo;
import com.dance.street.game.domain.bo.GenerateMatchesBo;
import com.dance.street.game.domain.bo.InitializeStageBo;
import com.dance.street.game.domain.vo.ArenaOverviewVo;
import com.dance.street.game.domain.vo.CircleAssignVo;

import java.util.List;

/**
 * 赛段生命周期服务:初始化 → 生成对阵 → 开始 → 完成 → 晋级流转。
 *
 * @author duane
 */
public interface ITStageLifecycleService {

    /** 锁定参赛方名单并排种子顺位,赛段 DRAFT→PENDING */
    void initialize(InitializeStageBo bo);

    /** 按赛制生成对阵(TMatch/Participant/Round + promotion_rule 连线) */
    void generateMatches(GenerateMatchesBo bo);

    /** 赛段 PENDING→GAMING;淘汰赛只开第一场(逐场进行),其余赛制所有场次进入 GAMING */
    void startStage(Long stageId);

    /**
     * 海选赛段进行中补签到:把新参赛方挂入当前人数最少的圈场次(新增 participant + round),
     * 保证其可被裁判打分并参与最终结算。仅 AUDITION + GAMING 且已生成场次时生效,否则为空操作。
     */
    void appendAuditionCompetitor(Long stageId, Long competitorId);

    /** 海选分圈随机抽取:把已签到选手随机均衡分配到各圈场次(可重抽,赛段未开始时) */
    List<CircleAssignVo> randomCircles(Long stageId);

    /** 擂台赛:按轮转队列创建并开始下一场对决(胜者守擂、败者队尾)。赛段须 GAMING 且无进行中对决 */
    void startNextArenaMatch(Long stageId);

    /** 擂台赛总览:轮转队列(含积分)与当前对决 */
    ArenaOverviewVo getArenaOverview(Long stageId);

    /** 赛段 GAMING→SETTLED(需所有场次已结算);AUTO 模式自动触发晋级 */
    void completeStage(Long stageId);

    /** 计算晋级:从已结算赛段取晋级者,在下一赛段创建新参赛方。返回晋级人数(幂等:已晋级返回 0) */
    int calculateAdvancement(CalculateAdvancementBo bo);
}

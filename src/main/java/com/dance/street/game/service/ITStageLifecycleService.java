package com.dance.street.game.service;

import com.dance.street.game.domain.bo.CalculateAdvancementBo;
import com.dance.street.game.domain.bo.GenerateMatchesBo;
import com.dance.street.game.domain.bo.InitializeStageBo;
import com.dance.street.game.domain.bo.AddGuestBo;
import com.dance.street.game.domain.bo.SeedOrderBo;
import com.dance.street.game.domain.vo.ArenaOverviewVo;
import com.dance.street.game.domain.vo.CircleAssignVo;
import com.dance.street.game.domain.vo.RankDetailVo;
import com.dance.street.game.domain.vo.TCompetitorVo;

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
     * 轮空场次自动结算:单边轮空(1 名真人)直接判胜并填下游/标晋级,双边轮空置为已结算。
     * 返回本次结算的场次数。
     */
    int settleByeMatches(Long stageId);

    /**
     * 海选/排名赛补签到:把新参赛方挂入圈场次(新增 participant + round),保证可被裁判打分并参与结算。
     * 分圈且配置了每圈名额时,按"剩余名额余量"择优落圈;否则挂入人数最少的圈。
     * AUDITION/RANK + GAMING/PENDING 且已生成场次时生效,尚未生成场次时为空操作(后续生成会纳入)。
     */
    void appendStageCompetitor(Long stageId, Long competitorId);

    /**
     * 同上,但显式指定目标圈场次(签到弹窗手动选圈)。目标圈须为未结算的正式圈。
     */
    void appendStageCompetitor(Long stageId, Long competitorId, Long targetMatchId);

    /**
     * GUEST 加入:除海选外任意赛段,在赛段规划/未开始态(DRAFT/PENDING)且未初始化时加入。
     * 仅创建参赛单位进入 GUEST 池,不自动挂入任何场次;导播按外部抽签结果设定种子顺序后,
     * 由 initialize → generateMatches 生成对阵(GUEST 胜出即占晋级名额)。
     */
    TCompetitorVo addGuest(AddGuestBo bo);

    /**
     * 按外部抽签结果批量设定赛段参赛方种子顺序(seedRank 1..n)。
     * 仅允许赛段尚未 initialize 时执行;返回参赛方数量。
     */
    int setSeedOrder(SeedOrderBo bo);

    /** 海选分圈随机抽取:把已签到选手随机均衡分配到各圈场次(可重抽,赛段未开始时) */
    List<CircleAssignVo> randomCircles(Long stageId);

    /** 擂台赛:按轮转队列创建并开始下一场对决(胜者守擂、败者队尾、平局双方均排到队尾)。赛段须 GAMING 且无进行中对决 */
    void startNextArenaMatch(Long stageId);

    /** 擂台赛总览:轮转队列(含积分)与当前对决 */
    ArenaOverviewVo getArenaOverview(Long stageId);

    /** 赛段 GAMING→SETTLED(需所有场次已结算);结算后仅产出晋级者/排名,由导播台在中间态「确认晋级」 */
    /**
     * 完成赛段:GAMING→SETTLED(结算后需在中间态「确认晋级」)。
     *
     * @return 结算后的赛段状态:SETTLED=已正常完成;GAMING=存在未完成场次(如海选二海/加赛),
     *         赛段保持进行中,需完成剩余判罚后再次调用。
     */
    String completeStage(Long stageId);

    /**
     * 回退到草稿:清除赛段已生成的全部场次(级联轮次/参赛明细/打分),参赛方回退待定,
     * isInitialized 归零、状态置 DRAFT,可重新排种子/生成对阵。仅 DRAFT/PENDING 状态可用。
     */
    void resetStageToDraft(Long stageId);

    /** 计算晋级:从已结算赛段取晋级者,在下一赛段创建新参赛方。返回晋级人数(幂等:已晋级返回 0) */
    int calculateAdvancement(CalculateAdvancementBo bo);

    /**
     * 排名赛:同分并列导致晋级名额超限时,导播台在中间态手动指定晋级者
     * (传入全部待定者即全部晋级,未选中的待定者标记淘汰)。返回调整的晋级人数。
     */
    int adjustAdvancement(Long stageId, List<Long> competitorIds);

    /**
     * 海选弃权/顶替(结算后、确认晋级前):
     * <ul>
     *   <li>仅传 withdrawnCompetitorId:标记晋级者弃权,其后的晋级者名次整体前移
     *       (名额往前推;不顶替时末尾空位在淘汰赛中即轮空);</li>
     *   <li>传 replacementCompetitorId:把任意被淘汰的选手顶替晋级,补齐到晋级名单末尾
     *       (支持任意 被淘汰者→任意晋级者 的替换;顶替总数受晋级名额上限约束)。</li>
     * </ul>
     * 返回新晋级的顶替人数(仅弃权时返回 0)。
     */
    int promoteReplacement(Long stageId, Long withdrawnCompetitorId, Long replacementCompetitorId);

    /** 排名赛排名明细:按圈返回每位参赛者的总分与各维度聚合分(未公布时隐藏分数) */
    RankDetailVo getRankDetail(Long stageId);
}

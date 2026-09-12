package com.dance.street.game.service;

import com.dance.street.game.domain.bo.GenerateMatchesBo;
import com.dance.street.game.domain.bo.InitializeStageBo;
import com.dance.street.game.domain.bo.SeedOrderBo;
import com.dance.street.game.domain.vo.ArenaOverviewVo;
import com.dance.street.game.domain.vo.AuditionResultVo;
import com.dance.street.game.domain.vo.RankDetailVo;
import com.dance.street.game.domain.vo.TCompetitorVo;

import java.util.List;

/**
 * 赛段生命周期服务:初始化 → 生成对阵 → 开始 → 完成 → 晋级流转。
 *
 * @author duane
 */
public interface ITStageLifecycleService {

    /** 锁定参赛方名单并排种子顺位(业务状态保持 DRAFT,开始后进入 GAMING) */
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
     * 结算单个轮空场次(导播台点「开始」轮空场次时调用):单边轮空判胜填下游/标晋级,
     * 双边轮空仅置已结算;非轮空场次返回 false。
     */
    boolean settleByeMatch(Long matchId);

    /**
     * 海选/排名赛补签到:把新参赛方挂入圈场次(新增 participant + round),保证可被裁判打分并参与结算。
     * 海选分圈为按号码顺序均分时,按新参赛方号码在其整体号码序列中的位置落圈;
     * 随机分圈时,配置了每圈名额则按"剩余名额余量"择优落圈,否则挂入人数最少的圈。
     * AUDITION/RANK + GAMING/PENDING 且已生成场次时生效,尚未生成场次时为空操作(后续生成会纳入)。
     */
    void appendStageCompetitor(Long stageId, Long competitorId);

    /**
     * 同上,但显式指定目标圈场次(签到弹窗手动选圈)。目标圈须为未结算的正式圈。
     */
    void appendStageCompetitor(Long stageId, Long competitorId, Long targetMatchId);

    /**
     * 同上,并支持「目标圈序号」:海选分圈尚未生成圈场次时,
     * 后端先按配置补建 ZONE-1..n 计划圈,再把新选手挂入 zoneIndex 对应的圈。
     */
    void appendStageCompetitor(Long stageId, Long competitorId, Long targetMatchId, Integer zoneIndex);

    /**
     * 编辑签到结果后重排落位:已改号的参赛方先从原圈场次移除,
     * 再按新号码挂入对应圈(按号分圈自动计算圈位;随机分圈可显式传 targetMatchId 换圈)。
     * 该参赛方已有打分记录时禁止移动。
     */
    void relocateCheckInCompetitor(Long stageId, Long competitorId, Long targetMatchId);

    /**
     * 解除签到:从圈场次移除参赛方及其轮次(已有打分记录时禁止),
     * 同场后续号码槽位/轮次自动前移补位。
     */
    void removeCheckInCompetitor(Long stageId, Long competitorId);

    /**
     * 按外部抽签结果批量设定赛段参赛方种子顺序(seedRank 1..n)。
     * 仅允许赛段尚未 initialize 时执行;返回参赛方数量。
     */
    int setSeedOrder(SeedOrderBo bo);

    /**
     * 海选分圈「确保圈场次」:圈数按配置建齐(一个圈 = 一个 ZONE match,允许暂时空场)。
     * 抽号/签到页打开时调用,保证顶部圈栏始终对应真实场次;已有场次开始或圈已建齐时为空操作。
     */
    void ensureAuditionCircles(Long stageId);

    /** 擂台赛:按轮转队列创建并开始下一场对决(胜者守擂、败者队尾、平局双方均排到队尾)。赛段须 GAMING 且无进行中对决 */
    void startNextArenaMatch(Long stageId);

    /**
     * 擂台赛参赛选手弃权:标记 WITHDRAWN,不再参与排队/对阵/排名。
     * 进行中的对决包含该选手时作废该对决(避免对手白拿积分/对决悬挂),队列下一位补位。
     */
    void withdrawArenaCompetitor(Long stageId, Long competitorId);

    /**
     * 擂台赛临时弃权(导播台):该选手本轮跳过、排到队尾,后续仍参与排队与排名;
     * 进行中的对决包含该选手时作废该对决,队列下一位顶上来。
     */
    void tempWithdrawArenaCompetitor(Long stageId, Long competitorId);

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

    /**
     * 确认晋级:把已结算赛段的晋级者装配进下一赛段名单(名单整单装配的唯一内核)。
     * 供导播台「跳过中间态确认」使用;常规路径由中间态确认名单直接调用名单服务。
     * 返回带入人数(幂等:已装配返回 0)。
     */
    int calculateAdvancement(Long stageId);

    /**
     * 导出海选结果 Excel:号码 / 选手名 / 各裁判分数(每裁判一列) / 总平均分 / 排名
     *
     * @param stageId  海选赛赛段ID
     * @param response HTTP 响应(直接写 xlsx)
     */
    void exportAuditionResult(Long stageId, jakarta.servlet.http.HttpServletResponse response);

    /**
     * 查询海选赛段结果(统一口径):原始海选成绩 + 二海/三海…加赛明细。
     * 二海分数只用于同分者决出晋级顺序,不计入原始总分;
     * 导出与前端各组件均消费本结果,不再各自聚合。
     */
    AuditionResultVo queryAuditionResult(Long stageId);

    /**
     * 海选加赛(二海/三海…)全员打分完成后自动结算:
     * 幂等,仅处理进行中的加赛场次;若再次同分会自动生成下一级加赛。
     * 返回是否执行了结算。
     */
    boolean tryAutoSettleTiebreaker(Long matchId);

    /**
     * 标记场次当前上场选手(海选大屏 widget 用):仅标记与广播,不参与结算;
     * competitorId 传 null 表示清除。
     */
    void setMatchCurrentCompetitor(Long matchId, Long competitorId);

    /** 查询场次当前标记的上场选手;未标记返回 null */
    Long getMatchCurrentCompetitor(Long matchId);

    /**
     * 赛事是否开启「跳过中间态确认阶段」(themeConfig.autoConfirmAdvancement,默认开启):
     * 开启后不再由后端在完成赛段时自动确认晋级,而是由 MC 导播台在开始赛段时
     * 弹窗确认后调用确认晋级接口,再开始本赛段。
     */
    boolean isAutoConfirmAdvancement(Long tournamentId);

    /**
     * 排名赛:同分并列导致晋级名额超限时,导播台在中间态手动指定晋级者
     * (传入全部待定者即全部晋级,未选中的待定者标记淘汰)。返回调整的晋级人数。
     */
    int adjustAdvancement(Long stageId, List<Long> competitorIds);

    /** 排名赛排名明细:按圈返回每位参赛者的总分与各维度聚合分(未公布时隐藏分数) */
    RankDetailVo getRankDetail(Long stageId);
}

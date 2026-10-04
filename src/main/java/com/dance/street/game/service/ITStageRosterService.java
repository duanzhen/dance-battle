package com.dance.street.game.service;

import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TStageRosterEntry;
import com.dance.street.game.domain.bo.TStageRosterBo;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.bo.TStageRosterOrderBo;
import com.dance.street.game.domain.bo.TStageRosterOverrideBo;
import com.dance.street.game.domain.vo.RosterCandidatesVo;
import com.dance.street.game.domain.vo.RosterPreviewVo;
import com.dance.street.game.domain.vo.StageParticipantsVo;
import com.dance.street.game.domain.vo.TStageRosterOverrideVo;
import com.dance.street.game.domain.vo.TStageRosterVo;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 名单服务:数据所有权 = 来源层(t_competitor) → 中间层(t_stage_roster_entry,一行一个座位)
 * → 目标层(确认名单后物化的 t_competitor)。
 *
 * <p>规则(来源组)存在独立边表 {@code t_stage_roster_group}:一行 = 一条入边
 * (来源赛段 → 目标赛段)+ 取人规则,边之间没有先后语义(座位由来源赛段给出的座号决定),
 * {@code generated=1} 表示系统自动补的链式衔接。确认与否留在
 * {@code t_stage.roster_applied / roster_skipped}。中间层是唯一的事实来源,
 * 所有读路径(中间态预览 / 大屏预排 / 导播台 / 名单页)都只读它。</p>
 *
 * @author duane
 */
public interface ITStageRosterService {

    /**
     * 为目标赛段合成默认名单来源组:
     * 入口赛段 = 一组签到(STREAM);非入口 = 一组"上一赛段·晋级·AUTO"。幂等。
     */
    void ensureRosterForStage(TStage stage);

    /** 目标赛段名单详情(唯一:一赛段一份名单) */
    List<TStageRosterVo> listByTarget(Long targetStageId);

    /**
     * 某赛段的名单来源组(赛段间依赖的边+取人规则),按行 id 稳定顺序。
     *
     * <p>供赛段生命周期里"配对模式/预排口径"等只读判断使用——它们此前直接解析
     * {@code t_stage.roster_config_json},表化后统一走这里。</p>
     */
    List<TStageRosterGroupBo> groupsOfStage(Long targetStageId);

    /**
     * 批量取多个目标赛段的名单(赛段列表/导播台列表用)。
     *
     * <p>逐个 {@link #listByTarget} 会让"每赛段一次名单查询 + 每来源组一次来源赛段查询"
     * 随赛段数放大成 O(赛段×组);批量口径下整页只需 3 条 SQL。</p>
     *
     * @return targetStageId -&gt; 该赛段的名单(与 listByTarget 同结构,通常只有一个元素)
     */
    Map<Long, List<TStageRosterVo>> listByTargets(Collection<Long> targetStageIds);

    /** 出口视角:全赛段扫描,返回引用某来源赛段的名单 */
    List<TStageRosterVo> listBySource(Long sourceStageId);

    /**
     * 确认名单(唯一写目标层的入口):把中间层当前的行物化为目标赛段参赛方。
     * 容量硬校验(超限报错),不再写回源赛段结果。
     */
    int applyRoster(Long targetStageId, Map<Long, List<Long>> manualSelections);

    /** 向目标赛段名单追加来源组(幂等按组去重;至少保留一组),返回追加后的名单 */
    TStageRosterVo addGroups(Long stageId, TStageRosterBo bo);

    /**
     * 删除一条来源组(按行 ID 定位)。
     *
     * <p>表化后按 ID 而不是数组下标:删除同目标内的其他组不会让下标位移,编辑不会指错条目。</p>
     */
    void removeGroup(Long stageId, Long groupId);

    /** 编辑一条来源组规则(按行 ID 定位) */
    void updateGroup(Long stageId, Long groupId, TStageRosterGroupBo group);

    /** 名单候选(按来源组返回,手动点选/预览) */
    RosterCandidatesVo candidates(Long stageId);

    /** 名单行是否存在任一来源候选(开赛守卫用) */
    boolean hasAnyCandidate(Long stageId);

    /** 名单就绪度(纯函数):全部内部来源组对应源赛段已结算 */
    boolean isRosterReady(Long stageId);

    /**
     * 开赛守卫:校验目标赛段的名单是否允许开赛,不通过时抛 ServiceException。
     *
     * <p>规则:无内部来源组(STREAM/人工覆盖)的名单不受限;含内部来源组时,
     * 已装配(CONFIRMED)/已跳过(SKIPPED)直接放行,来源未结算完拦截,
     * 来源已结算但仍有候选未确认拦截,确无候选则放行(本赛段不带人)。</p>
     *
     * <p>名单语义集中在名单服务内,赛段生命周期只负责在开赛前调用本方法。</p>
     */
    void assertStageStartable(Long targetStageId);

    /**
     * 改链/插段后对账名单:清理引用了旧前驱的自动默认组/入口 STREAM,按当前 prev 补齐。
     * <p>链路没有实际变化时(例如只是保存赛段)为空操作,不会因为名单已装配而报错。</p>
     */
    void reconcileAfterLinkChange(Long stageId);

    /** 删段后为存活但丢失名单的赛段补建默认名单 */
    void ensureRosterForSurvivors(Collection<Long> tournamentIds);

    /** 删除守卫:赛段含任何自定义(固定)边(作为来源或目标)时不允许直接删除 */
    void assertStagesDeletable(Collection<Long> stageIds);

    /** 删除赛段后的边收口:被删段的出边删除、入边原地改挂到链上后继 */
    void reattachEdgesOnStageDelete(Collection<Long> deletedStageIds, Map<Long, Long> successorByStage);

    /** 删除赛段后:清掉以这些赛段为目标的中间层名单行 */
    void removeEntriesOfTargets(Collection<Long> targetStageIds);

    /** 显式跳过整份名单(本赛段不带人) */
    void markSkipped(Long stageId);

    /** 目标赛段重置为草稿:清 applied/skipped,快照行由调用方删除 */
    void resetByTarget(Long targetStageId);

    /** 名单人工覆盖列表 */
    List<TStageRosterOverrideVo> listOverrides(Long stageId);

    /** 新增人工覆盖(ADD_SOURCE/ADD_GUEST/REMOVE/SEED) */
    TStageRosterOverrideVo addOverride(Long stageId, TStageRosterOverrideBo bo);

    /** 编辑人工覆盖(外卡档案/种子位) */
    void updateOverride(Long stageId, Long overrideId, TStageRosterOverrideBo bo);

    /** 撤销人工覆盖 */
    void deleteOverride(Long stageId, Long overrideId);

    /** 批量撤销人工覆盖 */
    void deleteOverrides(Long stageId, List<Long> overrideIds);

    /** 保存手工名单顺序(两列拖动结果,按位置落种子位) */
    void reorderRoster(Long stageId, List<TStageRosterOrderBo.Item> items);

    /** 名单实时预览(直接读中间层的行,不做任何重算/重排) */
    RosterPreviewVo previewAssembled(Long stageId);

    /**
     * 中间层名单:两个赛段之间唯一的一份数据(一行 = 一个座位,含空位行)。
     *
     * <p>读路径(中间态预览 / 大屏预排 / 导播台 / 名单页)只读它;写路径(来源结算、来源组变更、
     * 人工调整)只改它。</p>
     */
    List<TStageRosterEntry> entriesOf(Long targetStageId);

    /**
     * 重建中间层名单:清空后按来源组规则全量生成,人工调整一并丢弃(上游一变就全部重新来)。
     *
     * @return 是否真的重建(赛段已开赛或来源未结算时为 false)
     */
    boolean rebuildEntries(Long targetStageId);

    /** 来源赛段变动后,重建所有"来源组引用了它"的下游赛段中间层 */
    int rebuildEntriesOfDownstream(Long sourceStageId);

    /**
     * 预晋级实时同步:把来源赛段<b>当前</b>已晋级(ADVANCE 且有名次)的人,按名次写进下游中间态;
     * 已经不再晋级(判错重判 / 重置)的人,座位还原成空位行。
     *
     * <p>淘汰赛每场判完就会把胜者标成 ADVANCE 并写回名次({@code DownstreamRouter.markAdvance}),
     * 所以"谁晋级了"从那一刻起就该在中间态可见。这里<b>只改受影响的座位行</b>,不整表重建 ——
     * 整表重建会把现场已经做好的加人/外卡/换位一起冲掉;整表重建只留给"来源结算 / 主动重建"
     * 这些权威时刻。中间层还没铺开时按规则整表物化一次(此时本来也没有人工调整可丢)。</p>
     *
     * @param competitorIds 只同步这些参赛方(单场判完/重判时传本场参赛方,写入范围就锁死在这几行);
     *                      null 或 空 = 该赛段全部
     * @return 实际改动的座位行数
     */
    int syncPreAdvance(Long sourceStageId);

    /** 同上,限定只同步这些人(只改与他们相关的座位行) */
    int syncPreAdvance(Long sourceStageId, Collection<Long> competitorIds);

    /**
     * 上游结算完成后,把下游赛段里"等上游填入"的座位({@code slot_kind=PENDING})归一到轮空({@code BYE})。
     *
     * <p>赛前抢先生成对阵时,空位先标成"待定"(还有人会来);来源赛段全部结算后还没人来,
     * 那就是真轮空 —— 不归一的话,现场会一直看到「轮空 / 待定」混在一起。</p>
     *
     * @return 归一化的座位行数
     */
    int settlePendingSeatsOfDownstream(Long sourceStageId);

    /**
     * 赛段参赛选手(大屏「参赛选手」控件用)。
     *
     * <p>名单已物化(确认晋级)读目标层的 {@code t_competitor};尚未物化读中间层名单,
     * 未落位的人也在返回里({@code seedRank=null, holding=true})——大屏任何时刻都能看到
     * "这个赛段目前有哪些人",不会因为名单没确认就整块空白。</p>
     */
    StageParticipantsVo listStageParticipants(Long stageId);
}

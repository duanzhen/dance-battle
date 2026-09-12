package com.dance.street.game.service;

import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.bo.TStageRosterBo;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.bo.TStageRosterOrderBo;
import com.dance.street.game.domain.bo.TStageRosterOverrideBo;
import com.dance.street.game.domain.vo.RosterCandidatesVo;
import com.dance.street.game.domain.vo.RosterPreviewVo;
import com.dance.street.game.domain.vo.TStageRosterOverrideVo;
import com.dance.street.game.domain.vo.TStageRosterVo;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 名单服务:名单是赛段的属性(t_stage.roster_*),名单即赛段属性,无独立实体/表。
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

    /** 出口视角:全赛段扫描,返回引用某来源赛段的名单 */
    List<TStageRosterVo> listBySource(Long sourceStageId);

    /**
     * 整单装配(唯一写库内核):规则 + 覆盖 合并后物化为目标赛段参赛行。
     * 容量硬校验(超限报错),不再写回源赛段结果。
     */
    int applyRoster(Long targetStageId, Map<Long, List<Long>> manualSelections);

    /** 向目标赛段名单追加来源组(幂等按组去重;至少保留一组) */
    TStageRosterVo addGroups(Long stageId, TStageRosterBo bo);

    /** 名单候选(按来源组返回,手动点选/预览) */
    RosterCandidatesVo candidates(Long stageId);

    /** 名单行是否存在任一来源候选(开赛守卫用) */
    boolean hasAnyCandidate(Long stageId);

    /** 名单就绪度(纯函数):全部内部来源组对应源赛段已结算 */
    boolean isRosterReady(Long stageId);

    /** 只读预排候选(AUTO 来源组,与 apply 同口径;MANUAL/STREAM 不参与) */
    List<TCompetitor> previewRoster(Long targetStageId);

    /** 删除名单中的某一来源组(至少保留一组) */
    void removeGroup(Long stageId, int groupIndex);

    /** 编辑名单中的某一来源组规则(出口/入口自定义配置共用) */
    void updateGroup(Long stageId, int groupIndex, TStageRosterGroupBo group);

    /**
     * 改链/插段后对账名单:清理引用了旧前驱的自动默认组/入口 STREAM,按当前 prev 补齐。
     * <p>链路没有实际变化时(例如只是保存赛段)为空操作,不会因为名单已装配而报错。</p>
     */
    void reconcileAfterLinkChange(Long stageId);

    /** 删段后为存活但丢失名单的赛段补建默认名单 */
    void ensureRosterForSurvivors(Collection<Long> tournamentIds);

    /** 删除赛段后:摘除其余赛段名单中引用被删赛段的来源组 */
    void removeSourceRefs(Collection<Long> deletedStageIds);

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

    /** 保存手工名单顺序(两列拖动结果,按位置落种子位) */
    void reorderRoster(Long stageId, List<TStageRosterOrderBo.Item> items);

    /** 名单实时预览(规则 + 覆盖合并,不落库) */
    RosterPreviewVo previewAssembled(Long stageId);
}

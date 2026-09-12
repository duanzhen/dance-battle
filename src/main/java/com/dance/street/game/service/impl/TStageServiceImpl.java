package com.dance.street.game.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.mybatis.core.page.PageQuery;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TCompetitorMember;
import com.dance.street.game.domain.TPlayer;
import com.dance.street.game.domain.TRefereeStage;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.domain.TVisWidget;
import com.dance.street.game.domain.TStageRosterOverride;
import com.dance.street.game.domain.vo.PreBracketVo;
import com.dance.street.game.domain.vo.StageFlowVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.domain.vo.TStageRosterVo;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.RosterConstants;
import com.dance.street.game.engine.common.StageRosterGroupCodec;
import com.dance.street.game.engine.common.PairingModeResolver;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.engine.generator.KnockoutGenerator;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TCompetitorMemberMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TPlayerMapper;
import com.dance.street.game.mapper.TRefereeStageMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TStageRosterOverrideMapper;
import com.dance.street.game.mapper.TVisWidgetMapper;
import com.dance.street.game.service.ITStageRosterService;
import com.dance.street.game.service.ITStageService;

import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 赛段流程Service业务层处理
 *
 * @author duane
 * @date 2026-01-06
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TStageServiceImpl implements ITStageService {

    private final TStageMapper baseMapper;
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TMatchRoundMapper matchRoundMapper;
    private final TCompetitorMapper competitorMapper;
    private final TCompetitorMemberMapper competitorMemberMapper;
    private final TRoundScoreMapper roundScoreMapper;
    private final TRefereeStageMapper refereeStageMapper;
    private final TPlayerMapper playerMapper;
    private final TVisWidgetMapper visWidgetMapper;
    private final TStageRosterOverrideMapper overrideMapper;
    private final ITStageRosterService rosterService;

    /**
     * 查询赛段流程
     *
     * @param id 主键
     * @return 赛段流程
     */
    @Override
    public TStageVo queryById(Long id){
        return baseMapper.selectVoById(id);
    }

    /**
     * 分页查询赛段流程列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 赛段流程分页列表
     */
    @Override
    public TableDataInfo<TStageVo> queryPageList(TStageBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TStage> lqw = buildQueryWrapper(bo);
        Page<TStageVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        enrichIncoming(result.getRecords());
        return TableDataInfo.build(result);
    }

    /**
     * 查询符合条件的赛段流程列表
     *
     * @param bo 查询条件
     * @return 赛段流程列表
     */
    @Override
    public List<TStageVo> queryList(TStageBo bo) {
        LambdaQueryWrapper<TStage> lqw = buildQueryWrapper(bo);
        List<TStageVo> list = baseMapper.selectVoList(lqw);
        enrichIncoming(list);
        return list;
    }

    /** 补入名单摘要:名单是赛段属性,直接按赛段读取 */
    private void enrichIncoming(List<TStageVo> stages) {
        if (stages == null || stages.isEmpty()) {
            return;
        }
        for (TStageVo stage : stages) {
            if (stage.getId() != null) {
                List<TStageRosterVo> rosters = rosterService.listByTarget(stage.getId());
                stage.setIncoming(rosters.isEmpty() ? null : rosters);
            }
        }
    }

    private LambdaQueryWrapper<TStage> buildQueryWrapper(TStageBo bo) {
        Map<String, Object> params = bo.getParams();
        LambdaQueryWrapper<TStage> lqw = Wrappers.lambdaQuery();
        lqw.orderByAsc(TStage::getId);
        lqw.eq(bo.getTournamentId() != null, TStage::getTournamentId, bo.getTournamentId());
        lqw.eq(bo.getPrevStageId() != null, TStage::getPrevStageId, bo.getPrevStageId());
        lqw.eq(bo.getNextStageId() != null, TStage::getNextStageId, bo.getNextStageId());
        lqw.eq(bo.getParentStageId() != null, TStage::getParentStageId, bo.getParentStageId());
        lqw.like(StringUtils.isNotBlank(bo.getName()), TStage::getName, bo.getName());
        lqw.eq(StringUtils.isNotBlank(bo.getStageMode()), TStage::getStageMode, bo.getStageMode());
        lqw.eq(bo.getVisualColIndex() != null, TStage::getVisualColIndex, bo.getVisualColIndex());
        lqw.eq(StringUtils.isNotBlank(bo.getRuleConfig()), TStage::getRuleConfig, bo.getRuleConfig());
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), TStage::getStatus, bo.getStatus());
        return lqw;
    }

    /**
     * 新增赛段流程
     *
     * @param bo 赛段流程
     * @return 新增后的赛段流程
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TStageVo insertByBo(TStageBo bo) {
        TStage add = MapstructUtils.convert(bo, TStage.class);

        // 归一化 ruleConfig:补齐与 teamCountStart/teamCountEnd 对应的配置字段
        normalizeRuleConfig(add);

        // 校验数据
        validateLinkIds(add);

        // 维护链表
        maintainChainOnInsert(add);

        baseMapper.insert(add);
        bo.setId(add.getId());

        // 更新链表中的相邻节点
        updateNeighborLinks(add);
        // 名单:新赛段有直接前驱时同步写入默认来源组(source=prev, ADVANCE, AUTO);
        // 先写名单再联动名额,使 syncAdvanceCountFromNext 能识别"目标是否多来源"
        rosterService.ensureRosterForStage(add);
        // 插入赛段后联动调整源赛段晋级名额:
        // 前驱赛段的 teamCountEnd 对齐到新赛段的 teamCountStart,
        // 保证「前段选多少人 = 后段收多少人」;具体人选仍由中间态(预排/顶替/GUEST)对接
        syncAdvanceCountFromNext(add.getPrevStageId(), add.getId());
        // 中间插入(A→Z→B):B 的 prev 已改为 Z,名单默认来源同步从 A 迁到 Z
        if (add.getNextStageId() != null) {
            rosterService.reconcileAfterLinkChange(add.getNextStageId());
        }

        return MapstructUtils.convert(add, TStageVo.class);
    }

    /**
     * 修改赛段流程
     *
     * @param bo 赛段流程
     * @return 修改后的赛段流程
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TStageVo updateByBo(TStageBo bo) {
        TStage update = MapstructUtils.convert(bo, TStage.class);

        // 归一化 ruleConfig:补齐与 teamCountStart/teamCountEnd 对应的配置字段
        normalizeRuleConfig(update);

        // 校验数据
        validateLinkIds(update);

        // 防悬挂状态:直接把赛段置为已结束时,要求所有场次必须已结算(与 completeStage 口径一致),
        // 避免出现「赛段 SETTLED + 二海/场次 GAMING」的前端误写状态
        if (update.getStatus() != null && StageConstants.STAGE_SETTLED.equals(update.getStatus())) {
            long unfinished = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, update.getId())
                .ne(TMatch::getStatus, StageConstants.MATCH_SETTLED));
            if (unfinished > 0) {
                throw new ServiceException("赛段仍有 {} 场未结算(如海选二海),不能直接标记为已结束,请通过「完成赛段」结算",
                    unfinished);
            }
        }

        // 获取旧数据，用于清理原链表连接
        TStage oldStage = baseMapper.selectById(update.getId());
        // 分圈保护:海选圈数只增不减、开始后锁定(前端已改为按钮加圈)
        validateAuditionCircleChange(update, oldStage);

        // 防呆:客户端提交的链表指针可能已过期(如删除中间赛段后本地未刷新),
        // 指向的赛段必须存在且属于同一赛事,否则回退旧链接,避免把悬空指针写回
        sanitizeLinkIds(update, oldStage);

        // 先清理旧的链表连接
        if (oldStage != null) {
            clearOldLinks(oldStage);
        }

        // 维护新链表
        maintainChainOnInsert(update);

        baseMapper.updateById(update);

        // 更新链表中的相邻节点
        updateNeighborLinks(update);
        // 若本赛段因改链成为新的入口(无直接前驱),由后端补建签到外部来源组
        if (update.getPrevStageId() == null) {
            rosterService.ensureRosterForStage(update);
        }
        // 改链后对账本赛段名单:清旧前驱默认组/入口签到组,按新 prev 补齐默认组
        rosterService.reconcileAfterLinkChange(update.getId());

        return MapstructUtils.convert(update, TStageVo.class);
    }

    /**
     * 海选分圈变更保护:
     * 1) 赛段开始/结束后分圈结构(圈数/每圈名额/每圈裁判)锁定,禁止修改;
     * 2) 规划中允许增加圈,不允许减少到少于当前已生成的圈场次数。
     */
    private void validateAuditionCircleChange(TStage update, TStage oldStage) {
        if (oldStage == null
            || !StageModeEnum.AUDITION.getCode().equals(oldStage.getStageMode())
            || StringUtils.isBlank(update.getRuleConfig())) {
            return;
        }
        RuleConfigHolder oldRc = RuleConfigParser.parse(oldStage.getRuleConfig());
        RuleConfigHolder newRc = RuleConfigParser.parse(update.getRuleConfig());
        if (oldRc == null || newRc == null) {
            return;
        }
        int oldCircles = oldRc.getCircles() == null ? 1 : Math.max(1, oldRc.getCircles());
        int newCircles = newRc.getCircles() == null ? oldCircles : Math.max(1, newRc.getCircles());
        boolean structureChanged = oldCircles != newCircles
            || !Objects.equals(oldRc.getCircleAdvanceCounts(), newRc.getCircleAdvanceCounts())
            || !Objects.equals(oldRc.getCircleRefereeIds(), newRc.getCircleRefereeIds());
        if (!structureChanged) {
            return;
        }
        boolean locked = StageConstants.STAGE_GAMING.equals(oldStage.getStatus())
            || StageConstants.STAGE_SETTLED.equals(oldStage.getStatus())
            || StageConstants.STAGE_DISCARD.equals(oldStage.getStatus());
        if (locked) {
            throw new ServiceException("赛段已开始或结束,分圈结构(圈数/每圈名额/每圈裁判)已锁定,不能修改");
        }
        if (newCircles < oldCircles) {
            throw new ServiceException("分圈数只能增加不能减少(当前 {} 圈);如某圈不再使用,请把该圈晋级名额设为 0", oldCircles);
        }
        // 圈数增加时,新增圈必须在原有圈之后追加;以实际已建场次为下限兜底
        long actualZones = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, oldStage.getId())
            .likeRight(TMatch::getDisplayZone, "ZONE-"));
        if (newCircles < actualZones) {
            throw new ServiceException("已实际建成 {} 个圈,分圈数不能再减少", actualZones);
        }
    }

    /**
     * 修正过期的链表指针:prev/next 指向的赛段不存在或跨赛事时,
     * 回退为旧赛段的链接(或置空),防止删除中间赛段后本地未刷新导致悬空引用写回。
     */
    private void sanitizeLinkIds(TStage update, TStage oldStage) {
        if (update.getPrevStageId() != null && !isValidLinkTarget(update.getPrevStageId(), update.getTournamentId())) {
            log.warn("赛段[{}] prevStageId={} 已失效(不存在或跨赛事),回退为旧链接 {}",
                update.getId(), update.getPrevStageId(),
                oldStage == null ? null : oldStage.getPrevStageId());
            update.setPrevStageId(oldStage == null ? null : oldStage.getPrevStageId());
        }
        if (update.getNextStageId() != null && !isValidLinkTarget(update.getNextStageId(), update.getTournamentId())) {
            log.warn("赛段[{}] nextStageId={} 已失效(不存在或跨赛事),回退为旧链接 {}",
                update.getId(), update.getNextStageId(),
                oldStage == null ? null : oldStage.getNextStageId());
            update.setNextStageId(oldStage == null ? null : oldStage.getNextStageId());
        }
    }

    /** 链表指针有效性:目标赛段存在,且(指定赛事时)属于同一赛事 */
    private boolean isValidLinkTarget(Long stageId, Long tournamentId) {
        if (stageId == null) {
            return true;
        }
        TStage target = baseMapper.selectById(stageId);
        return target != null
            && (tournamentId == null || Objects.equals(target.getTournamentId(), tournamentId));
    }

    /**
     * 归一化赛段 rule_config,保证与赛段权威字段(teamCountStart/teamCountEnd)一致。
     *
     * <p>rule_config 是前后端共用的配置契约(前端按 {@code knockout.advanceCount} 判定决赛等),
     * 但新增/修改赛段时客户端可能遗漏嵌套字段(尤其直接走 API 的场景),
     * 造成「赛段表字段与 rule_config 不一致 → 前端渲染/引擎解析异常」。
     * 此处仅做补全,不覆盖客户端已显式提供的值。</p>
     *
     * <ul>
     *   <li>KNOCKOUT:补齐 {@code knockout.teamsCount} / {@code knockout.advanceCount}(取赛段字段)</li>
     *   <li>AUDITION:补齐顶层 {@code advanceCount}(取赛段字段)</li>
     *   <li>通用:补齐顶层 {@code mode}</li>
     * </ul>
     */
    private void normalizeRuleConfig(TStage stage) {
        String mode = stage.getStageMode();
        if (StringUtils.isBlank(mode) || StringUtils.isBlank(stage.getRuleConfig())) {
            return;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> rc = mapper.readValue(stage.getRuleConfig(), Map.class);
            if (rc == null) {
                return;
            }
            boolean changed = false;
            if (StageModeEnum.KNOCKOUT.getCode().equals(mode)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> ko = (Map<String, Object>) rc.get("knockout");
                if (ko == null) {
                    ko = new HashMap<>();
                    rc.put("knockout", ko);
                }
                if (stage.getTeamCountStart() != null && ko.get("teamsCount") == null) {
                    ko.put("teamsCount", stage.getTeamCountStart());
                    changed = true;
                }
                if (stage.getTeamCountEnd() != null && ko.get("advanceCount") == null) {
                    ko.put("advanceCount", stage.getTeamCountEnd());
                    changed = true;
                }
            } else if (StageModeEnum.AUDITION.getCode().equals(mode)
                || StageModeEnum.RANK.getCode().equals(mode)) {
                if (stage.getTeamCountEnd() != null && rc.get("advanceCount") == null) {
                    rc.put("advanceCount", stage.getTeamCountEnd());
                    changed = true;
                }
            }
            if (rc.get("mode") == null) {
                rc.put("mode", mode);
                changed = true;
            }
            if (changed) {
                stage.setRuleConfig(mapper.writeValueAsString(rc));
            }
        } catch (Exception e) {
            log.warn("赛段[{}] ruleConfig 归一化失败,保留原配置: {}", stage.getId(), e.getMessage());
        }
    }

    /**
     * 校验链表ID字段的类型
     */
    private void validateLinkIds(TStage entity) {
        try {
            if (entity.getPrevStageId() != null) {
                // 确保是 Long 类型
                Long prevId = Long.valueOf(entity.getPrevStageId().toString());
                entity.setPrevStageId(prevId);
            }
            if (entity.getNextStageId() != null) {
                Long nextId = Long.valueOf(entity.getNextStageId().toString());
                entity.setNextStageId(nextId);
            }
            if (entity.getParentStageId() != null) {
                Long parentId = Long.valueOf(entity.getParentStageId().toString());
                entity.setParentStageId(parentId);
            }
        } catch (Exception e) {
            log.error("赛段链表ID类型错误: prevStageId={}, nextStageId={}, parentStageId={}",
                entity.getPrevStageId(), entity.getNextStageId(), entity.getParentStageId(), e);
            throw new RuntimeException("赛段链表ID必须是数值类型");
        }
    }

    /**
     * 插入节点时的链表维护
     */
    private void maintainChainOnInsert(TStage stage) {
        if (stage.getTournamentId() == null) {
            throw new RuntimeException("tournamentId不能为空");
        }

        Long prevId = stage.getPrevStageId();
        Long nextId = stage.getNextStageId();

        // 如果同时指定了 prev 和 next，检查它们是否原本相连
        if (prevId != null && nextId != null) {
            TStage prevStage = baseMapper.selectById(prevId);
            if (prevStage != null && !Objects.equals(prevStage.getNextStageId(), nextId)) {
                throw new RuntimeException(
                    String.format("指定的前驱节点[%d]和后继节点[%d]不相邻", prevId, nextId)
                );
            }
        }

        // 如果只指定了 prevId，自动查找 next
        if (prevId != null && nextId == null) {
            TStage prevStage = baseMapper.selectById(prevId);
            if (prevStage != null) {
                nextId = prevStage.getNextStageId();
                stage.setNextStageId(nextId);
            }
        }
        // 如果只指定了 nextId，自动查找 prev
        else if (nextId != null && prevId == null) {
            TStage nextStage = baseMapper.selectById(nextId);
            if (nextStage != null) {
                prevId = nextStage.getPrevStageId();
                stage.setPrevStageId(prevId);
            }
        }
    }

    /**
     * 更新相邻节点的指针
     */
    private void updateNeighborLinks(TStage stage) {
        Long newId = stage.getId();
        Long prevId = stage.getPrevStageId();
        Long nextId = stage.getNextStageId();

        // 更新前驱节点的 nextStageId
        if (prevId != null) {
            TStage prevStage = baseMapper.selectById(prevId);
            if (prevStage != null) {
                prevStage.setNextStageId(newId);
                baseMapper.updateById(prevStage);
            }
        }

        // 更新后继节点的 prevStageId
        if (nextId != null) {
            TStage nextStage = baseMapper.selectById(nextId);
            if (nextStage != null) {
                nextStage.setPrevStageId(newId);
                baseMapper.updateById(nextStage);
            }
        }
    }

    /**
     * 晋级名额联动(旧链表模型遗留):名单化后**显式停用**。
     *
     * <p>每个赛段的晋级名额/容量与名单来源组是独立配置:
     * 前驱赛段应输出多少人以来源组(晋级/落选/名次)为准,
     * 下游赛段容量由 teamCountStart 决定。若在建链时临时把前驱 teamCountEnd
     * 顶到下游容量,会在"先建链、后配复活/多来源"的流程里误改海选晋级名额,
     * 因此这里保留调用点但不再修改任何数据。</p>
     */
    private void syncAdvanceCountFromNext(Long prevId, Long nextId) {
        // no-op:名单流转由来源组与赛段配置决定
    }

    /** 名单行的全部内部来源组是否都已结算(STREAM/外部组视为就绪) */
    private boolean allGroupsResolved(List<TStageRosterGroupBo> groups) {
        for (TStageRosterGroupBo g : groups) {
            if (g.getSourceStageId() == null) {
                continue;
            }
            TStage src = baseMapper.selectById(g.getSourceStageId());
            if (src == null || !StageConstants.STAGE_SETTLED.equals(src.getStatus())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 清理旧的链表连接
     */
    private void clearOldLinks(TStage oldStage) {
        Long oldId = oldStage.getId();
        Long oldPrevId = oldStage.getPrevStageId();
        Long oldNextId = oldStage.getNextStageId();

        // 如果有前驱节点，将它的 nextStageId 指向我们的后继
        if (oldPrevId != null) {
            TStage prevStage = baseMapper.selectById(oldPrevId);
            if (prevStage != null && Objects.equals(prevStage.getNextStageId(), oldId)) {
                if (oldNextId == null) {
                    // updateById 会跳过 null 字段,清空链接需用显式 set
                    baseMapper.update(null, Wrappers.<TStage>lambdaUpdate()
                        .eq(TStage::getId, oldPrevId)
                        .set(TStage::getNextStageId, null));
                } else {
                    prevStage.setNextStageId(oldNextId);
                    baseMapper.updateById(prevStage);
                }
            }
        }

        // 如果有后继节点，将它的 prevStageId 指向我们的前驱
        if (oldNextId != null) {
            TStage nextStage = baseMapper.selectById(oldNextId);
            if (nextStage != null && Objects.equals(nextStage.getPrevStageId(), oldId)) {
                if (oldPrevId == null) {
                    // updateById 会跳过 null 字段,清空链接需用显式 set
                    baseMapper.update(null, Wrappers.<TStage>lambdaUpdate()
                        .eq(TStage::getId, oldNextId)
                        .set(TStage::getPrevStageId, null));
                } else {
                    nextStage.setPrevStageId(oldPrevId);
                    baseMapper.updateById(nextStage);
                }
            }
        }
    }

    /**
     * 校验并批量删除赛段流程信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        List<TStage> deletingStages = baseMapper.selectList(
            Wrappers.lambdaQuery(TStage.class).in(TStage::getId, ids));
        List<TStage> toDeleteStages = List.of();
        if(isValid){
            // 在删除前重新连接链表
            reconnectChainBeforeDelete(ids);
            // 删除赛段后联动调整源赛段晋级名额:
            // 前驱赛段的 teamCountEnd 对齐到存活后继赛段的 teamCountStart(即被删赛段的 next),
            // 例如删除 32→16 的 32强 后,海选晋级名额自动回到下一赛段容量
            toDeleteStages = baseMapper.selectList(
                Wrappers.lambdaQuery(TStage.class).in(TStage::getId, ids));
            for (TStage st : toDeleteStages) {
                syncAdvanceCountFromNext(st.getPrevStageId(), st.getNextStageId());
            }
        }
        // 级联删除关联数据:场次→轮次→打分/参赛明细,参赛方→成员,裁判关联
        List<Long> stageIds = ids.stream().map(Long::valueOf).toList();
        // 名单清理:删除以这些赛段为目标的人工覆盖;其余赛段名单摘除引用被删赛段的来源组
        if (!stageIds.isEmpty()) {
            overrideMapper.delete(Wrappers.<TStageRosterOverride>lambdaQuery()
                .in(TStageRosterOverride::getTargetStageId, stageIds));
            rosterService.removeSourceRefs(stageIds);
        }
        List<Long> matchIds = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .in(TMatch::getStageId, stageIds)
                .select(TMatch::getId))
            .stream().map(TMatch::getId).toList();
        if (!matchIds.isEmpty()) {
            List<Long> roundIds = matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                    .in(TMatchRound::getMatchId, matchIds)
                    .select(TMatchRound::getId))
                .stream().map(TMatchRound::getId).toList();
            if (!roundIds.isEmpty()) {
                roundScoreMapper.delete(Wrappers.<TRoundScore>lambdaQuery()
                    .in(TRoundScore::getRoundId, roundIds));
            }
            participantMapper.delete(Wrappers.<TMatchParticipant>lambdaQuery()
                .in(TMatchParticipant::getMatchId, matchIds));
            matchRoundMapper.delete(Wrappers.<TMatchRound>lambdaQuery()
                .in(TMatchRound::getMatchId, matchIds));
            matchMapper.delete(Wrappers.<TMatch>lambdaQuery()
                .in(TMatch::getStageId, stageIds));
        }
        List<Long> compIds = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .in(TCompetitor::getStageId, stageIds)
                .select(TCompetitor::getId))
            .stream().map(TCompetitor::getId).toList();
        if (!compIds.isEmpty()) {
            competitorMemberMapper.delete(Wrappers.<TCompetitorMember>lambdaQuery()
                .in(TCompetitorMember::getCompetitorId, compIds));
            competitorMapper.delete(Wrappers.<TCompetitor>lambdaQuery()
                .in(TCompetitor::getStageId, stageIds));
        }
        refereeStageMapper.delete(Wrappers.<TRefereeStage>lambdaQuery()
            .in(TRefereeStage::getStageId, stageIds));
        boolean deleted = baseMapper.deleteByIds(ids) > 0;
        // 删除后清扫:同赛事仍有 prev/next 指向已删赛段的,按被删节点的前后链接重连,
        // 兜底修复异常数据留下的悬空指针(正常链表下此处无命中)
        for (TStage st : toDeleteStages) {
            baseMapper.update(null, Wrappers.<TStage>lambdaUpdate()
                .eq(TStage::getTournamentId, st.getTournamentId())
                .eq(TStage::getPrevStageId, st.getId())
                .set(TStage::getPrevStageId, st.getPrevStageId()));
            baseMapper.update(null, Wrappers.<TStage>lambdaUpdate()
                .eq(TStage::getTournamentId, st.getTournamentId())
                .eq(TStage::getNextStageId, st.getId())
                .set(TStage::getNextStageId, st.getNextStageId()));
        }
        Set<Long> affectedTournamentIds = toDeleteStages.stream()
            .map(TStage::getTournamentId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        // 删除后为存活赛段补齐名单行:被删赛段的后续赛段若原名单整行失效,
        // 按新 prev 补默认组;新入口(无前驱)补签到 STREAM 组
        rosterService.ensureRosterForSurvivors(affectedTournamentIds);
        // 自动解绑引用被删赛段/其场次的场景组件,避免大屏刷新后报"赛段不存在"
        clearWidgetStageBindings(deletingStages, matchIds);
        return deleted;
    }

    /**
     * 删除赛段后清理 vis_widget 绑定:dataConfig 中的 stageId / matchId
     * 若指向被删赛段或其场次,统一置空(组件保留,显示为待绑定状态)。
     */
    private void clearWidgetStageBindings(List<TStage> stages, List<Long> matchIds) {
        if (stages == null || stages.isEmpty()) {
            return;
        }
        Set<Long> tournamentIds = stages.stream()
            .map(TStage::getTournamentId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (tournamentIds.isEmpty()) {
            return;
        }
        Set<String> removedRefs = new HashSet<>();
        stages.forEach(s -> {
            if (s.getId() != null) {
                removedRefs.add(String.valueOf(s.getId()));
            }
        });
        if (matchIds != null) {
            matchIds.forEach(id -> {
                if (id != null) {
                    removedRefs.add(String.valueOf(id));
                }
            });
        }
        List<TVisWidget> widgets = visWidgetMapper.selectList(Wrappers.<TVisWidget>lambdaQuery()
            .in(TVisWidget::getTournamentId, tournamentIds));
        if (widgets.isEmpty()) {
            return;
        }
        ObjectMapper mapper = new ObjectMapper();
        for (TVisWidget w : widgets) {
            if (StringUtils.isBlank(w.getDataConfig())) {
                continue;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> dc = mapper.readValue(w.getDataConfig(), Map.class);
                if (dc == null) {
                    continue;
                }
                boolean changed = false;
                for (String key : List.of("stageId", "targetStageId", "matchId")) {
                    Object val = dc.get(key);
                    if (val != null && removedRefs.contains(String.valueOf(val))) {
                        dc.put(key, null);
                        changed = true;
                    }
                }
                if (changed) {
                    TVisWidget upd = new TVisWidget();
                    upd.setId(w.getId());
                    upd.setDataConfig(mapper.writeValueAsString(dc));
                    visWidgetMapper.updateById(upd);
                    log.info("赛段删除联动:组件[{}](type={}) 已清空失效绑定 {}", w.getId(), w.getType(), dc);
                }
            } catch (Exception e) {
                log.warn("赛段删除联动:组件[{}] dataConfig 解析失败,跳过: {}", w.getId(), e.getMessage());
            }
        }
    }

    /**
     * 删除前重新连接链表
     *
     * @param ids 待删除的ID集合
     */
    private void reconnectChainBeforeDelete(Collection<Long> ids) {
        // 查询要删除的赛段
        List<TStage> toDeleteStages = baseMapper.selectList(
            Wrappers.lambdaQuery(TStage.class).in(TStage::getId, ids)
        );

        if (toDeleteStages.isEmpty()) {
            return;
        }

        // 对每个要删除的节点，重新连接其前后节点
        for (TStage stage : toDeleteStages) {
            Long prevId = stage.getPrevStageId();
            Long nextId = stage.getNextStageId();

            // 将前驱节点的 next 指向后继节点
            if (prevId != null) {
                TStage prevStage = baseMapper.selectById(prevId);
                if (prevStage != null && Objects.equals(prevStage.getNextStageId(), stage.getId())) {
                    if (nextId == null) {
                        // updateById 会跳过 null 字段,清空链接需用显式 set
                        baseMapper.update(null, Wrappers.<TStage>lambdaUpdate()
                            .eq(TStage::getId, prevId)
                            .set(TStage::getNextStageId, null));
                    } else {
                        prevStage.setNextStageId(nextId);
                        baseMapper.updateById(prevStage);
                    }
                }
            }

            // 将后继节点的 prev 指向前驱节点
            if (nextId != null) {
                TStage nextStage = baseMapper.selectById(nextId);
                if (nextStage != null && Objects.equals(nextStage.getPrevStageId(), stage.getId())) {
                    if (prevId == null) {
                        // updateById 会跳过 null 字段,清空链接需用显式 set
                        baseMapper.update(null, Wrappers.<TStage>lambdaUpdate()
                            .eq(TStage::getId, nextId)
                            .set(TStage::getPrevStageId, null));
                    } else {
                        nextStage.setPrevStageId(prevId);
                        baseMapper.updateById(nextStage);
                    }
                }
            }
        }
    }

    /**
     * 根据比赛ID获取第一个赛段
     *
     * @param tournamentId 比赛ID
     * @return 第一个赛段，不关心赛段状态（DRAFT/PENDING/GAMING/SETTLED 均返回）
     */
    @Override
    public TStageVo getFirstStageByTournamentId(Long tournamentId) {
        TStageBo query = new TStageBo();
        query.setTournamentId(tournamentId);
        List<TStageVo> all = this.queryList(query);
        all.removeIf(s -> StageConstants.STAGE_DISCARD.equals(s.getStatus()));
        if (all.isEmpty()) {
            log.warn("赛事[{}]未找到任何赛段(已排除 DISCARD)", tournamentId);
            return null;
        }
        log.debug("赛事[{}]找到{}个赛段:{}", tournamentId, all.size(),
            all.stream().map(s -> s.getId() + "(" + s.getStatus() + ",prev=" + s.getPrevStageId() + ")").toList());
        TStageVo chainHead = all.stream()
            .filter(s -> s.getPrevStageId() == null)
            .findFirst().orElse(null);
        if (chainHead != null) {
            return chainHead;
        }
        log.warn("赛事[{}]未找到链表头(prevStageId IS NULL),回退到ID最小的赛段", tournamentId);
        return all.get(0);
    }

    /**
     * 大屏赛程流转:按链表顺序返回全部赛段,并附带当前进行中赛段/场次
     */
    @Override
    public StageFlowVo getFlowByTournamentId(Long tournamentId) {
        StageFlowVo vo = new StageFlowVo();
        List<TStage> all = baseMapper.selectList(Wrappers.<TStage>lambdaQuery()
            .eq(TStage::getTournamentId, tournamentId)
            .ne(TStage::getStatus, StageConstants.STAGE_DISCARD)
            .orderByAsc(TStage::getId));
        if (all.isEmpty()) {
            vo.setStages(List.of());
            return vo;
        }

        // 按 prev/next 链表排序,断链时按 id 兜底补齐
        Map<Long, TStage> byId = new HashMap<>();
        for (TStage s : all) {
            byId.put(s.getId(), s);
        }
        List<TStage> chain = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        TStage head = all.stream().filter(s -> s.getPrevStageId() == null).findFirst().orElse(all.get(0));
        TStage cur = head;
        while (cur != null && visited.add(cur.getId())) {
            chain.add(cur);
            cur = byId.get(cur.getNextStageId());
        }
        for (TStage s : all) {
            if (!visited.contains(s.getId())) {
                chain.add(s);
            }
        }

        vo.setStages(chain.stream().map(s -> {
            StageFlowVo.StageFlowItem item = new StageFlowVo.StageFlowItem();
            item.setId(s.getId());
            item.setName(s.getName());
            item.setStageMode(s.getStageMode());
            item.setStatus(s.getStatus());
            item.setTeamCountStart(s.getTeamCountStart() == null ? null : Long.valueOf(s.getTeamCountStart()));
            item.setTeamCountEnd(s.getTeamCountEnd() == null ? null : Long.valueOf(s.getTeamCountEnd()));
            item.setIsInitialized(s.getIsInitialized());
            return item;
        }).toList());

        // 当前进行中赛段:链上第一个 GAMING
        TStage current = chain.stream()
            .filter(s -> StageConstants.STAGE_GAMING.equals(s.getStatus()))
            .findFirst().orElse(null);
        if (current == null) {
            return vo;
        }
        vo.setCurrentStageId(current.getId());

        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, current.getId())
            .eq(TMatch::getStatus, StageConstants.MATCH_GAMING)
            .orderByAsc(TMatch::getId));
        if (matches.isEmpty()) {
            return vo;
        }
        TMatch match = matches.get(0);
        StageFlowVo.StageFlowMatch m = new StageFlowVo.StageFlowMatch();
        m.setId(match.getId());
        m.setName(match.getName());
        m.setStatus(match.getStatus());
        m.setMatchMode(match.getMatchMode());
        vo.setCurrentMatch(m);

        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, match.getId())
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
        // 批量取参赛方首位成员的照片(competitor -> member -> player.avatar)
        List<Long> compIds = parts.stream()
            .map(TMatchParticipant::getCompetitorId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        Map<Long, String> avatarMap = new HashMap<>();
        if (!compIds.isEmpty()) {
            List<TCompetitorMember> members = competitorMemberMapper.selectList(Wrappers.<TCompetitorMember>lambdaQuery()
                .in(TCompetitorMember::getCompetitorId, compIds));
            if (!members.isEmpty()) {
                List<Long> playerIds = members.stream()
                    .map(TCompetitorMember::getPlayerId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
                Map<Long, String> playerAvatar = playerMapper.selectList(Wrappers.<TPlayer>lambdaQuery()
                        .in(TPlayer::getId, playerIds))
                    .stream()
                    .filter(p -> StringUtils.isNotBlank(p.getAvatar()))
                    .collect(Collectors.toMap(TPlayer::getId, TPlayer::getAvatar, (a, b) -> a));
                for (TCompetitorMember mem : members) {
                    String av = playerAvatar.get(mem.getPlayerId());
                    if (StringUtils.isNotBlank(av)) {
                        avatarMap.putIfAbsent(mem.getCompetitorId(), av);
                    }
                }
            }
        }
        vo.setCurrentMatchParticipants(parts.stream().map(p -> {
            StageFlowVo.StageFlowParticipant sp = new StageFlowVo.StageFlowParticipant();
            sp.setCompetitorId(p.getCompetitorId());
            sp.setAvatar(avatarMap.get(p.getCompetitorId()));
            sp.setDisplaySlotIndex(p.getDisplaySlotIndex());
            sp.setScoreValue(p.getScoreValue());
            sp.setRankInMatch(p.getRankInMatch());
            sp.setOutcomeStatus(p.getOutcomeStatus());
            if (p.getCompetitorId() != null) {
                var comp = competitorMapper.selectById(p.getCompetitorId());
                sp.setCompetitorName(comp != null ? comp.getName() : ("选手 " + p.getCompetitorId()));
            } else {
                sp.setCompetitorName("待定");
            }
            return sp;
        }).toList());
        return vo;
    }

    /**
     * 下一赛段对战树预排:优先返回本赛段真实参赛方(GENERATED);
     * 未生成时取上一赛段胜者(含未最终确认的已结算胜者),按 finalRank 顺序作为预排种子。
     */
    @Override
    public PreBracketVo getPreBracket(Long stageId) {
        TStage stage = baseMapper.selectById(stageId);
        if (stage == null) {
            throw new ServiceException("赛段不存在");
        }
        PreBracketVo vo = new PreBracketVo();
        vo.setStageId(stage.getId());
        vo.setStageName(stage.getName());
        vo.setStageMode(stage.getStageMode());

        // 本赛段已有参赛方:已初始化或已写入晋级者时视为真实名单(GENERATED);
        // 仅提前加入 GUEST(未初始化、未确认晋级)时仍进入 PREVIEW,与上一赛段晋级者合并展示
        List<TCompetitor> own = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stage.getId())
            .orderByAsc(TCompetitor::getSeedRank)
            .orderByAsc(TCompetitor::getId));
        boolean initialized = Long.valueOf(1L).equals(stage.getIsInitialized());
        boolean hasConfirmedAdvancer = own.stream().anyMatch(c -> c.getSourceCompetitorId() != null);
        if (!own.isEmpty() && (initialized || hasConfirmedAdvancer)) {
            vo.setStatus("GENERATED");
            vo.setSeededCompetitors(own.stream().map(c -> toPreSeed(c, (long) own.indexOf(c) + 1, null)).toList());
            return vo;
        }

        TStage prev = resolvePrevStage(stage);
        if (prev == null) {
            vo.setStatus("NO_PREV");
            return vo;
        }
        // 预排用于 淘汰赛/海选/排名赛 → 淘汰赛;小组等其他赛段间不做预排
        if (!StageModeEnum.KNOCKOUT.getCode().equals(stage.getStageMode())
            || (!StageModeEnum.KNOCKOUT.getCode().equals(prev.getStageMode())
                && !StageModeEnum.AUDITION.getCode().equals(prev.getStageMode())
                && !StageModeEnum.RANK.getCode().equals(prev.getStageMode()))) {
            vo.setStatus("UNSUPPORTED");
            return vo;
        }

        // 预排候选来自目标名单(来源组):AUTO 组按优先级并集、组配额截断,
        // 分圈海选整单晋级沿用 apply 的圈内名次轮转排序——不再只读"链上上一赛段 ADVANCE"
        List<TCompetitor> advancers = rosterService.previewRoster(stage.getId());
        if (advancers.isEmpty()) {
            // 上一赛段尚无晋级者:仅返回已提前加入的参赛方(如 GUEST)
            if (!own.isEmpty()) {
                vo.setSeededCompetitors(own.stream()
                    .map(c -> toPreSeed(c, c.getSeedRank(), null)).toList());
            }
            vo.setStatus("WAIT_PREV");
            return vo;
        }

        // 场次位计数(用于 BYE/TBD 展示)仍按链上上一赛段场次;来源场次名取预排候选的真实来源赛段
        Map<Long, String> sourceMatch = new HashMap<>();
        Map<Integer, Integer> matchPosCount = new HashMap<>();
        List<TMatch> prevMatches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, prev.getId()));
        if (!prevMatches.isEmpty()) {
            List<Long> matchIds = prevMatches.stream().map(TMatch::getId).toList();
            List<TMatchParticipant> parts = participantMapper.selectList(
                Wrappers.<TMatchParticipant>lambdaQuery().in(TMatchParticipant::getMatchId, matchIds));
            Map<Long, Long> partCountByMatch = parts.stream()
                .collect(Collectors.groupingBy(TMatchParticipant::getMatchId, Collectors.counting()));
            for (TMatch m : prevMatches) {
                if (m.getDisplayRow() != null) {
                    matchPosCount.put(m.getDisplayRow().intValue() + 1,
                        partCountByMatch.getOrDefault(m.getId(), 0L).intValue());
                }
            }
        }
        List<Long> sourceStageIds = advancers.stream()
            .map(TCompetitor::getStageId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (!sourceStageIds.isEmpty()) {
            List<TMatch> sourceMatches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .in(TMatch::getStageId, sourceStageIds));
            List<Long> sourceMatchIds = sourceMatches.stream().map(TMatch::getId).toList();
            if (!sourceMatchIds.isEmpty()) {
                Map<Long, String> matchNameById = sourceMatches.stream()
                    .collect(Collectors.toMap(TMatch::getId, TMatch::getName));
                participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                        .in(TMatchParticipant::getMatchId, sourceMatchIds)
                        .isNotNull(TMatchParticipant::getCompetitorId))
                    .forEach(p -> sourceMatch.putIfAbsent(p.getCompetitorId(),
                        matchNameById.get(p.getMatchId())));
            }
        }

        // 按预排顺序填充种子空位:跳过场次留空,后续候选不抢占已占位置
        int totalSlots = stage.getTeamCountStart() != null && stage.getTeamCountStart() > 0
            ? stage.getTeamCountStart().intValue()
            : Math.max(advancers.size(), prevMatches.size());
        totalSlots = Math.max(1, totalSlots);
        PreBracketVo.PreSeed[] seedArr = new PreBracketVo.PreSeed[totalSlots];
        // 已提前加入的参赛方(通常为 GUEST)先按种子位占位;晋级者只填充剩余空位,
        // 超出计划规模的晋级者不进入(GUEST 顶替前几名种子,原晋级者按 finalRank 顺序顺延)
        for (TCompetitor g : own) {
            long r = g.getSeedRank() != null ? g.getSeedRank() : 0L;
            if (r >= 1L && r <= totalSlots) {
                seedArr[(int) (r - 1L)] = toPreSeed(g, r, null);
            }
        }
        // 晋级者严格按 finalRank 顺序填充空位:GUEST 占位后顺延,超出计划规模的晋级者不进入
        // (淘汰赛承接胜者时 finalRank=场次位置,顺序填充与位置保留等价)
        int cursor = 0;
        for (TCompetitor c : advancers) {
            while (cursor < seedArr.length && seedArr[cursor] != null) {
                cursor++;
            }
            if (cursor >= seedArr.length) {
                break; // 名额已满
            }
            PreBracketVo.PreSeed s = toPreSeed(c, null, sourceMatch.get(c.getId()));
            s.setSeedRank((long) (cursor + 1));
            seedArr[cursor] = s;
            cursor++;
        }
        List<PreBracketVo.PreSeed> seeds = new ArrayList<>();
        for (PreBracketVo.PreSeed s : seedArr) {
            if (s != null) {
                seeds.add(s);
            }
        }
    vo.setSeededCompetitors(seeds);
        vo.setStatus("PREVIEW");

        List<PreBracketVo.PrePair> pairList = new ArrayList<>();
        // 配对模式:与生成对阵同一口径(显式配置优先;种子来自名次则默认种子摆位)
        RuleConfigHolder stageRc = RuleConfigParser.parse(stage.getRuleConfig());
        String configuredPairing = stageRc != null && stageRc.getKnockout() != null
            ? stageRc.getKnockout().getPairingMode() : null;
        String pairingMode = PairingModeResolver.resolve(
            configuredPairing,
            seedsFromRanking(stage.getId()),
            PairingModeResolver.prevIsRanking(prev));
        if ("SEED".equalsIgnoreCase(pairingMode)) {
            int bracketSize = Math.max(2, nextPowerOfTwo(seedArr.length));
            int[] layout = KnockoutGenerator.seedLayout(bracketSize);
            int pairCount = Math.max(1, bracketSize / 2);
            int half = (int) Math.ceil(pairCount / 2.0);
            for (int i = 0; i < pairCount; i++) {
                PreBracketVo.PrePair p = new PreBracketVo.PrePair();
                p.setPosition(i + 1);
                p.setZone(i < half ? "LEFT" : "RIGHT");
                // SEED 标准种子摆位(与 KnockoutGenerator.SEED_LAYOUT 一致):第 i 场 = layout[2i] vs layout[2i+1]
                int leftPos = layout[2 * i];
                int rightPos = layout[2 * i + 1];
                p.setLeft(leftPos <= seedArr.length ? seedArr[leftPos - 1] : null);
                p.setRight(rightPos <= seedArr.length ? seedArr[rightPos - 1] : null);
                p.setLeftStatus(sideStatus(p.getLeft(), matchPosCount, leftPos));
                p.setRightStatus(sideStatus(p.getRight(), matchPosCount, rightPos));
                pairList.add(p);
            }
        } else {
            int pairs = Math.max(1, (int) Math.ceil(seedArr.length / 2.0));
            int half = (int) Math.ceil(pairs / 2.0);
            for (int i = 0; i < pairs; i++) {
                PreBracketVo.PrePair p = new PreBracketVo.PrePair();
                p.setPosition(i + 1);
                p.setZone(i < half ? "LEFT" : "RIGHT");
                int leftPos = i * 2 + 1;
                int rightPos = i * 2 + 2;
                p.setLeft(leftPos <= seedArr.length ? seedArr[leftPos - 1] : null);
                p.setRight(rightPos <= seedArr.length ? seedArr[rightPos - 1] : null);
                p.setLeftStatus(sideStatus(p.getLeft(), matchPosCount, leftPos));
                p.setRightStatus(sideStatus(p.getRight(), matchPosCount, rightPos));
                pairList.add(p);
            }
        }
        vo.setPairs(pairList);
        return vo;
    }

    /**
     * 预排空位状态:该位置有胜者=WINNER;对应上一场次存在且有人=待定(TBD);否则轮空(BYE)。
     */
    private String sideStatus(PreBracketVo.PreSeed seed, Map<Integer, Integer> matchPosCount, int pos) {
        if (seed != null) {
            return "WINNER";
        }
        Integer cnt = matchPosCount.get(pos);
        return cnt != null && cnt > 0 ? "TBD" : "BYE";
    }

    private static int nextPowerOfTwo(int v) {
        int p = 1;
        while (p < v) {
            p <<= 1;
        }
        return p;
    }

    /** 本赛段名单的来源里是否有海选/排名赛(决定默认是否头尾交叉配对) */
    private boolean seedsFromRanking(Long stageId) {
        TStage stage = baseMapper.selectById(stageId);
        if (stage == null) {
            return false;
        }
        return PairingModeResolver.seedsFromRanking(
            StageRosterGroupCodec.parse(stage.getRosterConfigJson()),
            baseMapper::selectById);
    }

    private PreBracketVo.PreSeed toPreSeed(TCompetitor c, Long seedRank, String sourceMatchName) {
        PreBracketVo.PreSeed s = new PreBracketVo.PreSeed();
        s.setCompetitorId(c.getId());
        s.setName(c.getName());
        s.setSeedRank(seedRank);
        s.setSourceCompetitorId(c.getSourceCompetitorId());
        s.setSourceMatchName(sourceMatchName);
        return s;
    }

    /** 通过 prevStageId 或链表反查上一赛段 */
    private TStage resolvePrevStage(TStage stage) {
        if (stage.getPrevStageId() != null) {
            TStage prev = baseMapper.selectById(stage.getPrevStageId());
            if (prev != null) {
                return prev;
            }
            // prevStageId 悬空(指向已删除赛段)时,按 nextStageId 反向反查兜底
        }
        List<TStage> all = baseMapper.selectList(Wrappers.<TStage>lambdaQuery()
            .eq(TStage::getTournamentId, stage.getTournamentId())
            .ne(TStage::getStatus, StageConstants.STAGE_DISCARD));
        return all.stream().filter(s -> stage.getId().equals(s.getNextStageId())).findFirst().orElse(null);
    }
}

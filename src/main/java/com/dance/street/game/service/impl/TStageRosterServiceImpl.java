package com.dance.street.game.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TCompetitorMember;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TPlayer;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TStageRosterOverride;
import com.dance.street.game.domain.bo.TStageRosterBo;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.bo.TStageRosterOrderBo;
import com.dance.street.game.domain.bo.TStageRosterOverrideBo;
import com.dance.street.game.domain.vo.RosterCandidatesVo;
import com.dance.street.game.domain.vo.RosterPreviewItemVo;
import com.dance.street.game.domain.vo.RosterPreviewVo;
import com.dance.street.game.domain.vo.TCompetitorVo;
import com.dance.street.game.domain.vo.TStageRosterOverrideVo;
import com.dance.street.game.domain.vo.TStageRosterVo;
import com.dance.street.game.engine.common.RosterConstants;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.StageRosterGroupCodec;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TCompetitorMemberMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TPlayerMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TStageRosterOverrideMapper;
import com.dance.street.game.service.ITStageRosterService;
import com.dance.street.game.service.TournamentEventNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 名单服务实现:名单 = 赛段属性(t_stage.roster_config_json + roster_applied/skipped),
 * 规则(groups) + 人工覆盖(override) + 快照(apply 物化到参赛行)。
 *
 * @author duane
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TStageRosterServiceImpl implements ITStageRosterService {

    private final TStageMapper stageMapper;
    private final TCompetitorMapper competitorMapper;
    private final TCompetitorMemberMapper competitorMemberMapper;
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TStageRosterOverrideMapper overrideMapper;
    private final TPlayerMapper playerMapper;
    private final TournamentEventNotifier tournamentEventNotifier;

    // ------------------------------------------------------------------
    // 名单 = stage 属性的读写
    // ------------------------------------------------------------------

    private List<TStageRosterGroupBo> groupsOf(TStage stage) {
        try {
            return StageRosterGroupCodec.parse(stage.getRosterConfigJson());
        } catch (IllegalArgumentException e) {
            throw new ServiceException("赛段[{}]名单来源组配置损坏: {}", stage.getId(), e.getMessage());
        }
    }

    private boolean isApplied(TStage stage) {
        return Long.valueOf(1L).equals(stage.getRosterApplied());
    }

    private boolean isSkipped(TStage stage) {
        return Long.valueOf(1L).equals(stage.getRosterSkipped());
    }

    private boolean isLocked(TStage stage) {
        return isApplied(stage) || isSkipped(stage);
    }

    private String groupsJson(List<TStageRosterGroupBo> groups) {
        return StageRosterGroupCodec.write(groups);
    }

    private void saveGroups(TStage stage, List<TStageRosterGroupBo> groups, Long applied, Long skipped) {
        TStage upd = new TStage();
        upd.setId(stage.getId());
        upd.setRosterConfigJson(groupsJson(groups));
        upd.setRosterApplied(applied);
        upd.setRosterSkipped(skipped);
        stageMapper.updateById(upd);
    }

    private void saveGroups(TStage stage, List<TStageRosterGroupBo> groups) {
        saveGroups(stage, groups, stage.getRosterApplied(), stage.getRosterSkipped());
    }

    private TStage mustRosterStage(Long stageId, boolean editable) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null) {
            throw new ServiceException("赛段不存在");
        }
        if (editable && isLocked(stage)) {
            throw new ServiceException("赛段名单已装配完成或已跳过,请先重置该赛段再调整");
        }
        return stage;
    }

    /** 名单展示状态:CONFIRMED/SKIPPED/READY/WAIT_SOURCE(推导,不再持久化) */
    private String stateOf(TStage stage) {
        if (isApplied(stage)) {
            return RosterConstants.ROSTER_CONFIRMED;
        }
        if (isSkipped(stage)) {
            return RosterConstants.ROSTER_SKIPPED;
        }
        return readyByGroups(groupsOf(stage)) ? RosterConstants.ROSTER_READY : RosterConstants.ROSTER_WAIT_SOURCE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ensureRosterForStage(TStage stage) {
        if (stage == null || stage.getId() == null) {
            return;
        }
        // 以库内最新状态为准:调用方可能传入改链前的旧 stage 对象
        TStage fresh = stageMapper.selectById(stage.getId());
        if (fresh == null) {
            return;
        }
        boolean entry = fresh.getPrevStageId() == null;
        TStageRosterGroupBo defaultGroup = entry ? externalGroup() : defaultGroup(fresh.getPrevStageId());
        List<TStageRosterGroupBo> groups = groupsOf(fresh);
        boolean exists = groups.stream().anyMatch(g -> sameGroup(g, defaultGroup));
        if (!exists) {
            groups.add(defaultGroup);
            saveGroups(fresh, groups);
            log.info("赛段[{}]名单已写入默认来源组[{}]", stage.getId(),
                entry ? "签到(STREAM)" : "上一赛段·晋级·AUTO");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reconcileAfterLinkChange(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null || StageConstants.STAGE_DISCARD.equals(stage.getStatus())) {
            return;
        }
        List<TStageRosterGroupBo> kept = new ArrayList<>();
        boolean changed = false;
        boolean streamSeen = false;
        Long prevId = stage.getPrevStageId();
        boolean entry = prevId == null;
        for (TStageRosterGroupBo g : groupsOf(stage)) {
            boolean stream = RosterConstants.FILL_STREAM.equals(g.getFillMode())
                || (g.getSourceStageId() == null && g.getFillMode() == null);
            if (entry) {
                if (stream) {
                    if (streamSeen) {
                        changed = true;
                        continue;
                    }
                    streamSeen = true;
                } else {
                    changed = true;
                    continue;
                }
            } else {
                if (stream) {
                    changed = true;
                    continue;
                }
                // 自动生成的「上一赛段·晋级」默认组:只有它引用的不是当前直接前驱时才清掉。
                // (旧实现按"传进来的旧前驱"判断,导致未改链的普通保存也会误判为需要重建名单)
                if (isGeneratedDefault(g) && !Objects.equals(g.getSourceStageId(), prevId)) {
                    changed = true;
                    continue;
                }
            }
            kept.add(g);
        }
        // 只要有任意来源组引用新的直接前驱,链式衔接即成立(不要求必须是"整单晋级"默认组)
        boolean hasCurrentDefault = entry
            ? kept.stream().anyMatch(g -> RosterConstants.FILL_STREAM.equals(g.getFillMode()))
            : kept.stream().anyMatch(g -> Objects.equals(g.getSourceStageId(), prevId));
        // 链路没有实际变化(例如只是保存赛段/生命周期状态回写)= 名单无需对账,
        // 直接返回,不受"名单已装配"锁定影响。
        if (!changed && hasCurrentDefault) {
            return;
        }
        // 确实要改名单时才要求未装配(装配后规则冻结,需先重置)
        if (isLocked(stage)) {
            throw new ServiceException("赛段[{}]名单已装配,请先重置该赛段后再调整赛段链路",
                stage.getName());
        }
        if (changed) {
            saveGroups(stage, kept);
        }
        if (!hasCurrentDefault) {
            ensureRosterForStage(stage);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ensureRosterForSurvivors(Collection<Long> tournamentIds) {
        if (tournamentIds == null || tournamentIds.isEmpty()) {
            return;
        }
        for (TStage stage : stageMapper.selectList(Wrappers.<TStage>lambdaQuery()
            .in(TStage::getTournamentId, tournamentIds)
            .orderByAsc(TStage::getId))) {
            if (StageConstants.STAGE_DISCARD.equals(stage.getStatus())) {
                continue;
            }
            if (stage.getRosterConfigJson() == null || stage.getRosterConfigJson().isBlank()) {
                ensureRosterForStage(stage);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeSourceRefs(Collection<Long> deletedStageIds) {
        if (deletedStageIds == null || deletedStageIds.isEmpty()) {
            return;
        }
        Set<Long> deleted = new HashSet<>(deletedStageIds);
        for (TStage stage : stageMapper.selectList(Wrappers.<TStage>lambdaQuery()
            .isNotNull(TStage::getRosterConfigJson))) {
            if (deleted.contains(stage.getId())) {
                continue;
            }
            List<TStageRosterGroupBo> groups = groupsOf(stage);
            int before = groups.size();
            groups.removeIf(g -> g.getSourceStageId() != null && deleted.contains(g.getSourceStageId()));
            if (groups.size() == before) {
                continue;
            }
            saveGroups(stage, groups);
            log.info("赛段删除:摘除赛段[{}]名单中引用已删赛段的来源组,剩余 {} 组",
                stage.getId(), groups.size());
        }
    }

    private boolean isGeneratedDefault(TStageRosterGroupBo g) {
        return g.getSourceStageId() != null
            && (g.getFillMode() == null || RosterConstants.FILL_AUTO.equals(g.getFillMode()))
            && OutcomeStatusEnum.ADVANCE.getCode().equals(g.getResultFilter())
            && g.getZone() == null && g.getRound() == null
            && g.getRankStart() == null && g.getRankEnd() == null
            && g.getScoreMin() == null && g.getScoreMax() == null
            && !Boolean.TRUE.equals(g.getRankByZone());
    }

    private TStageRosterGroupBo externalGroup() {
        TStageRosterGroupBo g = new TStageRosterGroupBo();
        g.setSourceStageId(null);
        g.setResultFilter(RosterConstants.FILTER_ANY);
        g.setFillMode(RosterConstants.FILL_STREAM);
        g.setQuota(0);
        g.setPriority(RosterConstants.EXTERNAL_ROSTER_PRIORITY);
        return g;
    }

    private TStageRosterGroupBo defaultGroup(Long sourceStageId) {
        TStageRosterGroupBo g = new TStageRosterGroupBo();
        g.setSourceStageId(sourceStageId);
        g.setResultFilter(OutcomeStatusEnum.ADVANCE.getCode());
        g.setFillMode(RosterConstants.FILL_AUTO);
        g.setQuota(0);
        g.setPriority(1);
        return g;
    }

    private boolean sameGroup(TStageRosterGroupBo a, TStageRosterGroupBo b) {
        return Objects.equals(a.getSourceStageId(), b.getSourceStageId())
            && Objects.equals(a.getResultFilter(), b.getResultFilter())
            && Objects.equals(a.getZone(), b.getZone())
            && Objects.equals(a.getRound(), b.getRound())
            && Objects.equals(a.getRankStart(), b.getRankStart())
            && Objects.equals(a.getRankEnd(), b.getRankEnd())
            && Objects.equals(a.getRankByZone(), b.getRankByZone())
            && Objects.equals(a.getScoreMin(), b.getScoreMin())
            && Objects.equals(a.getScoreMax(), b.getScoreMax())
            && Objects.equals(a.getOrderBy(), b.getOrderBy());
    }

    /**
     * 链式默认衔接:名单必须至少有一条引用"直接前驱"的来源组;
     * 没有配置出口(或删光了引用前驱的组)时,自动补"上一赛段·晋级·AUTO"。
     * 入口赛段(无前驱)则保证存在签到来源组。
     */
    private void ensurePrevChainDefault(TStage stage, List<TStageRosterGroupBo> groups) {
        Long prevId = stage.getPrevStageId();
        if (prevId == null) {
            boolean hasStream = groups.stream().anyMatch(g ->
                RosterConstants.FILL_STREAM.equals(g.getFillMode())
                    || (g.getSourceStageId() == null && g.getFillMode() == null));
            if (!hasStream) {
                groups.add(externalGroup());
                log.info("入口赛段[{}]未配置来源,已补签到来源组", stage.getId());
            }
            return;
        }
        boolean hasPrevRef = groups.stream()
            .anyMatch(g -> Objects.equals(g.getSourceStageId(), prevId));
        if (!hasPrevRef) {
            groups.add(defaultGroup(prevId));
            log.info("赛段[{}]未配置出口,已自动补回链式默认衔接:上一赛段[{}]晋级者进入本赛段",
                stage.getId(), prevId);
        }
    }

    /** 名单就绪度(纯函数):全部内部来源组已结算 */
    private boolean readyByGroups(List<TStageRosterGroupBo> groups) {
        if (groups.isEmpty()) {
            return false;
        }
        for (TStageRosterGroupBo g : groups) {
            if (RosterConstants.FILL_STREAM.equals(g.getFillMode()) || g.getSourceStageId() == null) {
                continue;
            }
            TStage src = stageMapper.selectById(g.getSourceStageId());
            if (src == null || !StageConstants.STAGE_SETTLED.equals(src.getStatus())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<TStageRosterVo> listByTarget(Long targetStageId) {
        TStage stage = stageMapper.selectById(targetStageId);
        if (stage == null) {
            return List.of();
        }
        return List.of(toVo(stage));
    }

    @Override
    public List<TStageRosterVo> listBySource(Long sourceStageId) {
        List<TStageRosterVo> out = new ArrayList<>();
        for (TStage stage : stageMapper.selectList(Wrappers.<TStage>lambdaQuery()
            .isNotNull(TStage::getRosterConfigJson)
            .orderByAsc(TStage::getId))) {
            if (groupsOf(stage).stream().anyMatch(g ->
                Objects.equals(g.getSourceStageId(), sourceStageId))) {
                out.add(toVo(stage));
            }
        }
        return out;
    }

    private TStageRosterVo toVo(TStage stage) {
        TStageRosterVo vo = new TStageRosterVo();
        vo.setId(stage.getId());
        vo.setTournamentId(stage.getTournamentId());
        vo.setTargetStageId(stage.getId());
        List<TStageRosterGroupBo> groups = groupsOf(stage);
        vo.setGroups(groups);
        vo.setState(stateOf(stage));
        if (!groups.isEmpty()) {
            TStageRosterGroupBo first = groups.get(0);
            vo.setSourceStageId(first.getSourceStageId());
            vo.setResultFilter(first.getResultFilter());
            vo.setQuota(first.getQuota());
            vo.setFillMode(first.getFillMode());
            vo.setPriority(first.getPriority());
            vo.setRankBandStart(first.getRankStart());
            vo.setRankBandEnd(first.getRankEnd());
            vo.setZoneFilter(first.getZone());
            vo.setRoundFilter(first.getRound());
            vo.setScoreMin(first.getScoreMin());
            vo.setScoreMax(first.getScoreMax());
        }
        vo.setOverrides(overridesOf(stage.getId()).stream()
            .map(o -> MapstructUtils.convert(o, TStageRosterOverrideVo.class))
            .toList());
        return vo;
    }

    // ------------------------------------------------------------------
    // 覆盖层
    // ------------------------------------------------------------------

    private List<TStageRosterOverride> overridesOf(Long stageId) {
        return overrideMapper.selectList(Wrappers.<TStageRosterOverride>lambdaQuery()
            .eq(TStageRosterOverride::getTargetStageId, stageId)
            .orderByAsc(TStageRosterOverride::getId));
    }

    @Override
    public List<TStageRosterOverrideVo> listOverrides(Long stageId) {
        return overridesOf(stageId).stream()
            .map(o -> MapstructUtils.convert(o, TStageRosterOverrideVo.class))
            .toList();
    }

    private TStage assertOverrideEditable(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null) {
            throw new ServiceException("赛段不存在");
        }
        if (isLocked(stage)) {
            throw new ServiceException("名单已装配完成或已跳过,请先重置目标赛段再调整人工覆盖");
        }
        if (!StageConstants.STAGE_DRAFT.equals(stage.getStatus())
            && !StageConstants.STAGE_PENDING.equals(stage.getStatus())) {
            throw new ServiceException("仅规划中(DRAFT/PENDING)的赛段可编辑人工覆盖,当前: {}",
                stage.getStatus());
        }
        if (Long.valueOf(1L).equals(stage.getIsInitialized())) {
            throw new ServiceException("赛段已初始化,名单已锁定,无法调整人工覆盖");
        }
        return stage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TStageRosterOverrideVo addOverride(Long stageId, TStageRosterOverrideBo bo) {
        TStage target = assertOverrideEditable(stageId);
        if (bo == null || bo.getOp() == null) {
            throw new ServiceException("请指定覆盖操作(op)");
        }
        String op = bo.getOp();
        if (!List.of(RosterConstants.OVERRIDE_ADD_SOURCE, RosterConstants.OVERRIDE_ADD_GUEST,
            RosterConstants.OVERRIDE_REMOVE, RosterConstants.OVERRIDE_SEED).contains(op)) {
            throw new ServiceException("不支持的覆盖操作: {}", op);
        }
        validateOverrideSource(stageId, op, bo);
        if (RosterConstants.OVERRIDE_ADD_GUEST.equals(op)) {
            validateGuestProfile(target, bo);
        }
        if (bo.getSeedRank() != null && !RosterConstants.OVERRIDE_REMOVE.equals(op)) {
            validateSeedWithinPlan(target, bo.getSeedRank());
        }
        // 去重口径:引用源行的按源行;外卡按关联选手(有关联)或姓名,允许同一名单加多个不同外卡
        boolean guestOp = RosterConstants.OVERRIDE_ADD_GUEST.equals(op);
        boolean guestByPlayer = guestOp && bo.getPlayerId() != null && bo.getPlayerId() > 0L;
        long existed = overrideMapper.selectCount(Wrappers.<TStageRosterOverride>lambdaQuery()
            .eq(TStageRosterOverride::getTargetStageId, stageId)
            .eq(TStageRosterOverride::getOp, op)
            .eq(!guestOp, TStageRosterOverride::getSourceCompetitorId, bo.getSourceCompetitorId())
            .eq(guestByPlayer, TStageRosterOverride::getPlayerId, bo.getPlayerId())
            .eq(guestOp && !guestByPlayer, TStageRosterOverride::getGuestName,
                bo.getGuestName() == null ? null : bo.getGuestName().trim()));
        if (existed > 0) {
            throw new ServiceException("同类型覆盖已存在,请先删除或编辑原覆盖");
        }
        TStageRosterOverride o = new TStageRosterOverride();
        o.setTenantId(target.getTenantId());
        o.setTournamentId(target.getTournamentId());
        o.setTargetStageId(stageId);
        o.setOp(op);
        o.setSourceCompetitorId(bo.getSourceCompetitorId());
        o.setPlayerId(bo.getPlayerId());
        o.setGuestName(bo.getGuestName());
        o.setGuestType(bo.getGuestType());
        o.setGuestNumber(bo.getGuestNumber());
        o.setSeedRank(bo.getSeedRank());
        o.setRemark(bo.getRemark());
        overrideMapper.insert(o);
        log.info("赛段[{}]新增覆盖[{}]({})", stageId, o.getId(), op);
        notifyTarget(stageId);
        return MapstructUtils.convert(o, TStageRosterOverrideVo.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateOverride(Long stageId, Long overrideId, TStageRosterOverrideBo bo) {
        TStage target = assertOverrideEditable(stageId);
        TStageRosterOverride o = overrideMapper.selectById(overrideId);
        if (o == null || !Objects.equals(o.getTargetStageId(), stageId)) {
            throw new ServiceException("覆盖不存在或不属于该名单");
        }
        if (bo == null) {
            throw new ServiceException("请提供覆盖内容");
        }
        if (bo.getOp() != null && !bo.getOp().equals(o.getOp())) {
            throw new ServiceException("覆盖操作类型不可编辑,请删除后重建");
        }
        if (bo.getSourceCompetitorId() != null
            && !bo.getSourceCompetitorId().equals(o.getSourceCompetitorId())) {
            throw new ServiceException("覆盖引用源行不可编辑,请删除后重建");
        }
        if (RosterConstants.OVERRIDE_ADD_GUEST.equals(o.getOp())) {
            if (bo.getGuestName() != null) {
                o.setGuestName(bo.getGuestName());
            }
            if (bo.getPlayerId() != null) {
                o.setPlayerId(bo.getPlayerId());
            }
            if (bo.getGuestType() != null) {
                o.setGuestType(bo.getGuestType());
            }
            if (bo.getGuestNumber() != null) {
                o.setGuestNumber(bo.getGuestNumber());
            }
            validateGuestProfile(target, toBo(o));
        }
        if (bo.getSeedRank() != null) {
            if (RosterConstants.OVERRIDE_REMOVE.equals(o.getOp())) {
                throw new ServiceException("REMOVE 覆盖不支持种子位");
            }
            validateSeedWithinPlan(target, bo.getSeedRank());
            o.setSeedRank(bo.getSeedRank());
        }
        if (bo.getRemark() != null) {
            o.setRemark(bo.getRemark());
        }
        overrideMapper.updateById(o);
        notifyTarget(stageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteOverride(Long stageId, Long overrideId) {
        assertOverrideEditable(stageId);
        TStageRosterOverride o = overrideMapper.selectById(overrideId);
        if (o == null || !Objects.equals(o.getTargetStageId(), stageId)) {
            return;
        }
        overrideMapper.deleteById(overrideId);
        log.info("赛段[{}]撤销覆盖[{}]({})", stageId, overrideId, o.getOp());
        notifyTarget(stageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reorderRoster(Long stageId, List<TStageRosterOrderBo.Item> items) {
        TStage target = assertOverrideEditable(stageId);
        if (items == null || items.isEmpty()) {
            return;
        }
        // 显式种子优先(允许留空位:删掉的人原来的位置空着,后面的不顶上);
        // 未指定种子的按数组顺序补到最小空闲位
        Set<Long> used = new HashSet<>();
        for (TStageRosterOrderBo.Item item : items) {
            if (item != null && item.getSeedRank() != null && item.getSeedRank() > 0L) {
                if (!used.add(item.getSeedRank())) {
                    throw new ServiceException("名单顺序里种子位[{}]重复,请检查拖拽结果", item.getSeedRank());
                }
            }
        }
        long nextFree = 0L;
        for (TStageRosterOrderBo.Item item : items) {
            if (item == null) {
                continue;
            }
            long seed;
            if (item.getSeedRank() != null && item.getSeedRank() > 0L) {
                seed = item.getSeedRank();
            } else {
                do {
                    nextFree++;
                } while (used.contains(nextFree));
                seed = nextFree;
                used.add(seed);
            }
            Long sourceCompetitorId = item.getSourceCompetitorId();
            if (item.getOverrideId() != null) {
                TStageRosterOverride o = overrideMapper.selectById(item.getOverrideId());
                if (o == null || !Objects.equals(o.getTargetStageId(), stageId)) {
                    continue;
                }
                if (RosterConstants.OVERRIDE_ADD_GUEST.equals(o.getOp())) {
                    TStageRosterOverride upd = new TStageRosterOverride();
                    upd.setId(o.getId());
                    upd.setSeedRank(seed);
                    overrideMapper.updateById(upd);
                    continue;
                }
                if (o.getSourceCompetitorId() != null) {
                    sourceCompetitorId = o.getSourceCompetitorId();
                }
            }
            if (sourceCompetitorId == null) {
                continue;
            }
            upsertSeedOverride(target, sourceCompetitorId, seed);
        }
        notifyTarget(stageId);
    }

    /** 顺序 = 位置 i 记第 i 位:已有 SEED 覆盖则改,没有则建 */
    private void upsertSeedOverride(TStage target, Long sourceCompetitorId, long seedRank) {
        validateSeedWithinPlan(target, seedRank);
        TStageRosterOverride existing = overrideMapper.selectOne(Wrappers.<TStageRosterOverride>lambdaQuery()
            .eq(TStageRosterOverride::getTargetStageId, target.getId())
            .eq(TStageRosterOverride::getOp, RosterConstants.OVERRIDE_SEED)
            .eq(TStageRosterOverride::getSourceCompetitorId, sourceCompetitorId)
            .last("limit 1"));
        if (existing != null) {
            if (!Objects.equals(existing.getSeedRank(), seedRank)) {
                TStageRosterOverride upd = new TStageRosterOverride();
                upd.setId(existing.getId());
                upd.setSeedRank(seedRank);
                overrideMapper.updateById(upd);
            }
            return;
        }
        TCompetitor c = competitorMapper.selectById(sourceCompetitorId);
        TStageRosterOverride o = new TStageRosterOverride();
        o.setTenantId(target.getTenantId());
        o.setTournamentId(target.getTournamentId());
        o.setTargetStageId(target.getId());
        o.setOp(RosterConstants.OVERRIDE_SEED);
        o.setSourceCompetitorId(sourceCompetitorId);
        o.setSeedRank(seedRank);
        o.setRemark(c == null ? null : c.getName());
        overrideMapper.insert(o);
    }

    private void validateOverrideSource(Long stageId, String op, TStageRosterOverrideBo bo) {
        if (RosterConstants.OVERRIDE_ADD_GUEST.equals(op)) {
            return;
        }
        if (bo.getSourceCompetitorId() == null) {
            throw new ServiceException("覆盖[{}]需要指定源赛段参赛方(sourceCompetitorId)", op);
        }
        TStage target = stageMapper.selectById(stageId);
        TCompetitor c = competitorMapper.selectById(bo.getSourceCompetitorId());
        if (c == null) {
            throw new ServiceException("源参赛方[{}]不存在", bo.getSourceCompetitorId());
        }
        // 手工名单:允许从本赛事推进链上位于目标赛段之前的任意赛段取人(不限于规则声明的来源组)
        if (!RosterConstants.OVERRIDE_REMOVE.equals(op)) {
            assertAddableSource(target, c);
        }
    }

    /** 手工加入名单的校验:源行存在、同赛事、位于目标赛段之前、未弃权 */
    private void assertAddableSource(TStage target, TCompetitor c) {
        if (target == null || c == null) {
            throw new ServiceException("源参赛方不存在");
        }
        TStage src = stageMapper.selectById(c.getStageId());
        if (src == null) {
            throw new ServiceException("源参赛方[{}]所属赛段不存在", c.getId());
        }
        assertSourceBeforeTarget(target, src);
        if (OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus())) {
            throw new ServiceException("源参赛方[{}]({})已弃权,不能加入名单", c.getId(), c.getName());
        }
    }

    private void validateGuestProfile(TStage target, TStageRosterOverrideBo bo) {
        if ((bo.getPlayerId() == null || bo.getPlayerId() == 0L)
            && (bo.getGuestName() == null || bo.getGuestName().isBlank())) {
            throw new ServiceException("外卡需要提供关联选手或显示名称");
        }
        if (bo.getPlayerId() != null && bo.getPlayerId() > 0L) {
            TPlayer player = playerMapper.selectById(bo.getPlayerId());
            if (player == null || !Objects.equals(player.getTournamentId(), target.getTournamentId())) {
                throw new ServiceException("外卡关联选手不存在或不属于当前赛事");
            }
        }
    }

    private void validateSeedWithinPlan(TStage target, Long seedRank) {
        if (seedRank == null || seedRank < 1L) {
            throw new ServiceException("指定种子位需为正整数");
        }
        if (target.getTeamCountStart() != null && target.getTeamCountStart() > 0
            && seedRank > target.getTeamCountStart()) {
            throw new ServiceException("指定种子位[{}]超出赛段计划规模[{}]",
                seedRank, target.getTeamCountStart());
        }
    }

    private TStageRosterOverrideBo toBo(TStageRosterOverride o) {
        TStageRosterOverrideBo bo = new TStageRosterOverrideBo();
        bo.setOp(o.getOp());
        bo.setSourceCompetitorId(o.getSourceCompetitorId());
        bo.setPlayerId(o.getPlayerId());
        bo.setGuestName(o.getGuestName());
        bo.setGuestType(o.getGuestType());
        bo.setGuestNumber(o.getGuestNumber());
        bo.setSeedRank(o.getSeedRank());
        bo.setRemark(o.getRemark());
        return bo;
    }

    // ------------------------------------------------------------------
    // 来源组管理
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TStageRosterVo addGroups(Long stageId, TStageRosterBo bo) {
        TStage target = stageMapper.selectById(stageId);
        if (target == null) {
            throw new ServiceException("目标赛段不存在");
        }
        if (!StageConstants.STAGE_DRAFT.equals(target.getStatus())
            && !StageConstants.STAGE_PENDING.equals(target.getStatus())) {
            throw new ServiceException("仅规划中(DRAFT/PENDING)的赛段可新增来源,当前: {}",
                target.getStatus());
        }
        if (Long.valueOf(1L).equals(target.getIsInitialized())) {
            throw new ServiceException("赛段已初始化,名单来源已锁定");
        }
        if (bo == null) {
            throw new ServiceException("请提供来源组规则");
        }
        List<TStageRosterGroupBo> desired = new ArrayList<>();
        if (bo.getGroups() != null && !bo.getGroups().isEmpty()) {
            desired.addAll(bo.getGroups());
        } else {
            TStageRosterGroupBo g = new TStageRosterGroupBo();
            g.setSourceStageId(bo.getSourceStageId());
            g.setResultFilter(bo.getResultFilter() == null
                ? OutcomeStatusEnum.ADVANCE.getCode() : bo.getResultFilter());
            g.setZone(bo.getZoneFilter());
            g.setRound(bo.getRoundFilter());
            g.setScoreMin(bo.getScoreMin());
            g.setScoreMax(bo.getScoreMax());
            g.setRankStart(bo.getRankBandStart());
            g.setRankEnd(bo.getRankBandEnd());
            g.setRankByZone(false);
            g.setFillMode(bo.getFillMode() == null
                ? RosterConstants.FILL_AUTO : bo.getFillMode());
            g.setQuota(bo.getQuota() == null ? 0 : bo.getQuota());
            g.setPriority(bo.getPriority());
            desired.add(g);
        }
        if (desired.isEmpty()) {
            throw new ServiceException("请至少提供一条来源组规则");
        }
        List<TStageRosterGroupBo> merged = new ArrayList<>(groupsOf(target));
        int maxPriority = merged.stream()
            .map(g -> g.getPriority() == null ? 0 : g.getPriority())
            .max(Integer::compare).orElse(0);
        boolean changed = false;
        for (TStageRosterGroupBo g : desired) {
            if (g.getSourceStageId() == null) {
                g.setSourceStageId(bo.getSourceStageId());
            }
            if (g.getResultFilter() == null) {
                g.setResultFilter(bo.getResultFilter() == null
                    ? OutcomeStatusEnum.ADVANCE.getCode() : bo.getResultFilter());
            }
            if (g.getFillMode() == null) {
                g.setFillMode(bo.getFillMode() == null
                    ? RosterConstants.FILL_AUTO : bo.getFillMode());
            }
            if (g.getQuota() == null && bo.getQuota() != null) {
                g.setQuota(bo.getQuota());
            }
            if (g.getSourceStageId() != null) {
                TStage source = stageMapper.selectById(g.getSourceStageId());
                if (source == null || !Objects.equals(source.getTournamentId(), target.getTournamentId())) {
                    throw new ServiceException("来源赛段不存在或不属于同一赛事");
                }
                assertSourceBeforeTarget(target, source);
            }
            if (g.getPriority() == null) {
                g.setPriority(g.getSourceStageId() == null
                    ? RosterConstants.EXTERNAL_ROSTER_PRIORITY : ++maxPriority);
            }
            if (merged.stream().noneMatch(m -> sameGroup(m, g))) {
                merged.add(g);
                changed = true;
            }
        }
        if (changed) {
            saveGroups(target, merged);
        }
        notifyTarget(stageId);
        return toVo(stageMapper.selectById(stageId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeGroup(Long stageId, int groupIndex) {
        TStage target = mustRosterStage(stageId, true);
        if (!StageConstants.STAGE_DRAFT.equals(target.getStatus())
            && !StageConstants.STAGE_PENDING.equals(target.getStatus())) {
            throw new ServiceException("仅规划中(DRAFT/PENDING)的赛段可删除来源组,当前: {}",
                target.getStatus());
        }
        List<TStageRosterGroupBo> groups = groupsOf(target);
        if (groupIndex < 0 || groupIndex >= groups.size()) {
            throw new ServiceException("来源组下标越界: {}", groupIndex);
        }
        if (groups.size() <= 1) {
            throw new ServiceException("名单至少需要保留一组来源;如需清空请删除整个来源");
        }
        groups.remove(groupIndex);
        // 若删掉了最后一条引用上一赛段的来源,自动补回链式默认衔接(未配置出口=默认进下一赛段)
        ensurePrevChainDefault(target, groups);
        saveGroups(target, groups);
        log.info("赛段[{}]删除来源组[{}],剩余 {} 组", stageId, groupIndex, groups.size());
        notifyTarget(stageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateGroup(Long stageId, int groupIndex, TStageRosterGroupBo group) {
        TStage target = mustRosterStage(stageId, true);
        if (!StageConstants.STAGE_DRAFT.equals(target.getStatus())
            && !StageConstants.STAGE_PENDING.equals(target.getStatus())) {
            throw new ServiceException("仅规划中(DRAFT/PENDING)的赛段可编辑来源组,当前: {}",
                target.getStatus());
        }
        if (group == null) {
            throw new ServiceException("请提供来源组规则");
        }
        if (group.getSourceStageId() != null) {
            TStage source = stageMapper.selectById(group.getSourceStageId());
            if (source == null || !Objects.equals(source.getTournamentId(), target.getTournamentId())) {
                throw new ServiceException("来源赛段不存在或不属于同一赛事");
            }
            assertSourceBeforeTarget(target, source);
        }
        List<TStageRosterGroupBo> groups = groupsOf(target);
        if (groupIndex < 0 || groupIndex >= groups.size()) {
            throw new ServiceException("来源组下标越界: {}", groupIndex);
        }
        TStageRosterGroupBo old = groups.get(groupIndex);
        if (group.getResultFilter() == null) {
            group.setResultFilter(OutcomeStatusEnum.ADVANCE.getCode());
        }
        if (group.getFillMode() == null) {
            group.setFillMode(RosterConstants.FILL_AUTO);
        }
        if (group.getQuota() == null) {
            group.setQuota(0);
        }
        if (group.getPriority() == null) {
            group.setPriority(old.getPriority() == null ? groupIndex + 1 : old.getPriority());
        }
        groups.set(groupIndex, group);
        // 编辑后若不再引用上一赛段,同样补回默认衔接
        ensurePrevChainDefault(target, groups);
        saveGroups(target, groups);
        log.info("赛段[{}]更新来源组[{}]", stageId, groupIndex);
        notifyTarget(stageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markSkipped(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null) {
            return;
        }
        TStage upd = new TStage();
        upd.setId(stageId);
        upd.setRosterSkipped(1L);
        stageMapper.updateById(upd);
        notifyTarget(stageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetByTarget(Long targetStageId) {
        TStage upd = new TStage();
        upd.setId(targetStageId);
        upd.setRosterApplied(0L);
        upd.setRosterSkipped(0L);
        stageMapper.updateById(upd);
    }

    // ------------------------------------------------------------------
    // 候选与装配(唯一写库内核)
    // ------------------------------------------------------------------

    private void assertNoPendingInSources(List<TStageRosterGroupBo> groups) {
        for (TStageRosterGroupBo g : groups) {
            if (g.getSourceStageId() == null) {
                continue;
            }
            TStage src = stageMapper.selectById(g.getSourceStageId());
            if (src == null) {
                continue;
            }
            long pending = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, src.getId())
                .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.PENDING.getCode()));
            if (pending > 0) {
                throw new ServiceException(
                    "来源赛段[{}]仍有 {} 名同分待定参赛方未裁决,请先在中间态处理后再确认名单",
                    src.getName(), pending);
            }
        }
    }

    @Override
    public RosterCandidatesVo candidates(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null) {
            throw new ServiceException("赛段不存在");
        }
        RosterCandidatesVo vo = new RosterCandidatesVo();
        vo.setStageId(stageId);
        vo.setState(stateOf(stage));
        List<RosterCandidatesVo.GroupCandidates> groupsVo = new ArrayList<>();
        for (TStageRosterGroupBo g : groupsOf(stage)) {
            RosterCandidatesVo.GroupCandidates gv = new RosterCandidatesVo.GroupCandidates();
            gv.setResultFilter(g.getResultFilter());
            gv.setZone(g.getZone());
            gv.setRankStart(g.getRankStart());
            gv.setRankEnd(g.getRankEnd());
            gv.setRankByZone(g.getRankByZone());
            gv.setRound(g.getRound());
            gv.setScoreMin(g.getScoreMin());
            gv.setScoreMax(g.getScoreMax());
            gv.setLabel(groupLabel(g));
            gv.setSourceStageId(g.getSourceStageId());
            gv.setCompetitors(groupRows(g).stream()
                .map(c -> MapstructUtils.convert(c, TCompetitorVo.class))
                .toList());
            groupsVo.add(gv);
        }
        vo.setGroups(groupsVo);
        return vo;
    }

    @Override
    public boolean hasAnyCandidate(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null) {
            return false;
        }
        for (TStageRosterGroupBo g : groupsOf(stage)) {
            if (!groupRows(g).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isRosterReady(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        return stage != null && readyByGroups(groupsOf(stage));
    }

    @Override
    public List<TCompetitor> previewRoster(Long targetStageId) {
        TStage stage = stageMapper.selectById(targetStageId);
        if (stage == null || isLocked(stage)) {
            return List.of();
        }
        boolean anyInternal = groupsOf(stage).stream().anyMatch(g -> g.getSourceStageId() != null);
        if (!anyInternal) {
            return List.of();
        }
        return autoCandidates(groupsOf(stage));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int applyRoster(Long targetStageId, Map<Long, List<Long>> manualSelections) {
        TStage target = stageMapper.selectById(targetStageId);
        if (target == null) {
            throw new ServiceException("赛段不存在");
        }
        if (!StageConstants.STAGE_DRAFT.equals(target.getStatus())
            && !StageConstants.STAGE_PENDING.equals(target.getStatus())) {
            throw new ServiceException("仅规划中(DRAFT/PENDING)的赛段可装配名单,当前: {}",
                target.getStatus());
        }
        if (Long.valueOf(1L).equals(target.getIsInitialized())) {
            throw new ServiceException("赛段已初始化,名单已锁定,无法装配");
        }
        if (isLocked(target)) {
            return 0;
        }
        List<TStageRosterGroupBo> groups = groupsOf(target);
        // 装配前兜底:未配置出口时,上一赛段晋级者默认进入本赛段
        int groupCountBefore = groups.size();
        ensurePrevChainDefault(target, groups);
        if (groups.size() != groupCountBefore) {
            saveGroups(target, groups);
        }
        boolean anyInternal = groups.stream().anyMatch(g -> g.getSourceStageId() != null);
        // 无内部来源且没有任何人工覆盖 = 本赛段不带人(签到/手动名单由独立入口维护);
        // 有 ADD_GUEST/ADD_SOURCE 覆盖时仍要物化——覆盖是显式人工决定,不依赖来源组。
        if (!anyInternal && overridesOf(targetStageId).isEmpty()) {
            return 0;
        }
        long matchCount = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, targetStageId));
        if (matchCount > 0) {
            throw new ServiceException("赛段[{}]已生成对阵,请先重置为草稿后再确认名单",
                target.getName());
        }
        if (!readyByGroups(groups)) {
            throw new ServiceException("名单来源尚未全部结算,请等待后再确认名单");
        }
        assertNoPendingInSources(groups);

        List<TCompetitor> existing = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, targetStageId)
            .orderByAsc(TCompetitor::getId));
        int plan = target.getTeamCountStart() != null && target.getTeamCountStart() > 0
            ? target.getTeamCountStart().intValue() : 0;
        Set<String> existingPlayerKeys = memberPlayerKeys(existing);
        Map<Long, TCompetitor> existingBySource = new HashMap<>();
        for (TCompetitor c : existing) {
            if (c.getSourceCompetitorId() != null) {
                existingBySource.put(c.getSourceCompetitorId(), c);
            }
        }

        List<AssembledRow> rows = assembleRows(targetStageId, groups, manualSelections, true);
        if (rows.isEmpty()) {
            log.info("赛段[{}]名单装配:无任何候选/覆盖,未带入人员", targetStageId);
            return 0;
        }
        if (plan > 0 && rows.size() > plan) {
            throw new ServiceException("名单装配 {} 人超出赛段计划 {} 人,请先调整来源组或人工覆盖后再确认",
                rows.size(), plan);
        }
        Set<Long> occupiedSeeds = new HashSet<>();
        for (TCompetitor c : existing) {
            if (c.getSeedRank() != null) {
                occupiedSeeds.add(c.getSeedRank());
            }
        }
        assignSeeds(rows, plan, occupiedSeeds);
        int created = 0;
        for (AssembledRow row : rows) {
            if (!row.guest && existingBySource.containsKey(row.source.getId())) {
                continue;
            }
            List<Long> players = row.guest
                ? (row.guestPlayerId == null ? List.of() : List.of(row.guestPlayerId))
                : memberPlayersOf(List.of(row.source)).getOrDefault(row.source.getId(), List.of());
            boolean overlap = false;
            for (Long pid : players) {
                if (pid != null && existingPlayerKeys.contains(String.valueOf(pid))) {
                    overlap = true;
                    break;
                }
            }
            if (overlap) {
                log.warn("赛段[{}]装配项与目标赛段已有选手重复,跳过: {}",
                    targetStageId, row.guest ? row.guestName : row.source.getName());
                continue;
            }
            if (plan > 0 && existing.size() + created >= plan) {
                throw new ServiceException("名单装配超出赛段计划 {} 人,请先调整来源组或人工覆盖后再确认",
                    plan);
            }
            if (row.guest) {
                created += copyGuestIntoStage(target, row, occupiedSeeds);
            } else {
                created += copyIntoStage(target, row.source, occupiedSeeds, players, row.seedRank);
            }
        }
        if (created > 0 || rows.stream().allMatch(r -> !r.guest
            && existingBySource.containsKey(r.source.getId()))) {
            TStage upd = new TStage();
            upd.setId(targetStageId);
            upd.setRosterApplied(1L);
            stageMapper.updateById(upd);
            log.info("赛段[{}]名单装配完成:新增 {} 人(共 {} 项)", targetStageId, created, rows.size());
        }
        log.info("赛段[{}]整单装配完成:新增 {} 人", targetStageId, created);
        notifyTarget(targetStageId);
        return created;
    }

    private static final class AssembledRow {

        TCompetitor source;
        boolean guest;
        String guestName;
        Long guestPlayerId;
        Long guestType;
        String guestNumber;
        Long overrideId;
        Long forcedSeed;
        Long seedRank;
        String entryTag;
    }

    private static AssembledRow sourceRow(TCompetitor c, Long overrideId, Long forcedSeed) {
        AssembledRow r = new AssembledRow();
        r.source = c;
        r.overrideId = overrideId;
        r.forcedSeed = forcedSeed;
        r.entryTag = OutcomeStatusEnum.ADVANCE.getCode().equals(c.getOutcomeStatus())
            ? RosterConstants.ENTRY_ADVANCE : RosterConstants.ENTRY_REVIVE;
        return r;
    }

    private static AssembledRow guestRow(TStageRosterOverride o) {
        AssembledRow r = new AssembledRow();
        r.guest = true;
        r.overrideId = o.getId();
        r.guestName = o.getGuestName();
        r.guestPlayerId = o.getPlayerId();
        r.guestType = o.getGuestType();
        r.guestNumber = o.getGuestNumber();
        r.forcedSeed = o.getSeedRank();
        r.entryTag = RosterConstants.ENTRY_GUEST;
        return r;
    }

    private List<AssembledRow> assembleRows(Long stageId, List<TStageRosterGroupBo> groups,
                                            Map<Long, List<Long>> manualSelections,
                                            boolean strict) {
        List<TStageRosterOverride> overrides = overridesOf(stageId);
        Set<Long> removedIds = overrides.stream()
            .filter(o -> RosterConstants.OVERRIDE_REMOVE.equals(o.getOp()))
            .map(TStageRosterOverride::getSourceCompetitorId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        List<Long> manualIds = manualSelections == null ? null : manualSelections.get(stageId);
        boolean hasManualGroup = groups.stream()
            .anyMatch(g -> RosterConstants.FILL_MANUAL.equals(g.getFillMode()));
        boolean hasAddOverride = overrides.stream()
            .anyMatch(o -> RosterConstants.OVERRIDE_ADD_SOURCE.equals(o.getOp())
                || RosterConstants.OVERRIDE_ADD_GUEST.equals(o.getOp()));
        if (strict && hasManualGroup && (manualIds == null || manualIds.isEmpty()) && !hasAddOverride) {
            throw new ServiceException("名单含手动来源组,请选择参赛者或添加人工覆盖后再确认名单");
        }
        List<AssembledRow> rows = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (TCompetitor c : autoCandidates(groups)) {
            if (removedIds.contains(c.getId()) || !seen.add(c.getId())) {
                continue;
            }
            rows.add(sourceRow(c, null, null));
        }
        if (manualIds != null && !manualIds.isEmpty()) {
            for (TCompetitor c : resolveManualCandidates(groups, manualIds)) {
                if (removedIds.contains(c.getId()) || !seen.add(c.getId())) {
                    continue;
                }
                rows.add(sourceRow(c, null, null));
            }
        }
        TStage targetStage = stageMapper.selectById(stageId);
        for (TStageRosterOverride o : overrides) {
            if (!RosterConstants.OVERRIDE_ADD_SOURCE.equals(o.getOp())
                || o.getSourceCompetitorId() == null) {
                continue;
            }
            if (removedIds.contains(o.getSourceCompetitorId()) || !seen.add(o.getSourceCompetitorId())) {
                continue;
            }
            TCompetitor c = competitorMapper.selectById(o.getSourceCompetitorId());
            assertAddableSource(targetStage, c);
            rows.add(sourceRow(c, o.getId(), o.getSeedRank()));
        }
        for (TStageRosterOverride o : overrides) {
            if (RosterConstants.OVERRIDE_ADD_GUEST.equals(o.getOp())) {
                rows.add(guestRow(o));
            }
        }
        for (TStageRosterOverride o : overrides) {
            if (!RosterConstants.OVERRIDE_SEED.equals(o.getOp())
                || o.getSourceCompetitorId() == null || o.getSeedRank() == null) {
                continue;
            }
            for (AssembledRow r : rows) {
                if (!r.guest && r.source != null
                    && Objects.equals(r.source.getId(), o.getSourceCompetitorId())) {
                    r.forcedSeed = o.getSeedRank();
                }
            }
        }
        return rows;
    }

    private void assignSeeds(List<AssembledRow> rows, int plan, Set<Long> occupiedSeeds) {
        Set<Long> occupied = new HashSet<>(occupiedSeeds);
        for (AssembledRow r : rows) {
            if (r.forcedSeed == null) {
                continue;
            }
            if (plan > 0 && r.forcedSeed > plan) {
                throw new ServiceException("指定种子位[{}]超出赛段计划规模[{}]", r.forcedSeed, plan);
            }
            if (!occupied.add(r.forcedSeed)) {
                throw new ServiceException("种子覆盖位[{}]已被占用,请先在中间态调整预排位置", r.forcedSeed);
            }
        }
        for (AssembledRow r : rows) {
            if (r.forcedSeed != null) {
                r.seedRank = r.forcedSeed;
            } else {
                r.seedRank = nextFreeSeed(occupied, plan);
                occupied.add(r.seedRank);
            }
        }
    }

    private List<TCompetitor> autoCandidates(List<TStageRosterGroupBo> groups) {
        Map<Long, TCompetitor> merged = new LinkedHashMap<>();
        for (TStageRosterGroupBo g : groups) {
            if (g.getSourceStageId() == null
                || RosterConstants.FILL_STREAM.equals(g.getFillMode())
                || RosterConstants.FILL_MANUAL.equals(g.getFillMode())) {
                continue;
            }
            List<TCompetitor> rows = new ArrayList<>(groupRows(g));
            TStage src = stageMapper.selectById(g.getSourceStageId());
            if (rotateNeeded(g, src)) {
                reorderByCircleRank(src, rows);
            }
            int quota = g.getQuota() != null && g.getQuota() > 0 ? g.getQuota() : Integer.MAX_VALUE;
            int n = 0;
            for (TCompetitor c : rows) {
                if (n++ >= quota) {
                    log.warn("来源组(源赛段 {})配额 {} 已满,剩余候选截断",
                        g.getSourceStageId(), g.getQuota());
                    break;
                }
                merged.putIfAbsent(c.getId(), c);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private boolean rotateNeeded(TStageRosterGroupBo g, TStage source) {
        if (RosterConstants.ORDER_ZONE_RANK_ROTATE.equals(g.getOrderBy())) {
            return true;
        }
        if (g.getOrderBy() != null && !g.getOrderBy().isBlank()) {
            return false;
        }
        return source != null && StageModeEnum.AUDITION.getCode().equals(source.getStageMode());
    }

    private List<TCompetitor> resolveManualCandidates(List<TStageRosterGroupBo> groups,
                                                      List<Long> selected) {
        if (selected == null || selected.isEmpty()) {
            throw new ServiceException("名单含手动来源组,请先选择参赛者");
        }
        List<TCompetitor> allowed = groupCandidates(groups);
        Set<Long> allowedIds = allowed.stream().map(TCompetitor::getId).collect(Collectors.toSet());
        Map<Long, TCompetitor> byId = competitorMapper.selectByIds(selected).stream()
            .collect(Collectors.toMap(TCompetitor::getId, c -> c, (a, b) -> a));
        List<TCompetitor> ordered = new ArrayList<>();
        for (Long id : selected) {
            TCompetitor c = byId.get(id);
            if (c == null) {
                throw new ServiceException("手动选择的参赛方[{}]不存在", id);
            }
            if (!allowedIds.contains(id)) {
                throw new ServiceException("参赛方[{}]({})不符合名单来源规则(圈/名次/分数等)",
                    id, c.getName());
            }
            ordered.add(c);
        }
        return ordered;
    }

    private List<TCompetitor> groupCandidates(List<TStageRosterGroupBo> groups) {
        Map<Long, TCompetitor> merged = new LinkedHashMap<>();
        for (TStageRosterGroupBo g : groups) {
            for (TCompetitor c : groupRows(g)) {
                merged.putIfAbsent(c.getId(), c);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private record PartInfo(String zone, Integer row, Integer rankInMatch, BigDecimal score) {
    }

    private List<TCompetitor> groupRows(TStageRosterGroupBo g) {
        Long sourceStageId = g.getSourceStageId();
        if (sourceStageId == null) {
            return List.of();
        }
        List<TCompetitor> comps = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, sourceStageId)
            .orderByAsc(TCompetitor::getId));
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, sourceStageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
        List<Long> matchIds = matches.stream().map(TMatch::getId).toList();
        Map<Long, PartInfo> partByComp = new HashMap<>();
        if (!matchIds.isEmpty()) {
            Map<Long, String> zoneById = matches.stream().collect(Collectors.toMap(TMatch::getId,
                m -> m.getDisplayZone() == null ? "CENTER" : m.getDisplayZone(), (a, b) -> a));
            Map<Long, Integer> rowById = matches.stream().collect(Collectors.toMap(TMatch::getId,
                m -> m.getDisplayRow() == null ? 0 : m.getDisplayRow().intValue(), (a, b) -> a));
            participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                    .in(TMatchParticipant::getMatchId, matchIds)
                    .isNotNull(TMatchParticipant::getCompetitorId))
                .forEach(p -> partByComp.putIfAbsent(p.getCompetitorId(), new PartInfo(
                    zoneById.get(p.getMatchId()),
                    rowById.get(p.getMatchId()),
                    p.getRankInMatch() == null ? null : p.getRankInMatch().intValue(),
                    p.getScoreValue())));
        }
        List<TCompetitor> groupList = new ArrayList<>();
        for (TCompetitor c : comps) {
            if (!outcomeMatches(c, g.getResultFilter())) {
                continue;
            }
            PartInfo part = partByComp.get(c.getId());
            if (!groupPass(g, c, part, zoneByIdOrder(matches))) {
                continue;
            }
            groupList.add(c);
        }
        groupList.sort(groupComparator(g, partByComp, zoneByIdOrder(matches)));
        return groupList;
    }

    private Map<String, Integer> zoneByIdOrder(List<TMatch> matches) {
        Map<String, Integer> order = new HashMap<>();
        int i = 0;
        for (TMatch m : matches) {
            String z = m.getDisplayZone() == null ? "CENTER" : m.getDisplayZone();
            order.putIfAbsent(z, i++);
        }
        return order;
    }

    private boolean outcomeMatches(TCompetitor c, String filter) {
        if (filter == null || RosterConstants.FILTER_ANY.equals(filter)) {
            return !OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus());
        }
        return filter.equals(c.getOutcomeStatus());
    }

    private boolean groupPass(TStageRosterGroupBo g, TCompetitor c, PartInfo part,
                              Map<String, Integer> zoneOrder) {
        boolean needPart = g.getZone() != null || g.getRound() != null || g.getScoreMin() != null
            || g.getScoreMax() != null || Boolean.TRUE.equals(g.getRankByZone());
        if (needPart && part == null) {
            return false;
        }
        if (g.getZone() != null && part != null
            && !Objects.equals(normalizeZone(g.getZone()), part.zone())) {
            return false;
        }
        if (g.getRound() != null && part != null && !Objects.equals(g.getRound(), part.row())) {
            return false;
        }
        if (g.getScoreMin() != null && (part == null || part.score() == null
            || part.score().compareTo(g.getScoreMin()) < 0)) {
            return false;
        }
        if (g.getScoreMax() != null && (part == null || part.score() == null
            || part.score().compareTo(g.getScoreMax()) > 0)) {
            return false;
        }
        if (Boolean.TRUE.equals(g.getRankByZone())) {
            if (part == null || part.rankInMatch() == null) {
                return false;
            }
            int r = part.rankInMatch();
            if (g.getRankStart() != null && r < g.getRankStart()) {
                return false;
            }
            if (g.getRankEnd() != null && r > g.getRankEnd()) {
                return false;
            }
        } else {
            if (g.getRankStart() != null && (c.getFinalRank() == null
                || c.getFinalRank() < g.getRankStart())) {
                return false;
            }
            if (g.getRankEnd() != null && (c.getFinalRank() == null
                || c.getFinalRank() > g.getRankEnd())) {
                return false;
            }
        }
        return true;
    }

    private String normalizeZone(String zone) {
        return zone == null ? null : (zone.startsWith("ZONE-") ? zone : zone.toUpperCase());
    }

    private Comparator<TCompetitor> groupComparator(TStageRosterGroupBo g,
                                                    Map<Long, PartInfo> partByComp,
                                                    Map<String, Integer> zoneOrder) {
        String orderBy = g.getOrderBy();
        if (RosterConstants.ORDER_SCORE.equals(orderBy)) {
            return (a, b) -> {
                BigDecimal sa = partByComp.get(a.getId()) == null ? null : partByComp.get(a.getId()).score();
                BigDecimal sb = partByComp.get(b.getId()) == null ? null : partByComp.get(b.getId()).score();
                int c = sb == null ? (sa == null ? 0 : -1) : (sa == null ? 1 : sb.compareTo(sa));
                return c != 0 ? c : Long.compare(a.getId(), b.getId());
            };
        }
        if (RosterConstants.ORDER_NUMBER.equals(orderBy)) {
            return Comparator.comparingLong((TCompetitor c) -> parseNumber(c.getNumber()))
                .thenComparing(TCompetitor::getId);
        }
        if (RosterConstants.ORDER_RANDOM.equals(orderBy)) {
            return Comparator.comparingLong((TCompetitor c) -> Long.hashCode(c.getId()))
                .thenComparing(TCompetitor::getId);
        }
        return (a, b) -> {
            if (g.getZone() != null || Boolean.TRUE.equals(g.getRankByZone())
                || RosterConstants.ORDER_ZONE_RANK.equals(orderBy)
                || RosterConstants.ORDER_ZONE_RANK_ROTATE.equals(orderBy)) {
                PartInfo pa = partByComp.get(a.getId());
                PartInfo pb = partByComp.get(b.getId());
                int za = zoneOrder.getOrDefault(pa == null ? "" : pa.zone(), Integer.MAX_VALUE);
                int zb = zoneOrder.getOrDefault(pb == null ? "" : pb.zone(), Integer.MAX_VALUE);
                if (za != zb) {
                    return Integer.compare(za, zb);
                }
                int ra = pa != null && pa.rankInMatch() != null ? pa.rankInMatch() : Integer.MAX_VALUE;
                int rb = pb != null && pb.rankInMatch() != null ? pb.rankInMatch() : Integer.MAX_VALUE;
                int c = Integer.compare(ra, rb);
                if (c != 0) {
                    return c;
                }
            } else {
                long fa = a.getFinalRank() == null ? Long.MAX_VALUE : a.getFinalRank();
                long fb = b.getFinalRank() == null ? Long.MAX_VALUE : b.getFinalRank();
                int c = Long.compare(fa, fb);
                if (c != 0) {
                    return c;
                }
            }
            return Long.compare(a.getId(), b.getId());
        };
    }

    private long parseNumber(String number) {
        if (number == null) {
            return Long.MAX_VALUE;
        }
        try {
            String n = number.trim().replaceFirst("^G", "");
            if (n.isEmpty() || !n.matches("\\d+")) {
                return Long.MAX_VALUE;
            }
            return Long.parseLong(n);
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    private void reorderByCircleRank(TStage source, List<TCompetitor> advancers) {
        if (source == null || !StageModeEnum.AUDITION.getCode().equals(source.getStageMode())
            || advancers == null || advancers.size() < 2) {
            return;
        }
        List<TMatch> srcMatches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, source.getId())
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
        if (srcMatches.size() < 2) {
            return;
        }
        RuleConfigHolder rc = RuleConfigParser.parse(source.getRuleConfig());
        List<Integer> perCircleCfg = rc != null ? rc.getCircleAdvanceCounts() : null;
        boolean explicitQuota = perCircleCfg != null && !perCircleCfg.isEmpty();
        int advanceCount = readStageAdvanceCount(source);
        int circles = (int) srcMatches.stream().map(m -> zoneOfMatch(m)).distinct().count();
        circles = Math.max(1, circles);
        int plannedCircles = rc != null && rc.getCircles() != null ? Math.max(1, rc.getCircles()) : circles;
        int divideBy = circles > plannedCircles ? plannedCircles : circles;
        int perCircle = divideBy > 1 ? advanceCount / divideBy : advanceCount;
        Map<String, Integer> zoneOrdinal = new HashMap<>();
        Map<String, Integer> zoneBase = new HashMap<>();
        int ordinal = 0;
        int acc = 0;
        for (TMatch m : srcMatches) {
            String zone = zoneOfMatch(m);
            if (!zoneOrdinal.containsKey(zone)) {
                int quota = ordinal >= plannedCircles ? 0
                    : explicitQuota && ordinal < perCircleCfg.size()
                        ? Math.max(0, perCircleCfg.get(ordinal)) : perCircle;
                zoneOrdinal.put(zone, ordinal);
                zoneBase.put(zone, acc);
                acc += quota;
                ordinal++;
            }
        }
        List<Long> srcMatchIds = srcMatches.stream().map(TMatch::getId).toList();
        Map<Long, String> zoneByCompetitor = new HashMap<>();
        if (!srcMatchIds.isEmpty()) {
            Map<Long, String> matchZone = new HashMap<>();
            for (TMatch m : srcMatches) {
                matchZone.put(m.getId(), zoneOfMatch(m));
            }
            participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                    .in(TMatchParticipant::getMatchId, srcMatchIds)
                    .isNotNull(TMatchParticipant::getCompetitorId)
                    .select(TMatchParticipant::getCompetitorId, TMatchParticipant::getMatchId))
                .forEach(p -> zoneByCompetitor.putIfAbsent(p.getCompetitorId(),
                    matchZone.getOrDefault(p.getMatchId(), "CENTER")));
        }
        advancers.sort((a, b) -> {
            String za = zoneByCompetitor.get(a.getId());
            String zb = zoneByCompetitor.get(b.getId());
            long fa = a.getFinalRank() == null ? Long.MAX_VALUE : a.getFinalRank();
            long fb = b.getFinalRank() == null ? Long.MAX_VALUE : b.getFinalRank();
            if (za == null || zb == null || !zoneOrdinal.containsKey(za) || !zoneOrdinal.containsKey(zb)) {
                int cmp = Long.compare(fa, fb);
                return cmp != 0 ? cmp : Long.compare(a.getId(), b.getId());
            }
            long ra = fa - zoneBase.getOrDefault(za, 0);
            long rb = fb - zoneBase.getOrDefault(zb, 0);
            if (ra != rb) {
                return Long.compare(ra, rb);
            }
            int oa = zoneOrdinal.get(za);
            int ob = zoneOrdinal.get(zb);
            if (oa != ob) {
                return Integer.compare(oa, ob);
            }
            return Long.compare(a.getId(), b.getId());
        });
    }

    private String zoneOfMatch(TMatch m) {
        return m.getDisplayZone() == null ? "CENTER" : m.getDisplayZone();
    }

    private int readStageAdvanceCount(TStage stage) {
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        int advanceCount = 1;
        if (rc != null && rc.getKnockout() != null && rc.getKnockout().getAdvanceCount() != null) {
            advanceCount = rc.getKnockout().getAdvanceCount();
        }
        try {
            Map<String, Object> raw = new tools.jackson.databind.ObjectMapper()
                .readValue(stage.getRuleConfig(), Map.class);
            if (raw != null && raw.containsKey("advanceCount")) {
                advanceCount = ((Number) raw.get("advanceCount")).intValue();
            }
        } catch (Exception ignored) {
        }
        return advanceCount;
    }

    private String groupLabel(TStageRosterGroupBo g) {
        String filter = g.getResultFilter();
        String result = OutcomeStatusEnum.ADVANCE.getCode().equals(filter) ? "晋级"
            : OutcomeStatusEnum.ELIMINATED.getCode().equals(filter) ? "落选"
            : filter == null ? "不限" : filter;
        String rank = g.getRankStart() != null || g.getRankEnd() != null
            ? (g.getRankStart() == null ? "" : g.getRankStart()) + "~"
                + (g.getRankEnd() == null ? "末" : g.getRankEnd()) + "名"
            : "";
        if (g.getZone() != null) {
            Map<String, Integer> order = g.getSourceStageId() == null ? Map.of()
                : zoneOrderOf(g.getSourceStageId());
            return "第" + (order.getOrDefault(normalizeZone(g.getZone()), -1) + 1) + "圈·" + result + rank;
        }
        return (Boolean.TRUE.equals(g.getRankByZone()) ? "每圈" : "全场") + result + rank;
    }

    private Map<String, Integer> zoneOrderOf(Long sourceStageId) {
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, sourceStageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
        return zoneByIdOrder(matches);
    }

    @Override
    public RosterPreviewVo previewAssembled(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null) {
            throw new ServiceException("赛段不存在");
        }
        RosterPreviewVo vo = new RosterPreviewVo();
        vo.setStageId(stageId);
        vo.setTargetStageId(stageId);
        List<TStageRosterGroupBo> groups = groupsOf(stage);
        vo.setReady(readyByGroups(groups));
        vo.setApplied(isApplied(stage));
        vo.setSkipped(isSkipped(stage));
        int plan = stage.getTeamCountStart() == null || stage.getTeamCountStart() <= 0
            ? 0 : stage.getTeamCountStart().intValue();
        vo.setCapacity(plan);
        // 已确认:直接展示落库的名单快照(种子位/来源标签都是真实值);
        // 不能再按规则重算——快照行已占满种子位,重算会把所有人的次序算成计划外
        if (isApplied(stage)) {
            List<TCompetitor> snapshot = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stageId)
                .orderByAsc(TCompetitor::getSeedRank)
                .orderByAsc(TCompetitor::getId));
            for (TCompetitor c : snapshot) {
                if (OutcomeStatusEnum.WITHDRAWN.getCode().equals(c.getOutcomeStatus())) {
                    continue;
                }
                RosterPreviewItemVo item = new RosterPreviewItemVo();
                item.setSeedRank(c.getSeedRank());
                item.setEntryTag(c.getEntryTag());
                item.setName(c.getName());
                item.setType(c.getType());
                item.setNumber(c.getNumber());
                item.setOutcomeStatus(c.getOutcomeStatus());
                item.setFinalRank(c.getFinalRank());
                if (c.getSourceCompetitorId() != null) {
                    item.setRefType("SOURCE");
                    item.setSourceCompetitorId(c.getSourceCompetitorId());
                    item.setSourceStageId(c.getSourceStageId());
                } else {
                    item.setRefType("GUEST");
                }
                vo.getItems().add(item);
            }
            return vo;
        }
        List<AssembledRow> rows = assembleRows(stageId, groups, null, false);
        if (groups.stream().anyMatch(g -> RosterConstants.FILL_MANUAL.equals(g.getFillMode()))) {
            vo.getWarnings().add("含手动来源组:请选择参赛者或添加 ADD_SOURCE 覆盖后再确认名单");
        }
        List<TCompetitor> existing = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stageId));
        Set<Long> occupied = new HashSet<>();
        for (TCompetitor c : existing) {
            if (c.getSeedRank() != null) {
                occupied.add(c.getSeedRank());
            }
        }
        assignSeeds(rows, plan, occupied);
        // 预览列表按出场次序展示(否则拖动排序看不出变化)
        rows.sort(Comparator.comparingLong(r -> r.seedRank == null ? Long.MAX_VALUE : r.seedRank));
        for (AssembledRow r : rows) {
            RosterPreviewItemVo item = new RosterPreviewItemVo();
            item.setOverrideId(r.overrideId);
            item.setEntryTag(r.entryTag);
            item.setSeedRank(r.seedRank);
            if (r.guest) {
                item.setRefType("GUEST");
                item.setPlayerId(r.guestPlayerId);
                item.setName(r.guestName);
                item.setType(r.guestType);
                item.setNumber(r.guestNumber);
            } else {
                item.setRefType("SOURCE");
                item.setSourceCompetitorId(r.source.getId());
                item.setSourceStageId(r.source.getStageId());
                item.setName(r.source.getName());
                item.setType(r.source.getType());
                item.setNumber(r.source.getNumber());
                item.setOutcomeStatus(r.source.getOutcomeStatus());
                item.setFinalRank(r.source.getFinalRank());
            }
            vo.getItems().add(item);
        }
        if (plan > 0 && rows.size() > plan) {
            vo.getWarnings().add(String.format(
                "装配 %d 人超出赛段计划 %d 人,确认名单前请调整来源组或人工覆盖",
                rows.size(), plan));
        }
        return vo;
    }

    // ------------------------------------------------------------------
    // 物化
    // ------------------------------------------------------------------

    private int copyIntoStage(TStage target, TCompetitor source, Set<Long> occupiedSeeds,
                              List<Long> players, Long seedOverride) {
        int plan = target.getTeamCountStart() != null && target.getTeamCountStart() > 0
            ? target.getTeamCountStart().intValue() : 0;
        long seed;
        if (seedOverride != null) {
            if (occupiedSeeds.contains(seedOverride)) {
                throw new ServiceException(
                    "种子覆盖位[{}]已被占用(已有参赛方或其他晋级者),请先在中间态调整预排位置", seedOverride);
            }
            seed = seedOverride;
        } else {
            seed = nextFreeSeed(occupiedSeeds, plan);
        }
        TCompetitor nc = new TCompetitor();
        nc.setTenantId(target.getTenantId());
        nc.setTournamentId(target.getTournamentId());
        nc.setStageId(target.getId());
        nc.setSourceCompetitorId(source.getId());
        nc.setSourceStageId(source.getStageId());
        nc.setFromRoster(1L);
        nc.setEntryTag(OutcomeStatusEnum.ADVANCE.getCode().equals(source.getOutcomeStatus())
            ? RosterConstants.ENTRY_ADVANCE : RosterConstants.ENTRY_REVIVE);
        nc.setType(source.getType());
        nc.setName(source.getName());
        nc.setNumber(source.getNumber());
        nc.setSeedRank(seed);
        nc.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        nc.setRemark(source.getRemark());
        competitorMapper.insert(nc);
        for (TCompetitorMember sm : competitorMemberMapper.selectList(Wrappers.<TCompetitorMember>lambdaQuery()
            .eq(TCompetitorMember::getCompetitorId, source.getId()))) {
            TCompetitorMember nm = new TCompetitorMember();
            nm.setTenantId(nc.getTenantId());
            nm.setTournamentId(nc.getTournamentId());
            nm.setCompetitorId(nc.getId());
            nm.setPlayerId(sm.getPlayerId());
            nm.setRole(sm.getRole());
            nm.setRemark(sm.getRemark());
            competitorMemberMapper.insert(nm);
        }
        occupiedSeeds.add(seed);
        return 1;
    }

    private int copyGuestIntoStage(TStage target, AssembledRow row, Set<Long> occupiedSeeds) {
        String number = row.guestNumber != null && !row.guestNumber.isBlank()
            ? row.guestNumber : nextGuestNumber(target);
        TCompetitor nc = new TCompetitor();
        nc.setTenantId(target.getTenantId());
        nc.setTournamentId(target.getTournamentId());
        nc.setStageId(target.getId());
        nc.setFromRoster(1L);
        nc.setEntryTag(RosterConstants.ENTRY_GUEST);
        nc.setType(row.guestType == null ? 0L : row.guestType);
        nc.setName(row.guestName);
        nc.setNumber(number);
        nc.setSeedRank(row.seedRank);
        nc.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        nc.setRemark("GUEST");
        competitorMapper.insert(nc);
        if (row.guestPlayerId != null) {
            TCompetitorMember nm = new TCompetitorMember();
            nm.setTenantId(target.getTenantId());
            nm.setTournamentId(target.getTournamentId());
            nm.setCompetitorId(nc.getId());
            nm.setPlayerId(row.guestPlayerId);
            nm.setRole("MEMBER");
            competitorMemberMapper.insert(nm);
        }
        occupiedSeeds.add(row.seedRank);
        log.info("赛段[{}]物化外卡[{}](id={}, 号={}, 种子={})",
            target.getId(), row.guestName, nc.getId(), number, row.seedRank);
        return 1;
    }

    private String nextGuestNumber(TStage stage) {
        List<String> numbers = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .eq(TCompetitor::getStageId, stage.getId())
                .select(TCompetitor::getNumber))
            .stream().map(TCompetitor::getNumber).filter(Objects::nonNull).toList();
        long maxNum = 0L;
        for (String n : numbers) {
            if (n.matches("\\d+")) {
                maxNum = Math.max(maxNum, Long.parseLong(n));
            } else if (n.matches("G\\d+")) {
                maxNum = Math.max(maxNum, Long.parseLong(n.substring(1)));
            }
        }
        return "G" + (maxNum + 1);
    }

    private long nextFreeSeed(Set<Long> occupiedSeeds, int plan) {
        if (plan > 0) {
            for (int s = 1; s <= plan; s++) {
                if (!occupiedSeeds.contains((long) s)) {
                    return s;
                }
            }
            return plan + 1L;
        }
        return occupiedSeeds.stream().mapToLong(Long::longValue).max().orElse(0L) + 1L;
    }

    private Set<String> memberPlayerKeys(List<TCompetitor> competitors) {
        if (competitors.isEmpty()) {
            return Set.of();
        }
        List<Long> compIds = competitors.stream().map(TCompetitor::getId).toList();
        return competitorMemberMapper.selectList(Wrappers.<TCompetitorMember>lambdaQuery()
                .in(TCompetitorMember::getCompetitorId, compIds)
                .select(TCompetitorMember::getPlayerId))
            .stream()
            .map(TCompetitorMember::getPlayerId)
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .collect(Collectors.toSet());
    }

    private Map<Long, List<Long>> memberPlayersOf(List<TCompetitor> competitors) {
        if (competitors.isEmpty()) {
            return Map.of();
        }
        List<Long> compIds = competitors.stream().map(TCompetitor::getId).toList();
        return competitorMemberMapper.selectList(Wrappers.<TCompetitorMember>lambdaQuery()
                .in(TCompetitorMember::getCompetitorId, compIds)
                .select(TCompetitorMember::getCompetitorId, TCompetitorMember::getPlayerId))
            .stream()
            .collect(Collectors.groupingBy(TCompetitorMember::getCompetitorId,
                Collectors.mapping(TCompetitorMember::getPlayerId, Collectors.toList())));
    }

    private void notifyTarget(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage != null) {
            tournamentEventNotifier.notify(stage.getTournamentId(), stageId, null, "stage");
        }
    }

    private void assertSourceBeforeTarget(TStage target, TStage source) {
        if (Objects.equals(source.getId(), target.getId())) {
            throw new ServiceException("来源赛段不能是目标赛段自身");
        }
        if (!Objects.equals(source.getTournamentId(), target.getTournamentId())) {
            throw new ServiceException("来源赛段与目标赛段不属于同一赛事");
        }
        Map<Long, TStage> byId = stageMapper.selectList(Wrappers.<TStage>lambdaQuery()
                .eq(TStage::getTournamentId, target.getTournamentId()))
            .stream().collect(Collectors.toMap(TStage::getId, s -> s, (a, b) -> a));
        Set<Long> visited = new HashSet<>();
        TStage cur = source;
        while (cur != null && visited.add(cur.getId())) {
            if (Objects.equals(cur.getId(), target.getId())) {
                return;
            }
            cur = cur.getNextStageId() == null ? null : byId.get(cur.getNextStageId());
        }
        throw new ServiceException("来源赛段必须位于目标赛段的推进链之前(沿 next 链不可达目标)");
    }
}

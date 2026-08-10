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
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TCompetitorMember;
import com.dance.street.game.domain.TPlayer;
import com.dance.street.game.domain.vo.PreBracketVo;
import com.dance.street.game.domain.vo.StageFlowVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.engine.generator.KnockoutGenerator;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TCompetitorMemberMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TPlayerMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.service.ITStageService;

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
    private final TCompetitorMapper competitorMapper;
    private final TCompetitorMemberMapper competitorMemberMapper;
    private final TPlayerMapper playerMapper;

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
        return baseMapper.selectVoList(lqw);
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

        return MapstructUtils.convert(add, TStageVo.class);
    }

    /**
     * 修改赛段流程
     *
     * @param bo 赛段流程
     * @return 修改后的赛段流程
     */
    @Override
    public TStageVo updateByBo(TStageBo bo) {
        TStage update = MapstructUtils.convert(bo, TStage.class);

        // 归一化 ruleConfig:补齐与 teamCountStart/teamCountEnd 对应的配置字段
        normalizeRuleConfig(update);

        // 校验数据
        validateLinkIds(update);

        // 获取旧数据，用于清理原链表连接
        TStage oldStage = baseMapper.selectById(update.getId());

        // 先清理旧的链表连接
        if (oldStage != null) {
            clearOldLinks(oldStage);
        }

        // 维护新链表
        maintainChainOnInsert(update);

        baseMapper.updateById(update);

        // 更新链表中的相邻节点
        updateNeighborLinks(update);

        return MapstructUtils.convert(update, TStageVo.class);
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
            } else if (StageModeEnum.AUDITION.getCode().equals(mode)) {
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
                prevStage.setNextStageId(oldNextId);
                baseMapper.updateById(prevStage);
            }
        }

        // 如果有后继节点，将它的 prevStageId 指向我们的前驱
        if (oldNextId != null) {
            TStage nextStage = baseMapper.selectById(oldNextId);
            if (nextStage != null && Objects.equals(nextStage.getPrevStageId(), oldId)) {
                nextStage.setPrevStageId(oldPrevId);
                baseMapper.updateById(nextStage);
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
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        if(isValid){
            // 在删除前重新连接链表
            reconnectChainBeforeDelete(ids);
        }
        return baseMapper.deleteByIds(ids) > 0;
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
                    prevStage.setNextStageId(nextId);
                    baseMapper.updateById(prevStage);
                }
            }

            // 将后继节点的 prev 指向前驱节点
            if (nextId != null) {
                TStage nextStage = baseMapper.selectById(nextId);
                if (nextStage != null && Objects.equals(nextStage.getPrevStageId(), stage.getId())) {
                    nextStage.setPrevStageId(prevId);
                    baseMapper.updateById(nextStage);
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

        // 本赛段已有参赛方(已初始化/晋级写入):直接按真实种子返回
        List<TCompetitor> own = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stage.getId())
            .orderByAsc(TCompetitor::getSeedRank)
            .orderByAsc(TCompetitor::getId));
        if (!own.isEmpty()) {
            vo.setStatus("GENERATED");
            vo.setSeededCompetitors(own.stream().map(c -> toPreSeed(c, (long) own.indexOf(c) + 1, null)).toList());
            return vo;
        }

        TStage prev = resolvePrevStage(stage);
        if (prev == null) {
            vo.setStatus("NO_PREV");
            return vo;
        }
        // 预排用于 淘汰赛/海选 → 淘汰赛;小组等其他赛段间不做预排
        if (!StageModeEnum.KNOCKOUT.getCode().equals(stage.getStageMode())
            || (!StageModeEnum.KNOCKOUT.getCode().equals(prev.getStageMode())
                && !StageModeEnum.AUDITION.getCode().equals(prev.getStageMode()))) {
            vo.setStatus("UNSUPPORTED");
            return vo;
        }

        // 上一赛段已标记晋级的参赛方(finalRank 为 null 的排最后,保证稳定顺序)
        List<TCompetitor> advancers = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, prev.getId())
            .eq(TCompetitor::getOutcomeStatus, OutcomeStatusEnum.ADVANCE.getCode()));
        if (advancers.isEmpty()) {
            vo.setStatus("WAIT_PREV");
            return vo;
        }
        advancers.sort(Comparator
            .comparing((TCompetitor c) -> c.getFinalRank() == null ? Long.MAX_VALUE : c.getFinalRank())
            .thenComparing(TCompetitor::getId));

        // 淘汰赛胜者来源场次名(用于"对应位置"展示)
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
            Map<Long, String> matchNameById = prevMatches.stream()
                .collect(Collectors.toMap(TMatch::getId, TMatch::getName));
            for (TMatchParticipant p : parts) {
                if (p.getCompetitorId() != null) {
                    sourceMatch.put(p.getCompetitorId(), matchNameById.get(p.getMatchId()));
                }
            }
            for (TMatch m : prevMatches) {
                if (m.getDisplayRow() != null) {
                    matchPosCount.put(m.getDisplayRow().intValue() + 1,
                        partCountByMatch.getOrDefault(m.getId(), 0L).intValue());
                }
            }
        }

        // 按原始场次位置(finalRank)排布:跳过场次留空,后续胜者不抢占被跳过场次的位置
        int totalSlots = stage.getTeamCountStart() != null && stage.getTeamCountStart() > 0
            ? stage.getTeamCountStart().intValue()
            : Math.max(advancers.size(), prevMatches.size());
        totalSlots = Math.max(1, totalSlots);
        PreBracketVo.PreSeed[] seedArr = new PreBracketVo.PreSeed[totalSlots];
        List<PreBracketVo.PreSeed> extras = new ArrayList<>();
        for (TCompetitor c : advancers) {
            PreBracketVo.PreSeed s = toPreSeed(c, null, sourceMatch.get(c.getId()));
            if (c.getFinalRank() != null && c.getFinalRank() > 0 && c.getFinalRank() <= totalSlots) {
                s.setSeedRank(c.getFinalRank());
                seedArr[c.getFinalRank().intValue() - 1] = s;
            } else {
                extras.add(s);
            }
        }
        for (int i = 0; i < seedArr.length && !extras.isEmpty(); i++) {
            if (seedArr[i] == null) {
                PreBracketVo.PreSeed s = extras.remove(0);
                s.setSeedRank((long) (i + 1));
                seedArr[i] = s;
            }
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
        // 配对模式:显式配置优先;从海选赛进入的淘汰赛默认 SEED(1-16、2-15),否则 SEQUENTIAL(1-2、3-4)
        String pairingMode = null;
        RuleConfigHolder stageRc = RuleConfigParser.parse(stage.getRuleConfig());
        if (stageRc != null && stageRc.getKnockout() != null) {
            pairingMode = stageRc.getKnockout().getPairingMode();
        }
        if (StageModeEnum.KNOCKOUT.getCode().equals(prev.getStageMode())) {
            // 承接上一淘汰赛胜者:按胜者位置顺序配对,覆盖本赛段配置的 SEED
            pairingMode = "SEQUENTIAL";
        } else if (StringUtils.isBlank(pairingMode)) {
            pairingMode = StageModeEnum.AUDITION.getCode().equals(prev.getStageMode()) ? "SEED" : "SEQUENTIAL";
        }
        if ("SEED".equalsIgnoreCase(pairingMode)) {
            int bracketSize = Math.max(2, nextPowerOfTwo(seedArr.length));
            int[] layout = KnockoutGenerator.seedLayout(bracketSize);
            int pairCount = Math.max(1, bracketSize / 2);
            int half = (int) Math.ceil(pairCount / 2.0);
            for (int i = 0; i < pairCount; i++) {
                PreBracketVo.PrePair p = new PreBracketVo.PrePair();
                p.setPosition(i + 1);
                p.setZone(i < half ? "LEFT" : "RIGHT");
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
            return baseMapper.selectById(stage.getPrevStageId());
        }
        List<TStage> all = baseMapper.selectList(Wrappers.<TStage>lambdaQuery()
            .eq(TStage::getTournamentId, stage.getTournamentId())
            .ne(TStage::getStatus, StageConstants.STAGE_DISCARD));
        return all.stream().filter(s -> stage.getId().equals(s.getNextStageId())).findFirst().orElse(null);
    }
}

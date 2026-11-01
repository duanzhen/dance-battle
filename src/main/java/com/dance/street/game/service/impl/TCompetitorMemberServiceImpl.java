package com.dance.street.game.service.impl;

import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.mybatis.core.page.PageQuery;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.dance.street.game.domain.bo.TCompetitorMemberBo;
import com.dance.street.game.domain.vo.TCompetitorMemberVo;
import com.dance.street.game.domain.TCompetitorMember;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TPlayer;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TCompetitorMemberMapper;
import com.dance.street.game.mapper.TPlayerMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.service.ITCompetitorMemberService;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.Objects;

/**
 * 参赛成员关联Service业务层处理
 *
 * @author duane
 * @date 2026-01-06
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TCompetitorMemberServiceImpl implements ITCompetitorMemberService {

    private final TCompetitorMemberMapper baseMapper;
    private final TCompetitorMapper competitorMapper;
    private final TPlayerMapper playerMapper;
    private final TStageMapper stageMapper;

    /**
     * 查询参赛成员关联
     *
     * @param id 主键
     * @return 参赛成员关联
     */
    @Override
    public TCompetitorMemberVo queryById(Long id){
        return baseMapper.selectVoById(id);
    }

    /**
     * 分页查询参赛成员关联列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 参赛成员关联分页列表
     */
    @Override
    public TableDataInfo<TCompetitorMemberVo> queryPageList(TCompetitorMemberBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TCompetitorMember> lqw = buildQueryWrapper(bo);
        Page<TCompetitorMemberVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        return TableDataInfo.build(result);
    }

    /**
     * 查询符合条件的参赛成员关联列表
     *
     * @param bo 查询条件
     * @return 参赛成员关联列表
     */
    @Override
    public List<TCompetitorMemberVo> queryList(TCompetitorMemberBo bo) {
        LambdaQueryWrapper<TCompetitorMember> lqw = buildQueryWrapper(bo);
        return baseMapper.selectVoList(lqw);
    }

    private LambdaQueryWrapper<TCompetitorMember> buildQueryWrapper(TCompetitorMemberBo bo) {
        Map<String, Object> params = bo.getParams();
        LambdaQueryWrapper<TCompetitorMember> lqw = Wrappers.lambdaQuery();
        lqw.orderByAsc(TCompetitorMember::getId);
        lqw.eq(bo.getTournamentId() != null, TCompetitorMember::getTournamentId, bo.getTournamentId());
        lqw.eq(bo.getCompetitorId() != null, TCompetitorMember::getCompetitorId, bo.getCompetitorId());
        lqw.eq(bo.getPlayerId() != null, TCompetitorMember::getPlayerId, bo.getPlayerId());
        lqw.eq(StringUtils.isNotBlank(bo.getRole()), TCompetitorMember::getRole, bo.getRole());
        return lqw;
    }

    /**
     * 新增参赛成员关联
     *
     * @param bo 参赛成员关联
     * @return 新增后的参赛成员关联
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TCompetitorMemberVo insertByBo(TCompetitorMemberBo bo) {
        TCompetitorMember add = MapstructUtils.convert(bo, TCompetitorMember.class);
        TCompetitor competitor = add.getCompetitorId() == null
            ? null : competitorMapper.selectById(add.getCompetitorId());
        TPlayer player = add.getPlayerId() == null ? null : playerMapper.selectById(add.getPlayerId());
        if (competitor == null || player == null) {
            throw new ServiceException("参赛成员关联的参赛方或选手不存在");
        }
        if (add.getTournamentId() == null
            || !Objects.equals(competitor.getTournamentId(), add.getTournamentId())
            || !Objects.equals(player.getTournamentId(), add.getTournamentId())) {
            throw new ServiceException("参赛成员、参赛方和选手必须属于同一赛事");
        }
        TStage stage = competitor.getStageId() == null ? null : stageMapper.selectById(competitor.getStageId());
        if (stage == null) {
            throw new ServiceException("参赛方所属赛段不存在");
        }
        if (StageConstants.STAGE_SETTLED.equals(stage.getStatus())
            || StageConstants.STAGE_DISCARD.equals(stage.getStatus())) {
            throw new ServiceException("赛段[{}]已结束，不能新增参赛成员", stage.getName());
        }
        long duplicate = baseMapper.selectCount(Wrappers.<TCompetitorMember>lambdaQuery()
            .eq(TCompetitorMember::getCompetitorId, add.getCompetitorId())
            .eq(TCompetitorMember::getPlayerId, add.getPlayerId()));
        if (duplicate > 0) {
            throw new ServiceException("选手[{}]已属于参赛方[{}]", add.getPlayerId(), add.getCompetitorId());
        }
        validEntityBeforeSave(add);
        baseMapper.insert(add);
        bo.setId(add.getId());
        return MapstructUtils.convert(add, TCompetitorMemberVo.class);
    }

    /**
     * 修改参赛成员关联
     *
     * @param bo 参赛成员关联
     * @return 修改后的参赛成员关联
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TCompetitorMemberVo updateByBo(TCompetitorMemberBo bo) {
        TCompetitorMember update = MapstructUtils.convert(bo, TCompetitorMember.class);
        TCompetitorMember before = update.getId() == null ? null : baseMapper.selectById(update.getId());
        if (before == null) {
            throw new ServiceException("参赛成员不存在: {}", update.getId());
        }
        if (update.getTournamentId() != null
            && !Objects.equals(before.getTournamentId(), update.getTournamentId())) {
            throw new ServiceException("参赛成员不允许变更所属赛事");
        }
        if (update.getCompetitorId() != null
            && !Objects.equals(before.getCompetitorId(), update.getCompetitorId())) {
            throw new ServiceException("参赛成员不允许变更所属参赛方");
        }
        if (update.getPlayerId() != null && !Objects.equals(before.getPlayerId(), update.getPlayerId())) {
            throw new ServiceException("参赛成员不允许变更关联选手");
        }
        guardMemberEditable(before.getCompetitorId());
        validEntityBeforeSave(update);
        baseMapper.updateById(update);
        return MapstructUtils.convert(update, TCompetitorMemberVo.class);
    }

    /**
     * 保存前的数据校验
     */
    private void validEntityBeforeSave(TCompetitorMember entity){
        //TODO 做一些数据校验,如唯一约束
    }

    /**
     * 校验并批量删除参赛成员关联信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        if (Boolean.TRUE.equals(isValid)) {
            if (ids == null || ids.isEmpty()) {
                throw new ServiceException("参赛成员ID不能为空");
            }
            for (Long id : ids) {
                TCompetitorMember member = baseMapper.selectById(id);
                if (member == null) {
                    continue;
                }
                guardMemberEditable(member.getCompetitorId());
                TPlayer player = playerMapper.selectById(member.getPlayerId());
                if (player != null && Objects.equals(player.getCompetitorId(), member.getCompetitorId())) {
                    throw new ServiceException("选手[{}]仍签到在参赛方[{}]中，请通过解除签到流程处理",
                        player.getName(), member.getCompetitorId());
                }
            }
        }
        return baseMapper.deleteByIds(ids) > 0;
    }

    private void guardMemberEditable(Long competitorId) {
        TCompetitor competitor = competitorId == null ? null : competitorMapper.selectById(competitorId);
        TStage stage = competitor == null || competitor.getStageId() == null
            ? null : stageMapper.selectById(competitor.getStageId());
        if (stage == null || !StageConstants.STAGE_DRAFT.equals(stage.getStatus())) {
            throw new ServiceException("赛段已开始，参赛成员关系已锁定");
        }
    }
}

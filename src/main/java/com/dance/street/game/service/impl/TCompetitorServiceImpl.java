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
import com.dance.street.game.domain.bo.TCompetitorBo;
import com.dance.street.game.domain.vo.TCompetitorVo;
import com.dance.street.game.domain.vo.TCompetitorMemberVo;
import com.dance.street.game.domain.vo.TPlayerVo;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TPlayer;
import com.dance.street.game.domain.TCompetitorMember;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TCompetitorMemberMapper;
import com.dance.street.game.mapper.TPlayerMapper;
import com.dance.street.game.service.ITCompetitorService;

import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * 参赛单位Service业务层处理
 *
 * @author duane
 * @date 2026-01-06
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TCompetitorServiceImpl implements ITCompetitorService {

    private final TCompetitorMapper baseMapper;
    private final TCompetitorMemberMapper competitorMemberMapper;
    private final TPlayerMapper playerMapper;

    /**
     * 查询参赛单位
     *
     * @param id 主键
     * @return 参赛单位
     */
    @Override
    public TCompetitorVo queryById(Long id){
        return baseMapper.selectVoById(id);
    }

    /**
     * 分页查询参赛单位列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 参赛单位分页列表
     */
    @Override
    public TableDataInfo<TCompetitorVo> queryPageList(TCompetitorBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TCompetitor> lqw = buildQueryWrapper(bo);
        Page<TCompetitorVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);

        // 填充 playerList
        if (!result.getRecords().isEmpty()) {
            List<Long> competitorIds = result.getRecords().stream()
                .map(TCompetitorVo::getId)
                .collect(Collectors.toList());

            // 查询参赛成员关联
            LambdaQueryWrapper<TCompetitorMember> memberLqw = Wrappers.lambdaQuery();
            memberLqw.in(TCompetitorMember::getCompetitorId, competitorIds);
            List<TCompetitorMemberVo> members = competitorMemberMapper.selectVoList(memberLqw);

            if (!members.isEmpty()) {
                // 收集所有 playerId
                List<Long> playerIds = members.stream()
                    .map(TCompetitorMemberVo::getPlayerId)
                    .collect(Collectors.toList());

                // 查询玩家信息
                LambdaQueryWrapper<TPlayer> playerLqw = Wrappers.lambdaQuery();
                playerLqw.in(TPlayer::getId, playerIds);
                List<TPlayerVo> players = playerMapper.selectVoList(playerLqw);

                // 按 competitorId 分组玩家
                Map<Long, List<TPlayerVo>> competitorPlayersMap = members.stream()
                    .collect(Collectors.groupingBy(
                        TCompetitorMemberVo::getCompetitorId,
                        Collectors.mapping(
                            member -> players.stream()
                                .filter(p -> p.getId().equals(member.getPlayerId()))
                                .findFirst()
                                .orElse(null),
                            Collectors.toList()
                        )
                    ));

                // 填充 playerList
                result.getRecords().forEach(competitor -> {
                    List<TPlayerVo> playerList = competitorPlayersMap.get(competitor.getId());
                    if (playerList != null) {
                        competitor.setPlayerList(playerList);
                    }
                });
            }
        }

        return TableDataInfo.build(result);
    }

    /**
     * 查询符合条件的参赛单位列表
     *
     * @param bo 查询条件
     * @return 参赛单位列表
     */
    @Override
    public List<TCompetitorVo> queryList(TCompetitorBo bo) {
        LambdaQueryWrapper<TCompetitor> lqw = buildQueryWrapper(bo);
        return baseMapper.selectVoList(lqw);
    }

    private LambdaQueryWrapper<TCompetitor> buildQueryWrapper(TCompetitorBo bo) {
        Map<String, Object> params = bo.getParams();
        LambdaQueryWrapper<TCompetitor> lqw = Wrappers.lambdaQuery();
        lqw.orderByAsc(TCompetitor::getId);
        lqw.eq(bo.getTournamentId() != null, TCompetitor::getTournamentId, bo.getTournamentId());
        lqw.eq(bo.getStageId() != null, TCompetitor::getStageId, bo.getStageId());
        lqw.eq(bo.getSourceCompetitorId() != null, TCompetitor::getSourceCompetitorId, bo.getSourceCompetitorId());
        lqw.eq(bo.getType() != null, TCompetitor::getType, bo.getType());
        lqw.like(StringUtils.isNotBlank(bo.getName()), TCompetitor::getName, bo.getName());
        lqw.eq(bo.getSeedRank() != null, TCompetitor::getSeedRank, bo.getSeedRank());
        lqw.eq(bo.getFinalRank() != null, TCompetitor::getFinalRank, bo.getFinalRank());
        lqw.eq(StringUtils.isNotBlank(bo.getOutcomeStatus()), TCompetitor::getOutcomeStatus, bo.getOutcomeStatus());
        return lqw;
    }

    /**
     * 新增参赛单位
     *
     * @param bo 参赛单位
     * @return 新增后的参赛单位
     */
    @Override
    public TCompetitorVo insertByBo(TCompetitorBo bo) {
        TCompetitor add = MapstructUtils.convert(bo, TCompetitor.class);
        // 签到/新增的参赛单位默认处于待比赛状态,否则赛段初始化(只认 PENDING)会找不到人
        if (StringUtils.isBlank(add.getOutcomeStatus())) {
            add.setOutcomeStatus(OutcomeStatusEnum.PENDING.getCode());
        }
        validEntityBeforeSave(add);
        baseMapper.insert(add);
        bo.setId(add.getId());
        return MapstructUtils.convert(add, TCompetitorVo.class);
    }

    /**
     * 修改参赛单位
     *
     * @param bo 参赛单位
     * @return 修改后的参赛单位
     */
    @Override
    public TCompetitorVo updateByBo(TCompetitorBo bo) {
        TCompetitor update = MapstructUtils.convert(bo, TCompetitor.class);
        validEntityBeforeSave(update);
        baseMapper.updateById(update);
        return MapstructUtils.convert(update, TCompetitorVo.class);
    }

    /**
     * 保存前的数据校验
     */
    private void validEntityBeforeSave(TCompetitor entity){
        //TODO 做一些数据校验,如唯一约束
    }

    /**
     * 校验并批量删除参赛单位信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    @Override
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        if(isValid){
            //TODO 做一些业务上的校验,判断是否需要校验
        }
        return baseMapper.deleteByIds(ids) > 0;
    }

    /**
     * 根据ID集合批量查询参赛单位
     *
     * @param ids ID集合
     * @return 参赛单位列表
     */
    @Override
    public List<TCompetitorVo> listByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<TCompetitor> lqw = Wrappers.lambdaQuery();
        lqw.in(TCompetitor::getId, ids);
        return baseMapper.selectVoList(lqw);
    }
}

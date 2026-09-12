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
import com.dance.street.game.service.TournamentEventNotifier;

import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.Objects;
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
    private final TournamentEventNotifier tournamentEventNotifier;

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
        // 改名联动 + 广播:仅在参赛单位名称实际变化时触发
        // (赛段配置内单独改名时传 syncPlayerName=false 跳过联动,广播仍保留)
        boolean renamed = false;
        if (update.getId() != null && StringUtils.isNotBlank(update.getName())) {
            TCompetitor before = baseMapper.selectById(update.getId());
            if (before != null && !Objects.equals(before.getName(), update.getName())) {
                renamed = true;
                if (!Boolean.FALSE.equals(bo.getSyncPlayerName())) {
                    syncLinkedPlayerName(update.getId(), update.getName());
                }
            }
        }
        baseMapper.updateById(update);
        if (renamed) {
            tournamentEventNotifier.notify(update.getTournamentId(), update.getStageId(), null, "competitor");
        }
        return MapstructUtils.convert(update, TCompetitorVo.class);
    }

    /**
     * 参赛单位改名联动:名下仅有一个选手时,同步更新该选手姓名。
     * 多成员参赛方(如组队报名)不联动,避免把参赛方名覆盖到个人档案。
     */
    private void syncLinkedPlayerName(Long competitorId, String newName) {
        List<TCompetitorMember> members = competitorMemberMapper.selectList(Wrappers.<TCompetitorMember>lambdaQuery()
            .eq(TCompetitorMember::getCompetitorId, competitorId));
        if (members == null || members.size() != 1 || members.get(0).getPlayerId() == null) {
            return;
        }
        Long playerId = members.get(0).getPlayerId();
        TPlayer player = playerMapper.selectById(playerId);
        if (player != null && !Objects.equals(player.getName(), newName)) {
            TPlayer upd = new TPlayer();
            upd.setId(playerId);
            upd.setName(newName);
            playerMapper.updateById(upd);
            log.info("参赛单位[{}]改名[{}],名下唯一选手[{}]已联动改名", competitorId, newName, playerId);
        }
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

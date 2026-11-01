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
import com.dance.street.game.domain.bo.TRoundScoreBo;
import com.dance.street.game.domain.vo.TRoundScoreVo;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.service.ITRoundScoreService;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;

/**
 * 轮次打分结果Service业务层处理
 *
 * @author duane
 * @date 2026-01-11
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TRoundScoreServiceImpl implements ITRoundScoreService {

    private final TRoundScoreMapper baseMapper;
    private final TMatchRoundMapper roundMapper;
    private final TMatchMapper matchMapper;
    private final TStageMapper stageMapper;
    private final TMatchParticipantMapper participantMapper;
    private static final Set<String> SCORE_ACTIONS = Set.of(
        StageConstants.SCORE_ACTION_SCORE,
        StageConstants.SCORE_ACTION_VOTE
    );

    /**
     * 查询轮次打分结果
     *
     * @param id 主键
     * @return 轮次打分结果
     */
    @Override
    public TRoundScoreVo queryById(Long id){
        return baseMapper.selectVoById(id);
    }

    /**
     * 分页查询轮次打分结果列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 轮次打分结果分页列表
     */
    @Override
    public TableDataInfo<TRoundScoreVo> queryPageList(TRoundScoreBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TRoundScore> lqw = buildQueryWrapper(bo);
        Page<TRoundScoreVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        return TableDataInfo.build(result);
    }

    /**
     * 查询符合条件的轮次打分结果列表
     *
     * @param bo 查询条件
     * @return 轮次打分结果列表
     */
    @Override
    public List<TRoundScoreVo> queryList(TRoundScoreBo bo) {
        LambdaQueryWrapper<TRoundScore> lqw = buildQueryWrapper(bo);
        return baseMapper.selectVoList(lqw);
    }

    private LambdaQueryWrapper<TRoundScore> buildQueryWrapper(TRoundScoreBo bo) {
        Map<String, Object> params = bo.getParams();
        LambdaQueryWrapper<TRoundScore> lqw = Wrappers.lambdaQuery();
        lqw.orderByAsc(TRoundScore::getId);
        lqw.eq(bo.getTournamentId() != null, TRoundScore::getTournamentId, bo.getTournamentId());
        lqw.eq(bo.getRoundId() != null, TRoundScore::getRoundId, bo.getRoundId());
        lqw.eq(bo.getCompetitorId() != null, TRoundScore::getCompetitorId, bo.getCompetitorId());
        lqw.eq(StringUtils.isNotBlank(bo.getAction()), TRoundScore::getAction, bo.getAction());
        lqw.eq(bo.getRefereeId() != null, TRoundScore::getRefereeId, bo.getRefereeId());
        lqw.eq(bo.getScore() != null, TRoundScore::getScore, bo.getScore());
        lqw.eq(StringUtils.isNotBlank(bo.getDimension()), TRoundScore::getDimension, bo.getDimension());
        return lqw;
    }

    /**
     * 新增轮次打分结果
     *
     * @param bo 轮次打分结果
     * @return 是否新增成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean insertByBo(TRoundScoreBo bo) {
        TRoundScore add = MapstructUtils.convert(bo, TRoundScore.class);
        if (add.getTournamentId() == null || add.getRoundId() == null || add.getCompetitorId() == null) {
            throw new ServiceException("打分必须同时包含赛事、轮次和参赛方");
        }
        TMatchRound round = roundMapper.selectById(add.getRoundId());
        TMatch match = round == null ? null : matchMapper.selectById(round.getMatchId());
        if (match == null) {
            throw new ServiceException("打分所属场次不存在");
        }
        if (!Objects.equals(match.getTournamentId(), add.getTournamentId())
            || !Objects.equals(round.getTournamentId(), add.getTournamentId())) {
            throw new ServiceException("打分、轮次和场次不属于同一赛事");
        }
        long attached = participantMapper.selectCount(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, match.getId())
            .eq(TMatchParticipant::getCompetitorId, add.getCompetitorId())
            .eq(TMatchParticipant::getTournamentId, add.getTournamentId()));
        if (attached == 0) {
            throw new ServiceException("参赛方[{}]不属于场次[{}]，不能写入打分",
                add.getCompetitorId(), match.getId());
        }
        guardScoreGaming(add.getRoundId());
        if (StringUtils.isBlank(add.getAction())) {
            add.setAction(StageConstants.SCORE_ACTION_SCORE);
        }
        if (StringUtils.isBlank(add.getDimension())) {
            add.setDimension(StageConstants.DIMENSION_MAIN);
        }
        if (!SCORE_ACTIONS.contains(add.getAction())) {
            throw new ServiceException("不支持的打分动作: {}", add.getAction());
        }
        guardScoreValue(add.getScore());
        validEntityBeforeSave(add);
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
        }
        return flag;
    }

    /**
     * 修改轮次打分结果
     *
     * @param bo 轮次打分结果
     * @return 是否修改成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateByBo(TRoundScoreBo bo) {
        TRoundScore update = MapstructUtils.convert(bo, TRoundScore.class);
        TRoundScore before = update.getId() == null ? null : baseMapper.selectById(update.getId());
        if (before == null) {
            throw new ServiceException("打分记录不存在: {}", update.getId());
        }
        if (update.getTournamentId() != null
            && !Objects.equals(before.getTournamentId(), update.getTournamentId())) {
            throw new ServiceException("打分记录不允许变更所属赛事");
        }
        if (update.getRoundId() != null && !Objects.equals(before.getRoundId(), update.getRoundId())) {
            throw new ServiceException("打分记录不允许变更所属轮次");
        }
        if (update.getCompetitorId() != null
            && !Objects.equals(before.getCompetitorId(), update.getCompetitorId())) {
            throw new ServiceException("打分记录不允许变更参赛方");
        }
        guardScoreGaming(before.getRoundId());
        if (update.getAction() != null && !SCORE_ACTIONS.contains(update.getAction())) {
            throw new ServiceException("不支持的打分动作: {}", update.getAction());
        }
        guardScoreValue(update.getScore() == null ? before.getScore() : update.getScore());
        validEntityBeforeSave(update);
        return baseMapper.updateById(update) > 0;
    }

    /**
     * 保存前的数据校验
     */
    private void validEntityBeforeSave(TRoundScore entity){
        //TODO 做一些数据校验,如唯一约束
    }

    /**
     * 校验并批量删除轮次打分结果信息
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
                throw new ServiceException("打分ID不能为空");
            }
            for (Long id : ids) {
                TRoundScore score = baseMapper.selectById(id);
                if (score != null) {
                    guardScoreGaming(score.getRoundId());
                }
            }
        }
        return baseMapper.deleteByIds(ids) > 0;
    }

    private void guardScoreGaming(Long roundId) {
        TMatchRound round = roundId == null ? null : roundMapper.selectById(roundId);
        TMatch match = round == null ? null : matchMapper.selectById(round.getMatchId());
        TStage stage = match == null ? null : stageMapper.selectById(match.getStageId());
        if (stage == null
            || !StageConstants.STAGE_GAMING.equals(stage.getStatus())
            || !StageConstants.MATCH_GAMING.equals(match.getStatus())
            || !StageConstants.MATCH_GAMING.equals(round.getStatus())) {
            throw new ServiceException("仅进行中的赛段、场次和轮次可以写入或修改原始分数");
        }
    }

    private static void guardScoreValue(java.math.BigDecimal score) {
        if (score == null) {
            throw new ServiceException("分数不能为空");
        }
        if (score.signum() < 0) {
            throw new ServiceException("分数不能为负数");
        }
    }
}

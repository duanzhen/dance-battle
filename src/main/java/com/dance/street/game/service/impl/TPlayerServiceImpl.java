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
import com.dance.street.game.domain.bo.TPlayerBo;
import com.dance.street.game.domain.bo.CheckInBo;
import com.dance.street.game.domain.bo.CheckInEditBo;
import com.dance.street.game.domain.bo.TCompetitorBo;
import com.dance.street.game.domain.bo.TCompetitorMemberBo;
import com.dance.street.game.domain.vo.PlayerImportVo;
import com.dance.street.game.domain.vo.TPlayerVo;
import com.dance.street.game.domain.vo.TCompetitorVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.domain.vo.TTournamentVo;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TPlayer;
import com.dance.street.game.domain.TCompetitorMember;
import com.dance.street.game.mapper.TPlayerMapper;
import com.dance.street.game.mapper.TCompetitorMemberMapper;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.service.ITPlayerService;
import com.dance.street.game.service.ITCompetitorService;
import com.dance.street.game.service.ITTournamentService;
import com.dance.street.game.service.ITStageService;
import com.dance.street.game.service.ITCompetitorMemberService;
import com.dance.street.game.service.ITStageLifecycleService;
import com.dance.street.game.service.TournamentEventNotifier;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 选手自然人Service业务层处理
 *
 * @author duane
 * @date 2026-01-06
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TPlayerServiceImpl implements ITPlayerService {

    private final TPlayerMapper baseMapper;
    private final ITCompetitorService competitorService;
    private final TCompetitorMapper competitorMapper;
    private final ITTournamentService tournamentService;
    private final ITStageService stageService;
    private final ITCompetitorMemberService competitorMemberService;
    private final TCompetitorMemberMapper competitorMemberMapper;
    private final ITStageLifecycleService stageLifecycleService;
    private final TournamentEventNotifier tournamentEventNotifier;

    /**
     * 查询选手自然人
     *
     * @param id 主键
     * @return 选手自然人
     */
    @Override
    public TPlayerVo queryById(Long id){
        return baseMapper.selectVoById(id);
    }

    /**
     * 分页查询选手自然人列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 选手自然人分页列表
     */
    @Override
    public TableDataInfo<TPlayerVo> queryPageList(TPlayerBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TPlayer> lqw = buildQueryWrapper(bo);
        Page<TPlayerVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);

        // 填充 competitorVo
        fillCompetitorVo(result.getRecords());

        return TableDataInfo.build(result);
    }

    /**
     * 查询符合条件的选手自然人列表
     *
     * @param bo 查询条件
     * @return 选手自然人列表
     */
    @Override
    public List<TPlayerVo> queryList(TPlayerBo bo) {
        LambdaQueryWrapper<TPlayer> lqw = buildQueryWrapper(bo);
        List<TPlayerVo> list = baseMapper.selectVoList(lqw);

        // 填充 competitorVo
        fillCompetitorVo(list);

        return list;
    }

    /**
     * 填充 competitorVo
     *
     * @param playerList 选手列表
     */
    private void fillCompetitorVo(List<TPlayerVo> playerList) {
        if (playerList == null || playerList.isEmpty()) {
            return;
        }

        // 收集所有 competitorId
        List<Long> competitorIds = playerList.stream()
            .map(TPlayerVo::getCompetitorId)
            .filter(id -> id != null)
            .distinct()
            .collect(Collectors.toList());

        if (competitorIds.isEmpty()) {
            return;
        }

        // 批量查询参赛单位
        List<TCompetitorVo> competitors = competitorService.listByIds(competitorIds);

        // 构建 id -> competitorVo 的映射
        Map<Long, TCompetitorVo> competitorMap = competitors.stream()
            .collect(Collectors.toMap(TCompetitorVo::getId, c -> c));

        // 填充 competitorVo
        playerList.forEach(player -> {
            if (player.getCompetitorId() != null) {
                player.setCompetitorVo(competitorMap.get(player.getCompetitorId()));
            }
        });
    }

    private LambdaQueryWrapper<TPlayer> buildQueryWrapper(TPlayerBo bo) {
        Map<String, Object> params = bo.getParams();
        LambdaQueryWrapper<TPlayer> lqw = Wrappers.lambdaQuery();
        lqw.orderByAsc(TPlayer::getId);
        lqw.eq(bo.getTournamentId() != null, TPlayer::getTournamentId, bo.getTournamentId());
        lqw.like(StringUtils.isNotBlank(bo.getName()), TPlayer::getName, bo.getName());
        lqw.eq(StringUtils.isNotBlank(bo.getAvatar()), TPlayer::getAvatar, bo.getAvatar());
        lqw.eq(StringUtils.isNotBlank(bo.getIdCard()), TPlayer::getIdCard, bo.getIdCard());
        lqw.eq(StringUtils.isNotBlank(bo.getTags()), TPlayer::getTags, bo.getTags());
        return lqw;
    }

    /**
     * 新增选手自然人
     *
     * @param bo 选手自然人
     * @return 新增后的选手自然人
     */
    @Override
    public TPlayerVo insertByBo(TPlayerBo bo) {
        TPlayer add = MapstructUtils.convert(bo, TPlayer.class);
        validEntityBeforeSave(add);
        baseMapper.insert(add);
        bo.setId(add.getId());
        return MapstructUtils.convert(add, TPlayerVo.class);
    }

    /**
     * 修改选手自然人
     *
     * @param bo 选手自然人
     * @return 修改后的选手自然人
     */
    @Override
    public TPlayerVo updateByBo(TPlayerBo bo) {
        TPlayer update = MapstructUtils.convert(bo, TPlayer.class);
        validEntityBeforeSave(update);
        // 改名联动:选手姓名更新后,若其所属参赛单位名下只有这一个选手,同步更新参赛单位名称
        if (update.getId() != null && StringUtils.isNotBlank(update.getName())) {
            TPlayer before = baseMapper.selectById(update.getId());
            if (before != null && before.getCompetitorId() != null
                    && !Objects.equals(before.getName(), update.getName())) {
                syncLinkedCompetitorName(before.getCompetitorId(), update.getName(), before.getId());
            }
        }
        baseMapper.updateById(update);
        return MapstructUtils.convert(update, TPlayerVo.class);
    }

    /**
     * 选手改名联动:所属参赛单位名下仅有一个选手(且就是本人)时,同步更新参赛单位名称。
     * 多成员参赛方(如组队报名)不联动,避免个人改名覆盖参赛方名。
     */
    private void syncLinkedCompetitorName(Long competitorId, String newName, Long playerId) {
        List<TCompetitorMember> members = competitorMemberMapper.selectList(Wrappers.<TCompetitorMember>lambdaQuery()
            .eq(TCompetitorMember::getCompetitorId, competitorId));
        if (members == null || members.size() != 1
                || !Objects.equals(members.get(0).getPlayerId(), playerId)) {
            return;
        }
        TCompetitor competitor = competitorMapper.selectById(competitorId);
        if (competitor != null && !Objects.equals(competitor.getName(), newName)) {
            TCompetitor upd = new TCompetitor();
            upd.setId(competitorId);
            upd.setName(newName);
            competitorMapper.updateById(upd);
            // 联动改名后广播,让裁判端/导播台等实时刷新
            tournamentEventNotifier.notify(competitor.getTournamentId(), competitor.getStageId(), null, "competitor");
            log.info("选手[{}]改名[{}],所属参赛单位[{}]已联动改名", playerId, newName, competitorId);
        }
    }

    /**
     * 保存前的数据校验
     */
    private void validEntityBeforeSave(TPlayer entity){
        //TODO 做一些数据校验,如唯一约束
    }

    /**
     * 校验并批量删除选手自然人信息
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
     * 选手签到
     *
     * @param bo 签到请求
     * @return 签到后的选手信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TPlayerVo checkIn(CheckInBo bo) {
        // 1. 根据playerId查询player信息
        TPlayer player = baseMapper.selectById(bo.getPlayerId());
        if (player == null) {
            throw new RuntimeException("选手不存在");
        }

        // 2. 检查是否已经签到
        if (player.getCompetitorId() != null) {
            throw new RuntimeException("该选手已经签到过了");
        }

        Long tournamentId = player.getTournamentId();
        Long playerId = player.getId();

        // 3. 查询赛事信息
        TTournamentVo tournament = tournamentService.queryById(tournamentId);
        if (tournament == null) {
            throw new RuntimeException("赛事不存在");
        }

        // 4. 查询第一个赛段信息
        TStageVo firstStage = stageService.getFirstStageByTournamentId(tournamentId);
        if (firstStage == null) {
            throw new RuntimeException("赛事没有设置赛段");
        }
        // 海选/排名赛(首个赛段)已结束时禁止继续签到:迟到者无法再参与打分与后续晋级
        if ((StageModeEnum.AUDITION.getCode().equals(firstStage.getStageMode())
                || StageModeEnum.RANK.getCode().equals(firstStage.getStageMode()))
            && (StageConstants.STAGE_SETTLED.equals(firstStage.getStatus())
                || StageConstants.STAGE_DISCARD.equals(firstStage.getStatus()))) {
            throw new RuntimeException("海选/排名赛已结束，无法继续签到");
        }

        Long competitorId;
        String checkInType = bo.getCheckInType();

        if ("CREATE".equalsIgnoreCase(checkInType)) {
            // 5.1 新建competitor
            if (bo.getCompetitorNumber() == null || bo.getCompetitorNumber().isBlank()) {
                throw new RuntimeException("新建参赛单位时，选手号不能为空");
            }

            TCompetitorBo competitorBo = new TCompetitorBo();
            competitorBo.setTournamentId(tournamentId);
            competitorBo.setStageId(firstStage.getId());
            competitorBo.setType(0L); // 个人
            competitorBo.setName(player.getName());
            competitorBo.setNumber(bo.getCompetitorNumber());

            TCompetitorVo competitorVo = competitorService.insertByBo(competitorBo);
            competitorId = competitorVo.getId();

            // 创建competitor_member关联
            TCompetitorMemberBo memberBo = new TCompetitorMemberBo();
            memberBo.setTournamentId(tournamentId);
            memberBo.setCompetitorId(competitorId);
            memberBo.setPlayerId(playerId);
            memberBo.setRole("MEMBER");
            competitorMemberService.insertByBo(memberBo);

            // 海选/排名赛已开始(场次已生成):把新签到选手挂入当前人数最少的圈场次,可被裁判打分并参与结算
            if (StageModeEnum.AUDITION.getCode().equals(firstStage.getStageMode())
                || StageModeEnum.RANK.getCode().equals(firstStage.getStageMode())) {
                // 线下抽签指定圈(matchId)时挂入指定圈,否则系统按各圈剩余名额自动择优
                stageLifecycleService.appendStageCompetitor(firstStage.getId(), competitorId, bo.getMatchId(), bo.getZoneIndex());
            }

        } else if ("JOIN".equalsIgnoreCase(checkInType)) {
            // 5.2 加入已有competitor
            if (bo.getCompetitorId() == null) {
                throw new RuntimeException("加入已有参赛单位时，参赛单位ID不能为空");
            }

            competitorId = bo.getCompetitorId();
            TCompetitorVo competitorVo = competitorService.queryById(competitorId);
            if (competitorVo == null) {
                throw new RuntimeException("参赛单位不存在");
            }

            if (!competitorVo.getStageId().equals(firstStage.getId())) {
                throw new RuntimeException("只能加入首个赛段的参赛单位");
            }

            // 检查是否达到最大人数
            Long currentMemberCount = getCompetitorMemberCount(competitorId);
            Long maxMembers = firstStage.getMembers();

            if (maxMembers != null && currentMemberCount >= maxMembers) {
                throw new RuntimeException("该参赛单位已达到最大人数限制");
            }

            // 创建competitor_member关联
            TCompetitorMemberBo memberBo = new TCompetitorMemberBo();
            memberBo.setTournamentId(tournamentId);
            memberBo.setCompetitorId(competitorId);
            memberBo.setPlayerId(playerId);
            memberBo.setRole("MEMBER");
            competitorMemberService.insertByBo(memberBo);

        } else {
            throw new RuntimeException("无效的签到类型: " + checkInType);
        }

        // 6. 更新player的competitorId、name、avatar
        player.setCompetitorId(competitorId);
        if (bo.getName() != null && !bo.getName().isBlank()) {
            player.setName(bo.getName().trim());
        }
        if (bo.getAvatar() != null && !bo.getAvatar().isBlank()) {
            player.setAvatar(bo.getAvatar().trim());
        }
        baseMapper.updateById(player);

        // 签到成功:广播赛事事件,通知海选/排名赛参赛选手列表等页面实时更新
        tournamentEventNotifier.notify(tournamentId, firstStage.getId(), null, "competitor");

        return baseMapper.selectVoById(playerId);
    }

    /**
     * 编辑签到结果:已签到选手改号码(按号分圈自动换圈/随机分圈指定圈)或改名/头像。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TPlayerVo editCheckIn(CheckInEditBo bo) {
        TPlayer player = baseMapper.selectById(bo.getPlayerId());
        if (player == null) {
            throw new RuntimeException("选手不存在");
        }
        if (player.getCompetitorId() == null) {
            throw new RuntimeException("该选手尚未签到,请先签到");
        }
        TCompetitor competitor = competitorMapper.selectById(player.getCompetitorId());
        if (competitor == null) {
            throw new RuntimeException("选手对应的参赛单位不存在");
        }
        TTournamentVo tournament = tournamentService.queryById(player.getTournamentId());
        if (tournament == null) {
            throw new RuntimeException("赛事不存在");
        }
        TStageVo firstStage = stageService.getFirstStageByTournamentId(player.getTournamentId());
        if (firstStage == null) {
            throw new RuntimeException("赛事没有设置赛段");
        }
        if (!Objects.equals(competitor.getStageId(), firstStage.getId())) {
            throw new RuntimeException("只能编辑首个赛段的签到结果");
        }
        if (StageConstants.STAGE_SETTLED.equals(firstStage.getStatus())
            || StageConstants.STAGE_DISCARD.equals(firstStage.getStatus())) {
            throw new RuntimeException("赛段已结束,无法编辑签到结果");
        }
        if (StageConstants.STAGE_GAMING.equals(firstStage.getStatus())) {
            throw new RuntimeException("赛段已开始,签到结果已锁定:开赛后仅支持新增签到,不能编辑/换号/换圈");
        }

        boolean numberChanged = false;
        if (StringUtils.isNotBlank(bo.getCompetitorNumber())) {
            String newNumber = bo.getCompetitorNumber().trim();
            if (!Objects.equals(competitor.getNumber(), newNumber)) {
                long members = competitorMemberMapper.selectCount(Wrappers.<TCompetitorMember>lambdaQuery()
                    .eq(TCompetitorMember::getCompetitorId, competitor.getId()));
                if (members > 1) {
                    throw new RuntimeException("该参赛单位包含多名选手,不能直接改号;请先解除本选手签到后重新签到");
                }
                long occupied = competitorMapper.selectCount(Wrappers.<TCompetitor>lambdaQuery()
                    .eq(TCompetitor::getTournamentId, player.getTournamentId())
                    .eq(TCompetitor::getStageId, firstStage.getId())
                    .eq(TCompetitor::getNumber, newNumber)
                    .ne(TCompetitor::getId, competitor.getId()));
                if (occupied > 0) {
                    throw new RuntimeException("号码 " + newNumber + " 已被占用,请选择其他号码");
                }
                TCompetitor upd = new TCompetitor();
                upd.setId(competitor.getId());
                upd.setNumber(newNumber);
                competitorMapper.updateById(upd);
                competitor.setNumber(newNumber);
                numberChanged = true;
            }
        }
        // 改号或指定换圈时,把参赛方从旧场次挪到新号码对应圈/目标圈并重排
        if (numberChanged || bo.getMatchId() != null) {
            stageLifecycleService.relocateCheckInCompetitor(firstStage.getId(), competitor.getId(), bo.getMatchId());
        }

        String oldName = player.getName();
        if (StringUtils.isNotBlank(bo.getName())) {
            String newName = bo.getName().trim();
            player.setName(newName);
            if (!Objects.equals(oldName, newName)) {
                syncLinkedCompetitorName(competitor.getId(), newName, player.getId());
            }
        }
        if (StringUtils.isNotBlank(bo.getAvatar())) {
            player.setAvatar(bo.getAvatar().trim());
        }
        baseMapper.updateById(player);
        tournamentEventNotifier.notify(player.getTournamentId(), firstStage.getId(), null, "competitor");
        return baseMapper.selectVoById(player.getId());
    }

    /**
     * 解除签到:撤销选手与首个赛段参赛单位的关联。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelCheckIn(Long playerId) {
        TPlayer player = baseMapper.selectById(playerId);
        if (player == null) {
            throw new RuntimeException("选手不存在");
        }
        if (player.getCompetitorId() == null) {
            throw new RuntimeException("该选手尚未签到");
        }
        TCompetitor competitor = competitorMapper.selectById(player.getCompetitorId());
        if (competitor == null) {
            // 历史脏数据:参赛单位已被删除但选手仍残留 competitor_id,
            // 直接清空关联,避免旧版本留下"已签到但找不到参赛单位"的死状态
            log.warn("选手[{}]解除签到:参赛单位[{}]已不存在,仅清理选手关联",
                playerId, player.getCompetitorId());
            baseMapper.update(null, Wrappers.<TPlayer>lambdaUpdate()
                .eq(TPlayer::getId, playerId)
                .set(TPlayer::getCompetitorId, null));
            tournamentEventNotifier.notify(player.getTournamentId(), null, null, "competitor");
            return;
        }
        TTournamentVo tournament = tournamentService.queryById(player.getTournamentId());
        if (tournament == null) {
            throw new RuntimeException("赛事不存在");
        }
        TStageVo firstStage = stageService.getFirstStageByTournamentId(player.getTournamentId());
        if (firstStage == null) {
            throw new RuntimeException("赛事没有设置赛段");
        }
        if (StageConstants.STAGE_SETTLED.equals(firstStage.getStatus())
            || StageConstants.STAGE_DISCARD.equals(firstStage.getStatus())) {
            throw new RuntimeException("赛段已结束,无法解除签到");
        }
        if (StageConstants.STAGE_GAMING.equals(firstStage.getStatus())) {
            throw new RuntimeException("赛段已开始,签到结果已锁定:开赛后仅支持新增签到,不能解除签到");
        }

        // 名下还有其他成员(多成员参赛方):仅解除该选手本人,参赛单位及其场次保留
        long memberCount = competitorMemberMapper.selectCount(Wrappers.<TCompetitorMember>lambdaQuery()
            .eq(TCompetitorMember::getCompetitorId, competitor.getId()));
        if (memberCount > 1) {
            competitorMemberMapper.delete(Wrappers.<TCompetitorMember>lambdaQuery()
                .eq(TCompetitorMember::getCompetitorId, competitor.getId())
                .eq(TCompetitorMember::getPlayerId, playerId));
        } else {
            // 最后一名成员:从圈场次移除并清理参赛单位(已有打分记录时由底层拦截)
            stageLifecycleService.removeCheckInCompetitor(firstStage.getId(), competitor.getId());
            competitorMemberMapper.delete(Wrappers.<TCompetitorMember>lambdaQuery()
                .eq(TCompetitorMember::getCompetitorId, competitor.getId()));
            competitorMapper.deleteById(competitor.getId());
        }
        // updateById 默认忽略 null 字段,必须显式 set 才能清空 competitor_id
        baseMapper.update(null, Wrappers.<TPlayer>lambdaUpdate()
            .eq(TPlayer::getId, playerId)
            .set(TPlayer::getCompetitorId, null));
        tournamentEventNotifier.notify(player.getTournamentId(), firstStage.getId(), null, "competitor");
        log.info("选手[{}]已解除签到(参赛单位[{}])", playerId, competitor.getId());
    }

    /**
     * 获取参赛单位当前成员数量
     *
     * @param competitorId 参赛单位ID
     * @return 成员数量
     */
    private Long getCompetitorMemberCount(Long competitorId) {
        LambdaQueryWrapper<TCompetitorMember> lqw = Wrappers.lambdaQuery();
        lqw.eq(TCompetitorMember::getCompetitorId, competitorId);
        return competitorMemberMapper.selectCount(lqw);
    }

    /**
     * 批量导入选手
     */
    @Override
    public int importPlayers(List<PlayerImportVo> list, Long tournamentId) {
        int count = 0;
        for (PlayerImportVo vo : list) {
            String name = vo.getName();
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            TPlayer player = new TPlayer();
            player.setTournamentId(tournamentId);
            player.setName(name.trim());
            player.setIdCard(vo.getIdCard());
            player.setTags(vo.getTags());
            player.setRemark(vo.getRemark());
            baseMapper.insert(player);
            count++;
        }
        return count;
    }
}

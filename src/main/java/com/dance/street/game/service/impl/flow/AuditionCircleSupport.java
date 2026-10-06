package com.dance.street.game.service.impl.flow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.StageFlowSupport;
import com.dance.street.game.engine.common.enums.MatchModeEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.service.ITRefereeStageService;
import com.dance.street.game.service.impl.settle.SettlementSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 海选「圈」的口径与圈级裁判绑定。
 *
 * <p>三件事此前散在 {@code TStageLifecycleServiceImpl} 里,却被生成对阵、加圈、签到落圈、
 * 开赛守卫、结果导出五条线共用(圈口径 9 处、计划圈数 6 处、圈裁判绑定 4 处),是又一个
 * 共享内核。抽出来后各条线读同一份口径,不会再出现"单圈按 CENTER 名字、多圈按 ZONE-*"那种分叉。</p>
 *
 * @author duane
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditionCircleSupport {

    private final TMatchMapper matchMapper;
    private final TMatchRefereeMapper matchRefereeMapper;
    private final TMatchRoundMapper matchRoundMapper;
    private final ITRefereeStageService refereeStageService;
    /** 加赛场次判定(同分加赛复用原圈 displayZone,不计入圈场次) */
    private final SettlementSupport settlementSupport;
    private final StageLookup stageLookup;

    /**
     * 海选圈场次的<b>唯一口径</b>:本赛段除同分加赛外的全部正式场次,按展示序。
     *
     * <p>圈就是正式场次,单圈/多圈不分开判断——圈序号即本列表下标 +1,分区名恒为
     * {@code ZONE-k}(单圈即 {@code ZONE-1})。此前"多圈只认 ZONE-* 场次、单圈另取一个
     * CENTER 名字"的分叉,会让单圈场次在加圈后掉出圈集合:名额算在它身上、裁判绑在它身上,
     * 但"按圈取人"和"补圈"都不认它(表现为加一圈后新圈 0 名额、圈级裁判绑定被清空)。</p>
     */
    public List<TMatch> circles(TStage stage) {
        return matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .eq(TMatch::getStageId, stage.getId())
                .orderByAsc(TMatch::getDisplayRow)
                .orderByAsc(TMatch::getId))
            .stream()
            // 同分加赛复用原圈 displayZone,不计入"圈场次"
            .filter(m -> !settlementSupport.isTiebreaker(m))
            .toList();
    }

    /** 配置里计划的圈数(ruleConfig.circles),未配置为 0 */
    public int plannedCircleCount(TStage stage) {
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        return (rc != null && rc.getCircles() != null) ? Math.max(0, rc.getCircles()) : 0;
    }

    /**
     * 海选圈结构与保存同步:圈未建则预建空圈,圈不够则按配置追加,圈已齐则按「每圈裁判」重绑。
     *
     * <p>圈只由配置侧产生——签到只负责人落进已有的圈,不再补建圈场次。
     * 场次一旦开始就不再增删圈,也不改动进行中的裁判绑定。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void ensureAuditionCircles(Long stageId) {
        TStage stage = stageLookup.get(stageId);
        if (!StageModeEnum.AUDITION.getCode().equals(stage.getStageMode())
            || plannedCircleCount(stage) < 1
            || StageConstants.STAGE_SETTLED.equals(stage.getStatus())
            || StageConstants.STAGE_DISCARD.equals(stage.getStatus())) {
            return;
        }
        // 已有场次开始(进行中)后不再增删圈
        boolean anyStarted = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .ne(TMatch::getStatus, StageConstants.MATCH_PENDING)) > 0;
        if (anyStarted) {
            return;
        }
        List<TMatch> zones = circles(stage);
        int planned = plannedCircleCount(stage);
        if (zones.isEmpty()) {
            // 统一预建空圈:只建圈结构,不分配选手——落圈一律由客户端在签到时指定,
            // 因此这里不能走会按号码/名额分人的生成路径。
            addMissingCircles(stage, planned);
            log.info("海选赛段[{}]预建{}个空圈完成(不分配选手)", stageId, planned);
            return;
        }
        if (zones.size() < planned) {
            // 只允许增加圈:在末尾追加空白 ZONE match,原圈及已落圈选手保持不变
            addMissingCircles(stage, planned);
            log.info("海选赛段[{}]按配置追加空圈至{}圈完成", stageId, planned);
            return;
        }
        // 圈已建齐:本次保存可能只改了「每圈裁判」配置,按配置重新应用圈-裁判绑定。
        // 仅在配置里显式写了每圈裁判(长度与圈数一致)时才重绑——否则会走兜底规则
        // (全裁判绑每圈 / 1:1),把现场手动调好的绑定覆盖掉。
        // 场次已开始的情况在上面已提前返回,不会改动进行中的绑定。
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        List<List<Long>> cfg = rc != null ? rc.getCircleRefereeIds() : null;
        if (cfg != null && cfg.size() == zones.size()) {
            assignCircleReferees(stage);
        }
    }

    /**
     * 海选增加圈:只追加缺失的空白 ZONE match(原有圈、已落圈选手、轮次均不动)。
     * 新圈无选手/无轮次,签到落圈或补签时自动写入;裁判绑定按配置整体重绑。
     */
    private void addMissingCircles(TStage stage, int planned) {
        // 兜底:任一原始圈场次已开始就不再补圈(调用方应已校验)
        boolean anyStarted = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stage.getId())
            .ne(TMatch::getStatus, StageConstants.MATCH_PENDING)) > 0;
        if (anyStarted) {
            log.warn("海选赛段[{}]已有场次开始,跳过补圈(配置{}圈,当前{}圈)", stage.getId(),
                planned, circles(stage).size());
            return;
        }
        List<TMatch> zones = circles(stage);
        if (zones.size() >= planned) {
            return;
        }
        String matchMode = zones.stream()
            .map(TMatch::getMatchMode)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(MatchModeEnum.VOTING.getCode());
        for (int c = zones.size() + 1; c <= planned; c++) {
            TMatch m = new TMatch();
            m.setTournamentId(stage.getTournamentId());
            m.setTenantId(stage.getTenantId());
            m.setStageId(stage.getId());
            // 与生成器同一口径:第 k 个圈恒为 ZONE-k(单圈即 ZONE-1)
            m.setName(StageFlowSupport.circleName(planned, c));
            m.setDisplayZone(StageFlowSupport.circleZone(c));
            m.setDisplayRow((long) (c - 1));
            m.setDisplayCol(1L);
            m.setStatus(StageConstants.MATCH_PENDING);
            m.setMatchMode(matchMode);
            m.setMatchType(StageConstants.MATCH_TYPE_NORMAL);
            matchMapper.insert(m);
            // 每圈一个首回合:round 只表示「场次内的回合/局」,与选手无关
            TMatchRound round = new TMatchRound();
            round.setTenantId(stage.getTenantId());
            round.setTournamentId(stage.getTournamentId());
            round.setMatchId(m.getId());
            round.setRoundSequence(1L);
            round.setStatus(StageConstants.MATCH_PENDING);
            matchRoundMapper.insert(round);
            log.info("海选赛段[{}]追加空白第{}圈(matchId={})", stage.getId(), c, m.getId());
        }
        // 按配置刷新圈-裁判绑定(新增圈一并绑定,已有圈幂等重绑)
        assignCircleReferees(stage);
    }

    /**
     * 海选分圈后按圈绑定裁判(一圈可多裁判):
     * 优先使用 ruleConfig.circleRefereeIds(按圈顺序,每圈可多个);
     * 未配置时:圈数 = 赛段裁判数则按圈顺序 1:1,否则把赛段全部裁判绑到每个圈。
     */
    public void assignCircleReferees(TStage stage) {
        // 圈集合与"每圈名额/出口按圈取人"同一口径(正式圈,不含加赛场次)
        List<TMatch> circles = circles(stage);
        if (circles.isEmpty()) {
            return;
        }
        List<Long> circleIds = circles.stream().map(TMatch::getId).toList();

        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        List<List<Long>> cfg = rc != null ? rc.getCircleRefereeIds() : null;
        boolean useConfig = cfg != null && cfg.size() == circles.size();
        List<Long> stageRefereeIds = refereeStageService.getRefereeIdsByStageId(stage.getId());
        if (!useConfig && stageRefereeIds.isEmpty()) {
            // 既没有"每圈裁判"配置、也没有赛段级裁判:没有任何可用于绑定的信息,
            // 保持现状(既不删也不写)。此前是先删光再判断,这一步会把圈级绑定静默清空,
            // 且因为配置长度对不上而永远恢复不回来(开赛守卫随即报"某圈还没有裁判")。
            return;
        }
        boolean oneToOne = !useConfig && circles.size() == stageRefereeIds.size();
        // 先把"每圈绑谁"算清楚,再一次性重建,保证任何情况下都不会删了不补
        List<List<Long>> plan = new ArrayList<>(circles.size());
        for (int i = 0; i < circles.size(); i++) {
            List<Long> assigned = useConfig
                ? (cfg.get(i) == null ? List.of() : cfg.get(i))
                : (oneToOne ? List.of(stageRefereeIds.get(i)) : stageRefereeIds);
            plan.add(assigned);
        }
        // 幂等:重建前先清掉该批场次已有的圈-裁判绑定
        matchRefereeMapper.delete(Wrappers.<TMatchReferee>lambdaQuery()
            .in(TMatchReferee::getMatchId, circleIds));
        int assignedCount = 0;
        for (int i = 0; i < circles.size(); i++) {
            List<Long> assigned = plan.get(i);
            if (assigned.isEmpty()) {
                continue;
            }
            for (Long refereeId : assigned) {
                if (refereeId == null) {
                    continue;
                }
                TMatchReferee mr = new TMatchReferee();
                mr.setMatchId(circles.get(i).getId());
                mr.setRefereeId(refereeId);
                mr.setTournamentId(stage.getTournamentId());
                matchRefereeMapper.insert(mr);
                assignedCount++;
            }
        }
        log.info("赛段[{}]海选{}圈绑定裁判完成,共{}条(useConfig={})",
            stage.getId(), circles.size(), assignedCount, useConfig);
    }
}

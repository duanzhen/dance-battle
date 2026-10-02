package com.dance.street.game.service.impl.stage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TMatchReferee;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.vo.AuditionResultVo;
import com.dance.street.game.excel.ExcelUtil;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRefereeMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TRoundScoreMapper;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.service.impl.flow.AuditionCircleSupport;
import com.dance.street.game.service.impl.flow.StageLookup;
import com.dance.street.game.service.impl.settle.SettlementSupport;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static com.dance.street.game.service.impl.settle.SettlementSupport.parseCompetitorNumber;

/**
 * 海选结果的口径与导出。
 *
 * <p>从 {@code TStageLifecycleServiceImpl} 按业务轴搬出来的内部协作者:海选成绩怎么算、
 * 二海/三海怎么展示、导出成什么样,是自成一条业务线。查询是唯一口径
 * ({@link #queryAuditionResult}),导出与前端组件都消费它,不再各自聚合。</p>
 *
 * @author duane
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AuditionResultService {

    private final StageLookup stageLookup;
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TMatchRoundMapper matchRoundMapper;
    private final TMatchRefereeMapper matchRefereeMapper;
    private final TRoundScoreMapper roundScoreMapper;
    private final TCompetitorMapper competitorMapper;
    private final TRefereeMapper refereeMapper;
    /** 加赛场次判定(二海/三海复用原圈 displayZone) */
    private final SettlementSupport settlementSupport;
    /** 圈场次口径(导出多圈时按圈分组) */
    private final AuditionCircleSupport auditionCircleSupport;

    /**
     * 查询海选赛段结果(统一口径):原始海选成绩 + 二海/三海…加赛明细。
     * 二海分数只用于同分者决出晋级顺序,不计入原始总分;
     * 导出与前端各组件均消费本结果,不再各自聚合。
     */
    public AuditionResultVo queryAuditionResult(Long stageId) {
        TStage stage = stageLookup.get(stageId);
        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, stageId)
            .orderByAsc(TMatch::getDisplayRow)
            .orderByAsc(TMatch::getId));
        List<TMatch> mainMatches = new ArrayList<>();
        List<TMatch> tbMatches = new ArrayList<>();
        for (TMatch m : matches) {
            if (settlementSupport.isTiebreaker(m)) {
                tbMatches.add(m);
            } else {
                mainMatches.add(m);
            }
        }
        List<TReferee> referees = refereeMapper.selectList(Wrappers.<TReferee>lambdaQuery()
            .eq(TReferee::getTournamentId, stage.getTournamentId())
            .orderByAsc(TReferee::getId));
        Map<Long, String> refNameById = referees.stream()
            .collect(Collectors.toMap(TReferee::getId,
                r -> StringUtils.defaultString(r.getName(), "裁判" + r.getId()), (a, b) -> a));

        AuditionResultVo vo = new AuditionResultVo();
        vo.setCompetitors(buildAuditionMainItems(mainMatches, referees, refNameById));
        vo.setTiebreakers(buildAuditionTiebreakers(tbMatches, referees, refNameById));
        return vo;
    }

    /** 原始海选场参与方明细:原始总分(不含二海)、场次排名、结果、各裁判分;按最终排名升序 */
    private List<AuditionResultVo.CompetitorItem> buildAuditionMainItems(List<TMatch> mainMatches,
                                                                         List<TReferee> referees,
                                                                         Map<Long, String> refNameById) {
        if (mainMatches.isEmpty()) {
            return List.of();
        }
        List<Long> matchIds = mainMatches.stream().map(TMatch::getId).toList();
        Map<Long, TMatch> matchById = mainMatches.stream()
            .collect(Collectors.toMap(TMatch::getId, m -> m, (a, b) -> a));
        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .in(TMatchParticipant::getMatchId, matchIds)
            .isNotNull(TMatchParticipant::getCompetitorId));
        Map<String, BigDecimal> scoreByRef = roundScoreByRef(matchIds);
        Map<Long, TCompetitor> compById = competitorById(parts);
        List<AuditionResultVo.CompetitorItem> items = new ArrayList<>();
        for (TMatchParticipant p : parts) {
            items.add(toCompetitorItem(p, compById, matchById, scoreByRef, referees, refNameById));
        }
        items.sort(Comparator
            .comparing((AuditionResultVo.CompetitorItem i) -> i.getFinalRank() == null ? Long.MAX_VALUE : i.getFinalRank())
            .thenComparingInt(i -> parseCompetitorNumber(i.getNumber())));
        return items;
    }

    /** 二海/三海…:按「加赛深度 + 圈」分组,每圈独立一份明细(多圈时互不混淆),参与方按号码升序 */
    private List<AuditionResultVo.TiebreakerItem> buildAuditionTiebreakers(List<TMatch> tbMatches,
                                                                           List<TReferee> referees,
                                                                           Map<Long, String> refNameById) {
        // 加赛深度 -> 圈(displayZone,按首次出现序) -> 加赛场次
        Map<Integer, LinkedHashMap<String, List<TMatch>>> byDepthZone = new TreeMap<>();
        for (TMatch m : tbMatches) {
            String nm = m.getName() == null ? "" : m.getName();
            int depth = 0;
            for (int i = nm.indexOf("加赛"); i >= 0; i = nm.indexOf("加赛", i + 2)) {
                depth++;
            }
            String zone = m.getDisplayZone();
            byDepthZone.computeIfAbsent(Math.max(1, depth), k -> new LinkedHashMap<>())
                .computeIfAbsent(zone, k -> new ArrayList<>())
                .add(m);
        }
        // 全部加赛场次一次取回参与方/打分/参赛单位,再按「深度×圈」在内存分组:
        // 此前每个分组各发 3 类查询(K 个组 ≈ 3K 条),现在固定 3 条。
        List<Long> allIds = tbMatches.stream().map(TMatch::getId).filter(Objects::nonNull).toList();
        List<TMatchParticipant> allParts = allIds.isEmpty() ? List.of()
            : participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                .in(TMatchParticipant::getMatchId, allIds)
                .isNotNull(TMatchParticipant::getCompetitorId));
        Map<Long, List<TMatchParticipant>> partsByMatch = allParts.stream()
            .filter(p -> p.getMatchId() != null)
            .collect(Collectors.groupingBy(TMatchParticipant::getMatchId));
        Map<String, BigDecimal> allScoreByRef = roundScoreByRef(allIds);
        Map<Long, TCompetitor> allCompById = competitorById(allParts);

        List<AuditionResultVo.TiebreakerItem> result = new ArrayList<>();
        for (Map.Entry<Integer, LinkedHashMap<String, List<TMatch>>> depthEntry : byDepthZone.entrySet()) {
            for (Map.Entry<String, List<TMatch>> zoneEntry : depthEntry.getValue().entrySet()) {
                List<TMatch> group = zoneEntry.getValue();
                List<Long> ids = group.stream().map(TMatch::getId).toList();
                Map<Long, TMatch> matchById = group.stream()
                    .collect(Collectors.toMap(TMatch::getId, m -> m, (a, b) -> a));
                List<TMatchParticipant> parts = ids.stream()
                    .flatMap(id -> partsByMatch.getOrDefault(id, List.of()).stream())
                    .toList();
                List<AuditionResultVo.CompetitorItem> items = new ArrayList<>();
                for (TMatchParticipant p : parts) {
                    items.add(toCompetitorItem(p, allCompById, matchById, allScoreByRef, referees, refNameById));
                }
                items.sort(Comparator.comparingInt(i -> parseCompetitorNumber(i.getNumber())));
                AuditionResultVo.TiebreakerItem tb = new AuditionResultVo.TiebreakerItem();
                tb.setRound(depthEntry.getKey());
                tb.setMatchId(group.get(0).getId());
                tb.setName(group.get(0).getName());
                tb.setZone(group.get(0).getDisplayZone());
                tb.setCompetitors(items);
                result.add(tb);
            }
        }
        return result;
    }

    /** 指定场次的 (competitorId:refereeId) -> 累计分 */
    private Map<String, BigDecimal> roundScoreByRef(List<Long> matchIds) {
        List<Long> roundIds = matchIds.isEmpty() ? List.of()
            : matchRoundMapper.selectList(Wrappers.<TMatchRound>lambdaQuery()
                    .in(TMatchRound::getMatchId, matchIds).select(TMatchRound::getId))
                .stream().map(TMatchRound::getId).toList();
        return roundIds.isEmpty() ? Map.of()
            : roundScoreMapper.selectList(Wrappers.<TRoundScore>lambdaQuery().in(TRoundScore::getRoundId, roundIds))
                .stream()
                .filter(s -> s.getCompetitorId() != null && s.getRefereeId() != null && s.getScore() != null)
                .collect(Collectors.toMap(
                    s -> s.getCompetitorId() + ":" + s.getRefereeId(),
                    TRoundScore::getScore,
                    BigDecimal::add));
    }

    /** 参与方 -> 参赛单位映射 */
    private Map<Long, TCompetitor> competitorById(List<TMatchParticipant> parts) {
        List<Long> cids = parts.stream()
            .map(TMatchParticipant::getCompetitorId).filter(Objects::nonNull).distinct().toList();
        return cids.isEmpty() ? Map.of()
            : competitorMapper.selectByIds(cids).stream()
                .collect(Collectors.toMap(TCompetitor::getId, c -> c, (a, b) -> a));
    }

    /** 参赛方行 -> 统一结果项(号码/名称/总分/排名/结果/各裁判分) */
    private AuditionResultVo.CompetitorItem toCompetitorItem(TMatchParticipant p,
                                                             Map<Long, TCompetitor> compById,
                                                             Map<Long, TMatch> matchById,
                                                             Map<String, BigDecimal> scoreByRef,
                                                             List<TReferee> referees,
                                                             Map<Long, String> refNameById) {
        TCompetitor c = p.getCompetitorId() == null ? null : compById.get(p.getCompetitorId());
        AuditionResultVo.CompetitorItem item = new AuditionResultVo.CompetitorItem();
        item.setCompetitorId(p.getCompetitorId());
        item.setNumber(c == null ? null : c.getNumber());
        item.setName(c == null ? null : c.getName());
        TMatch m = p.getMatchId() == null ? null : matchById.get(p.getMatchId());
        item.setZone(m == null ? null : m.getDisplayZone());
        item.setScore(p.getScoreValue());
        item.setRankInMatch(p.getRankInMatch());
        item.setOutcomeStatus(p.getOutcomeStatus());
        item.setFinalRank(c == null ? null : c.getFinalRank());
        List<AuditionResultVo.RefereeScoreItem> refScores = new ArrayList<>();
        if (p.getCompetitorId() != null) {
            for (TReferee r : referees) {
                BigDecimal v = scoreByRef.get(p.getCompetitorId() + ":" + r.getId());
                if (v != null) {
                    AuditionResultVo.RefereeScoreItem rs = new AuditionResultVo.RefereeScoreItem();
                    rs.setRefereeId(r.getId());
                    rs.setRefereeName(refNameById.get(r.getId()));
                    rs.setScore(v);
                    refScores.add(rs);
                }
            }
        }
        item.setRefereeScores(refScores);
        return item;
    }

    /**
     * 导出海选结果 Excel:
     * <ul>
     *   <li>"海选成绩" sheet:号码 / 选手名 / 各裁判分数 / 总分 / 排名,总分只统计原始海选场;</li>
     *   <li>二海/三海/… sheet:按加赛深度各占一张(号码 / 选手名 / 各裁判分数 / 总分 / 结果),
     *       加赛分数仅用于同分者决出晋级顺序,不进入主表总分。</li>
     * </ul>
     * 数据统一来自 {@link #queryAuditionResult(Long)},此处不再重复聚合。
     */
    public void exportAuditionResult(Long stageId, HttpServletResponse response) {
        TStage stage = stageLookup.get(stageId);
        if (!StageModeEnum.AUDITION.getCode().equals(stage.getStageMode())) {
            throw new ServiceException("仅海选赛赛段支持导出海选结果");
        }
        AuditionResultVo result = queryAuditionResult(stageId);
        // 裁判列顺序:赛事全部裁判按 id 升序(未打分的裁判该列留空)
        List<TReferee> referees = refereeMapper.selectList(Wrappers.<TReferee>lambdaQuery()
            .eq(TReferee::getTournamentId, stage.getTournamentId())
            .orderByAsc(TReferee::getId));
        // 多圈导出:主表与加赛表都带"圈"列(显示该圈裁判名,无裁判回退第N圈)
        Map<String, String> zoneLabels = buildAuditionZoneLabelMap(stage, referees);
        boolean multiCircle = zoneLabels.size() > 1;

        // 主表头:号码 | 选手名 | [圈] | 裁判1..n | 总分 | 排名
        List<List<String>> head = new ArrayList<>();
        head.add(List.of("号码"));
        head.add(List.of("选手名"));
        if (multiCircle) {
            head.add(List.of("圈"));
        }
        for (TReferee r : referees) {
            head.add(List.of(StringUtils.defaultString(r.getName(), "裁判" + r.getId())));
        }
        head.add(List.of("总分"));
        head.add(List.of("排名"));
        // 排序口径与名单页「分数排名」一致:原始分降序 → 加赛(二海/三海)分降序 → 号码牌升序;
        // 多圈时先按圈分组,与「排名」列的分圈名次保持一致
        Map<Long, BigDecimal> tiebreakScore = auditionTiebreakScoreMap(result.getTiebreakers());
        List<String> zoneOrder = auditionCircleSupport.circles(stage).stream()
            .map(TMatch::getDisplayZone).filter(Objects::nonNull).distinct().toList();
        List<AuditionResultVo.CompetitorItem> mainItems = new ArrayList<>(result.getCompetitors());
        mainItems.sort(auditionExportComparator(zoneOrder, tiebreakScore));
        List<List<Object>> rows = new ArrayList<>();
        for (AuditionResultVo.CompetitorItem c : mainItems) {
            rows.add(auditionExportRow(c, referees, true, zoneLabels, multiCircle));
        }

        // 加赛表头:号码 | 选手名 | [圈] | 裁判1..n | 总分 | 结果
        List<List<String>> tbHead = new ArrayList<>();
        tbHead.add(List.of("号码"));
        tbHead.add(List.of("选手名"));
        if (multiCircle) {
            tbHead.add(List.of("圈"));
        }
        for (TReferee r : referees) {
            tbHead.add(List.of(StringUtils.defaultString(r.getName(), "裁判" + r.getId())));
        }
        tbHead.add(List.of("总分"));
        tbHead.add(List.of("结果"));

        // 组装工作表:主表 + 二海/三海…各一张
        List<ExcelUtil.RawSheet> sheets = new ArrayList<>();
        sheets.add(new ExcelUtil.RawSheet("海选成绩", head, rows));
        Set<String> usedSheetNames = new HashSet<>();
        usedSheetNames.add("海选成绩");
        for (AuditionResultVo.TiebreakerItem tb : result.getTiebreakers()) {
            List<List<Object>> tbRows = new ArrayList<>();
            // 加赛表按该轮加赛总分降序(同分再按号码牌),即「谁赢了加赛谁在前」
            List<AuditionResultVo.CompetitorItem> tbItems = new ArrayList<>(tb.getCompetitors());
            tbItems.sort(Comparator
                .comparing((AuditionResultVo.CompetitorItem c) -> c.getScore() == null ? new BigDecimal(-1) : c.getScore())
                .reversed()
                .thenComparingInt(c -> parseCompetitorNumber(c.getNumber())));
            for (AuditionResultVo.CompetitorItem c : tbItems) {
                tbRows.add(auditionExportRow(c, referees, false, zoneLabels, multiCircle));
            }
            String sheetName = tiebreakerSheetName(tb.getRound());
            String zoneLabel = zoneLabelOf(zoneLabels, tb.getZone());
            if (multiCircle && StringUtils.isNotBlank(zoneLabel)) {
                sheetName += "·" + zoneLabel;
            }
            if (!usedSheetNames.add(sheetName) && tb.getZone() != null) {
                // 同名裁判同时绑多个圈等极端情况:追加圈号保证 sheet 不重名
                sheetName += "·" + tb.getZone();
                usedSheetNames.add(sheetName);
            }
            sheets.add(new ExcelUtil.RawSheet(sheetName, tbHead, tbRows));
        }

        try {
            org.dromara.common.core.utils.file.FileUtils.setAttachmentResponseHeader(
                response, "海选结果-" + stage.getName() + ".xlsx");
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet;charset=UTF-8");
            try (jakarta.servlet.ServletOutputStream os = response.getOutputStream()) {
                ExcelUtil.exportSheets(sheets, os);
            }
        } catch (java.io.IOException e) {
            throw new ServiceException("导出海选结果失败: {}", e.getMessage());
        }
    }

    /**
     * 加赛得分:competitorId -> 最深一轮(二海→三海…)的加赛总分。
     * 只用于同原始分时的先后,不参与主表总分。
     */
    private Map<Long, BigDecimal> auditionTiebreakScoreMap(List<AuditionResultVo.TiebreakerItem> tiebreakers) {
        Map<Long, BigDecimal> byCid = new HashMap<>();
        if (tiebreakers == null || tiebreakers.isEmpty()) {
            return byCid;
        }
        List<AuditionResultVo.TiebreakerItem> ordered = new ArrayList<>(tiebreakers);
        ordered.sort(Comparator.comparingInt(t -> t.getRound() == null ? 0 : t.getRound()));
        for (AuditionResultVo.TiebreakerItem tb : ordered) {
            if (tb.getCompetitors() == null) {
                continue;
            }
            for (AuditionResultVo.CompetitorItem c : tb.getCompetitors()) {
                if (c.getCompetitorId() != null && c.getScore() != null) {
                    byCid.put(c.getCompetitorId(), c.getScore());
                }
            }
        }
        return byCid;
    }

    /**
     * 海选导出主表排序:圈序 → 原始分降序 → 加赛分降序 → 号码牌升序
     * (与名单页「分数排名」同一口径,保证二海选手按加赛成绩排在前面)。
     */
    private Comparator<AuditionResultVo.CompetitorItem> auditionExportComparator(
        List<String> zoneOrder, Map<Long, BigDecimal> tiebreakScore) {
        Comparator<AuditionResultVo.CompetitorItem> byZone = Comparator.comparingInt(c -> {
            int idx = c.getZone() == null ? -1 : zoneOrder.indexOf(c.getZone());
            return idx < 0 ? Integer.MAX_VALUE : idx;
        });
        Comparator<AuditionResultVo.CompetitorItem> byScore = Comparator.comparing(
                (AuditionResultVo.CompetitorItem c) -> c.getScore() == null ? new BigDecimal(-1) : c.getScore())
            .reversed();
        Comparator<AuditionResultVo.CompetitorItem> byTiebreak = Comparator.comparing(
                (AuditionResultVo.CompetitorItem c) -> tiebreakScore.getOrDefault(c.getCompetitorId(), new BigDecimal(-1)))
            .reversed();
        Comparator<AuditionResultVo.CompetitorItem> byNumber =
            Comparator.comparingInt(c -> parseCompetitorNumber(c.getNumber()));
        return byZone.thenComparing(byScore).thenComparing(byTiebreak).thenComparing(byNumber);
    }

    /** 海选导出行:主表最后一列为排名,加赛表最后一列为结果 */
    private List<Object> auditionExportRow(AuditionResultVo.CompetitorItem c,
                                           List<TReferee> referees, boolean mainSheet,
                                           Map<String, String> zoneLabels, boolean multiCircle) {
        Map<Long, BigDecimal> refMap = c.getRefereeScores() == null ? Map.of()
            : c.getRefereeScores().stream()
                .collect(Collectors.toMap(AuditionResultVo.RefereeScoreItem::getRefereeId,
                    AuditionResultVo.RefereeScoreItem::getScore, (a, b) -> a));
        List<Object> row = new ArrayList<>();
        row.add(c.getNumber() == null ? "" : c.getNumber());
        row.add(c.getName() == null ? "" : c.getName());
        if (multiCircle) {
            row.add(zoneLabelOf(zoneLabels, c.getZone()));
        }
        BigDecimal total = BigDecimal.ZERO;
        int scoredRefs = 0;
        for (TReferee r : referees) {
            BigDecimal v = refMap.get(r.getId());
            row.add(v == null ? "" : v.stripTrailingZeros().toPlainString());
            if (v != null) {
                total = total.add(v);
                scoredRefs++;
            }
        }
        row.add(scoredRefs == 0 ? "" : total.stripTrailingZeros().toPlainString());
        row.add(mainSheet
            ? (c.getFinalRank() == null ? "" : c.getFinalRank())
            : auditionResultText(c.getOutcomeStatus()));
        return row;
    }

    /** 导出用圈标签:多圈时按圈显示裁判名(无裁判回退「第N圈」) */
    private Map<String, String> buildAuditionZoneLabelMap(TStage stage, List<TReferee> referees) {
        List<TMatch> zones = auditionCircleSupport.circles(stage);
        if (zones.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> nameById = referees.stream()
            .collect(Collectors.toMap(TReferee::getId,
                r -> StringUtils.defaultString(r.getName(), "裁判" + r.getId()), (a, b) -> a));
        List<Long> zoneIds = zones.stream().map(TMatch::getId).toList();
        List<TMatchReferee> refRows = zoneIds.isEmpty() ? List.of()
            : matchRefereeMapper.selectList(Wrappers.<TMatchReferee>lambdaQuery()
                .in(TMatchReferee::getMatchId, zoneIds)
                .orderByAsc(TMatchReferee::getRefereeId));
        Map<Long, List<String>> namesByMatch = new HashMap<>();
        for (TMatchReferee mr : refRows) {
            String nm = nameById.get(mr.getRefereeId());
            if (nm == null) {
                continue;
            }
            namesByMatch.computeIfAbsent(mr.getMatchId(), k -> new ArrayList<>()).add(nm);
        }
        Map<String, String> labels = new HashMap<>();
        for (TMatch z : zones) {
            List<String> names = namesByMatch.get(z.getId());
            labels.put(z.getDisplayZone(),
                names == null || names.isEmpty()
                    ? "第" + StringUtils.defaultString(z.getDisplayZone(), "").replace("ZONE-", "") + "圈"
                    : String.join(" / ", names));
        }
        return labels;
    }

    private String zoneLabelOf(Map<String, String> zoneLabels, String zone) {
        if (zone == null || zone.isBlank() || zoneLabels.isEmpty()) {
            return "";
        }
        return zoneLabels.getOrDefault(zone, "");
    }

    /** 加赛深度 -> sheet 名:1=二海,2=三海,3=四海,4=五海(加赛上限内最多四级) */
    private String tiebreakerSheetName(int depth) {
        return switch (depth) {
            case 1 -> "二海";
            case 2 -> "三海";
            case 3 -> "四海";
            case 4 -> "五海";
            default -> "加赛" + depth;
        };
    }

    /** 海选加赛参赛方结果文本 */
    private String auditionResultText(String status) {
        if (status == null) {
            return "";
        }
        if ("ADVANCE".equals(status)) return "晋级";
        if ("ELIMINATED".equals(status)) return "淘汰";
        if ("PENDING".equals(status)) return "进行中";
        if ("WITHDRAWN".equals(status)) return "退赛";
        return status;
    }
}

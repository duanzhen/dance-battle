package com.dance.street.game.service.impl.roster;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.engine.common.RosterConstants;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.StageFlowSupport;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TStageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
import java.util.stream.Collectors;

/**
 * 名单装配的规则求值:按来源组(出口)规则取人、排序、落位。
 *
 * <p>从 {@code TStageRosterServiceImpl} 拆出来的第一块,也是最关键的一块:它<b>只依赖 mapper,
 * 不依赖任何业务 bean</b>,因此中间层存储(EntryStore)可以依赖它,而它不反向依赖存储 ——
 * 这就是原来"重建要装配、装配要读重建结果"那个循环依赖的解法。</p>
 *
 * <p>口径与注释原样保留:淘汰赛名次要坐回原座位(轮空留下的空洞不许压紧)、
 * 海选按圈名次轮转、来源组配额截断等。</p>
 *
 * @author duane
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RosterAssembler {

    private final TStageMapper stageMapper;
    private final TCompetitorMapper competitorMapper;
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;

    /** 装配中间结果:或来自某条来源边的人,或一张外卡 */
    public static final class AssembledRow {
        public TCompetitor source;
        public boolean guest;
        public String guestName;
        public Long guestPlayerId;
        public Long guestType;
        public String guestNumber;
        public Long seedRank;
        public String entryTag;
    }

    /**
     * 多入口汇合判定:两条以上不同的来源边都往同一个赛段送人时,系统没有依据决定谁坐哪,
     * 于是全部先进"待落位区",由导播在中间态拖到真实座位。
     */
    public boolean multiEntry(List<TStageRosterGroupBo> groups) {
        return groups.stream()
            .map(TStageRosterGroupBo::getSourceStageId)
            .filter(Objects::nonNull)
            .distinct()
            .count() > 1;
    }

    /**
     * 按来源组规则取人(纯规则口径):人工调整(加人/外卡/剔除/换位)不在这里参与合并——
     * 它们直接改中间层的行,只有"全量重建"这一步才会回到这里从规则重新算。
     */
    public List<AssembledRow> assembleRows(List<TStageRosterGroupBo> groups) {
        List<AssembledRow> rows = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (TCompetitor c : autoCandidates(groups)) {
            if (seen.add(c.getId())) {
                rows.add(sourceRow(c));
            }
        }
        return rows;
    }

    /** 落位:淘汰赛名次坐回原座位,其余按占用情况顺延取空位 */
    public void assignSeeds(List<AssembledRow> rows, int plan, Set<Long> occupiedSeeds) {
        Set<Long> occupied = new HashSet<>(occupiedSeeds);
        // 淘汰赛承接上一轮淘汰赛时:晋级者按来源名次(finalRank)坐回对应座位;
        // 名次里的空洞来自"双方都轮空"的场次——把座位留空,等于让轮空也晋级到下一赛段对应的座位,
        // 下一级签表因此不会塌陷/错位(名次 = 场次 displayRow + 1,见 DownstreamRouter.markAdvance)。
        Map<Long, Boolean> knockoutSourceCache = new HashMap<>();
        for (AssembledRow r : rows) {
            Long rank = r.source == null ? null : r.source.getFinalRank();
            if (rank != null && rank >= 1L && (plan <= 0 || rank <= plan)
                && isKnockoutSource(r.source, knockoutSourceCache) && occupied.add(rank)) {
                r.seedRank = rank;
                continue;
            }
            r.seedRank = nextFreeSeed(occupied, plan);
            occupied.add(r.seedRank);
        }
    }

    /** 场次内下一个可用座位号 */
    public long nextFreeSeed(Set<Long> occupiedSeeds, int plan) {
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

    /** 该来源参赛方是否来自淘汰赛赛段(只有淘汰赛的名次才对应"场次座位",含轮空留下的空洞) */
    private boolean isKnockoutSource(TCompetitor source, Map<Long, Boolean> cache) {
        if (source == null || source.getStageId() == null) {
            return false;
        }
        return cache.computeIfAbsent(source.getStageId(), id -> {
            TStage s = stageMapper.selectById(id);
            return s != null && StageModeEnum.KNOCKOUT.getCode().equals(s.getStageMode());
        });
    }

    private static AssembledRow sourceRow(TCompetitor c) {
        AssembledRow r = new AssembledRow();
        r.source = c;
        r.entryTag = OutcomeStatusEnum.ADVANCE.getCode().equals(c.getOutcomeStatus())
            ? RosterConstants.ENTRY_ADVANCE : RosterConstants.ENTRY_REVIVE;
        return r;
    }

    private List<TCompetitor> autoCandidates(List<TStageRosterGroupBo> groups) {
        Map<Long, TCompetitor> merged = new LinkedHashMap<>();
        // 来源赛段名称一次批量取回,替代逐组 selectById
        List<Long> sourceStageIds = groups.stream()
            .map(TStageRosterGroupBo::getSourceStageId)
            .filter(Objects::nonNull).distinct().toList();
        Map<Long, TStage> sourceStageById = sourceStageIds.isEmpty() ? Map.of()
            : stageMapper.selectByIds(sourceStageIds).stream()
                .collect(Collectors.toMap(TStage::getId, s -> s, (a, b) -> a));
        for (TStageRosterGroupBo g : groups) {
            if (g.getSourceStageId() == null
                || RosterConstants.FILL_STREAM.equals(g.getFillMode())
                || RosterConstants.FILL_MANUAL.equals(g.getFillMode())) {
                continue;
            }
            List<TCompetitor> rows = new ArrayList<>(groupRows(g));
            TStage src = sourceStageById.get(g.getSourceStageId());
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

    private record PartInfo(String zone, Integer row, Integer rankInMatch, BigDecimal score) {
    }

    /** 按一条来源组的规则与排序取人(出口配置面板的候选列表也用它) */
    public List<TCompetitor> groupRows(TStageRosterGroupBo g) {
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
                m -> Objects.toString(m.getDisplayZone(), ""), (a, b) -> a));
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

    /** 圈序:按场次展示顺序给每个 displayZone 一个序号 */
    public Map<String, Integer> zoneByIdOrder(List<TMatch> matches) {
        Map<String, Integer> order = new HashMap<>();
        int i = 0;
        for (TMatch m : matches) {
            String z = Objects.toString(m.getDisplayZone(), "");
            order.putIfAbsent(z, i++);
        }
        return order;
    }

    /** 圈名归一:ZONE-1 与 "zone-1" 视为同一圈 */
    public String normalizeZone(String zone) {
        return zone == null ? null : (zone.startsWith("ZONE-") ? zone : zone.toUpperCase());
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

    /**
     * 海选来源按圈轮转排序:圈1第1、圈2第1、圈1第2…(与结算名次同一口径),
     * 让各圈靠前的人交替进入下一赛段,而不是一个圈的人挤在一起。
     */
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
        // 圈序号/名额/全局起点:与海选·排名赛结算共用同一口径(名单取人顺序必须与结算名次一致)
        Map<String, StageFlowSupport.CircleQuota> quotaCtx =
            StageFlowSupport.circleQuotaContext(source, srcMatches, "海选");
        Map<String, Integer> zoneOrdinal = new HashMap<>();
        Map<String, Integer> zoneBase = new HashMap<>();
        quotaCtx.forEach((zone, quota) -> {
            zoneOrdinal.put(zone, quota.ordinal());
            zoneBase.put(zone, quota.base());
        });
        List<Long> srcMatchIds = srcMatches.stream().map(TMatch::getId).toList();
        Map<Long, String> zoneByCompetitor = new HashMap<>();
        if (!srcMatchIds.isEmpty()) {
            Map<Long, String> matchZone = new HashMap<>();
            for (TMatch m : srcMatches) {
                matchZone.put(m.getId(), m.getDisplayZone());
            }
            participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                    .in(TMatchParticipant::getMatchId, srcMatchIds)
                    .isNotNull(TMatchParticipant::getCompetitorId)
                    .select(TMatchParticipant::getCompetitorId, TMatchParticipant::getMatchId))
                .forEach(p -> zoneByCompetitor.putIfAbsent(p.getCompetitorId(),
                    matchZone.getOrDefault(p.getMatchId(), "")));
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
}

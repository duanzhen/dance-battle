package com.dance.street.game.service.impl.roster;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.domain.TStageRosterEntry;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.engine.common.RosterConstants;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 名单装配的规则求值与座位投影:按来源组(出口)规则取人、排序、切片和落位。
 *
 * <p>取人、名额切片、出口压紧和目标边落座属于同一条投影算法的连续步骤，放在同一个
 * 类中，避免每个私有步骤都变成独立 Bean。本类不按来源 mode 分支。
 * 中间层存储(EntryStore)依赖本类做投影,本类不反向依赖存储。</p>
 *
 * <p>口径:淘汰签表空洞不许压紧;海选/排名非从第1名切片的压紧在出口边界一次完成;
 * 入边只有一条时自动落座,多条(汇合/多圈)时全部进待落座;</p>
 *
 * @author duane
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RosterProjectionService {

    private final TCompetitorMapper competitorMapper;
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TStageMapper stageMapper;

    /** 装配中间结果:或来自某条来源边的人,或一张外卡 */
    public static final class AssembledRow {
        public TCompetitor source;
        /** 这个人是从哪条入边进来的({@code t_stage_roster_group.id});同一来源有多条边时用于区分 */
        public Long sourceGroupId;
        public boolean guest;
        public String guestName;
        public Long guestPlayerId;
        public Long guestType;
        public String guestNumber;
        public Long seedRank;
        public String entryTag;
    }

    /**
     * 多入口判定:<b>入边(来源组行)多于一条</b>时,系统没有依据决定谁坐哪,
     * 于是全部先进"待落位区",由导播在中间态拖到真实座位。
     *
     * <p>"多入口"按<b>边的条数</b>算,不是按不同来源赛段数算:同一个海选按圈配了
     * A圈、B圈两条出口,就是两条入边,两拨人的座号各自从 1 起算、必然重叠
     * (海选落选者是按圈名额累加编号的),只有整单进待落座才不会撞座。</p>
     */
    public boolean multiEntry(List<TStageRosterGroupBo> groups) {
        return RosterEdgeRules.isMultiEntry(groups);
    }

    /**
     * 目标赛段中间态的完整投影:整表重建与实时同步共用同一份结果,不再各算一套。
     *
     * @param holding    是否走待落位区(多入口汇合)
     * @param slotCount  座位总数 1..slotCount,每个座位一行实体(BYE/PENDING 也占号)
     * @param bySlot     有人的座位(seatRank -&gt; 人)
     * @param holdingRows 待落位的人(slot=null)
     */
    public record Projection(boolean holding, int slotCount,
                             Map<Long, AssembledRow> bySlot, List<AssembledRow> holdingRows) {
    }

    /**
     * 把"目标赛段的入边规则 + 来源赛段结果"投影成一份完整的中间态。
     *
     * <p>统一规则(与赛制无关,任何赛段、任何衔接都是这一套):</p>
     * <ol>
     *   <li>按出边规则取人({@link #assembleRows});</li>
     *   <li><b>入边只有一条</b> → 自动落座:按出边决定
     *       原样复制或压紧目标座号,1..N 每个座位都有一行实体,轮空不压塌陷;</li>
     *   <li><b>入边 ≥2 条</b>(汇合/多圈)→ 不做座位计算,所有人进待落位区,
     *       座位 1..N 照铺空位实体,由导播在中间态拖入。</li>
     * </ol>
     */
    public Projection project(TStage target, List<TStageRosterGroupBo> groups) {
        int plan = target.getTeamCountStart() == null || target.getTeamCountStart() <= 0
            ? 0 : target.getTeamCountStart().intValue();
        if (multiEntry(groups)) {
            List<AssembledRow> holdingRows = assembleRows(groups);
            int slots = Math.max(plan, holdingRows.size());
            return new Projection(true, slots, Map.of(), holdingRows);
        }
        // 单入边:切片 → 落座/压紧;取人按需惰性读取
        List<AssembledRow> rows = List.of();
        if (!groups.isEmpty()) {
            // 唯一的入口边(MANUAL 边不取人、不算入口);没有入口边则没有规则人
            List<TStageRosterGroupBo> entry = RosterEdgeRules.entryEdges(groups);
            if (!entry.isEmpty()) {
                TStageRosterGroupBo edge = entry.get(0);
                rows = exitApply(edge, () -> groupRows(edge), plan);
            }
        }
        int maxAssigned = rows.stream().map(r -> r.seedRank).filter(Objects::nonNull)
            .mapToInt(Long::intValue).max().orElse(0);
        int totalSlots = Math.max(plan, maxAssigned);
        if (totalSlots <= 0) {
            totalSlots = rows.size();
        }
        Map<Long, AssembledRow> bySlot = new LinkedHashMap<>();
        for (AssembledRow r : rows) {
            if (r.seedRank != null && r.seedRank >= 1 && r.seedRank <= totalSlots) {
                bySlot.putIfAbsent(r.seedRank, r);
            }
        }
        // 没有座位号的人 = 来源座号超出本赛段容量(或座位已被占满):进待落座区,不占座位
        List<AssembledRow> overflow = rows.stream().filter(r -> r.seedRank == null).toList();
        return new Projection(false, totalSlots, bySlot, overflow);
    }

    /**
     * 「一条自动边 + 手动边」的手动优先:在自动投影上叠加导播手动的人(origin=MANUAL 的中间层行)。
     *
     * <ul>
     *   <li>手动的人已经有座位 → 座位归他;自动投影里本来坐那个座位的人进待落位区(不顺移,别人座位不动);</li>
     *   <li>同一选手同时被自动边选中、又有手动行 → 只留手动那条,自动边的这条丢掉(座位让空);</li>
     *   <li>手动的人没有座位 → 他本来就在待落位区,与投影无关。</li>
     * </ul>
     *
     * <p>返回新的投影;入参不改。只对带 MANUAL 边的赛段调用,调用方负责判断。</p>
     */
    public Projection manualWins(Projection p, List<TStageRosterEntry> manualRows) {
        if (p.holding() || manualRows == null || manualRows.isEmpty()) {
            return p;
        }
        Set<Long> manualSources = new HashSet<>();
        Set<Long> manualSlots = new HashSet<>();
        for (TStageRosterEntry m : manualRows) {
            if (m.getSourceCompetitorId() != null) {
                manualSources.add(m.getSourceCompetitorId());
            }
            if (m.getSlot() != null) {
                manualSlots.add(m.getSlot());
            }
        }
        Map<Long, AssembledRow> bySlot = new LinkedHashMap<>();
        List<AssembledRow> holdingRows = new ArrayList<>();
        p.bySlot().forEach((seat, r) -> {
            if (r.source != null && manualSources.contains(r.source.getId())) {
                return;                      // 同一选手:手动为准
            }
            if (manualSlots.contains(seat)) {
                r.seedRank = null;           // 座位被手动的人占了:自动的人进待落位区
                holdingRows.add(r);
                return;
            }
            bySlot.put(seat, r);
        });
        for (AssembledRow r : p.holdingRows()) {
            if (r.source != null && manualSources.contains(r.source.getId())) {
                continue;
            }
            holdingRows.add(r);
        }
        return new Projection(false, p.slotCount(), bySlot, holdingRows);
    }

    /**
     * 待落位区粘性:已经在待落位区的人(被手动占座挤下来等)不因对账/重建自动回座,
     * 座位保持空位,等导播拖回去。只应在配了 MANUAL 边的赛段、且 keepManual 的路径调用。
     *
     * <p>没有 MANUAL 边的赛段不要调用(纯自动边被挤就是 bug,保持投影说了算)。</p>
     */
    public Projection stickyHolding(Projection p, Collection<Long> holdingSourceIds) {
        if (p == null || p.holding() || holdingSourceIds == null || holdingSourceIds.isEmpty()) {
            return p;
        }
        Set<Long> sticky = new HashSet<>();
        for (Long id : holdingSourceIds) {
            if (id != null) {
                sticky.add(id);
            }
        }
        if (sticky.isEmpty()) {
            return p;
        }
        Map<Long, AssembledRow> bySlot = new LinkedHashMap<>();
        List<AssembledRow> holdingRows = new ArrayList<>();
        Set<Long> alreadyHolding = new HashSet<>();
        p.bySlot().forEach((seat, r) -> {
            if (r != null && r.source != null && sticky.contains(r.source.getId())) {
                r.seedRank = null;
                holdingRows.add(r);
                alreadyHolding.add(r.source.getId());
                return;
            }
            bySlot.put(seat, r);
        });
        for (AssembledRow r : p.holdingRows()) {
            if (r != null && r.source != null && alreadyHolding.contains(r.source.getId())) {
                continue;
            }
            holdingRows.add(r);
            if (r != null && r.source != null) {
                alreadyHolding.add(r.source.getId());
            }
        }
        return new Projection(false, p.slotCount(), bySlot, holdingRows);
    }

    /**
     * 按来源组规则取人(纯规则口径):人工调整(加人/外卡/剔除/换位)不在这里参与合并——
     * 它们直接改中间层的行,只有"全量重建"这一步才会回到这里从规则重新算。
     */
    public List<AssembledRow> assembleRows(List<TStageRosterGroupBo> groups) {
        return autoCandidateRows(groups);
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

    /**
     * 对一条出边执行出口投影:取人 → quota 切片 → 目标边落座(必要时压紧)。
     * 非自动出边返回空列表且不调用候选供应器。
     */
    public List<AssembledRow> exitApply(TStageRosterGroupBo edge,
                                        Supplier<List<TCompetitor>> candidates, int plan) {
        if (!RosterEdgeRules.isAutoEdge(edge)) {
            return new ArrayList<>();
        }
        List<AssembledRow> rows = slice(edge, candidates.get());
        assignEdgeSeats(rows, plan, new HashSet<>(), shouldCompact(edge));
        return rows;
    }

    /** 只按出边名额切片并包装成装配行，不分配目标座位。 */
    public List<AssembledRow> sliceEdge(TStageRosterGroupBo edge,
                                        Supplier<List<TCompetitor>> candidates) {
        if (!RosterEdgeRules.isAutoEdge(edge)) {
            return new ArrayList<>();
        }
        return slice(edge, candidates.get());
    }

    /**
     * 该出边是否在切片后压紧目标座位。
     *
     * <p>海选/排名来源的名次是累加序号，允许压紧；淘汰等签表来源必须保留空洞座位。</p>
     */
    public boolean shouldCompact(TStageRosterGroupBo edge) {
        if (edge == null || edge.getSourceStageId() == null) {
            return false;
        }
        TStage source = stageMapper.selectById(edge.getSourceStageId());
        if (source == null) {
            return false;
        }
        return StageModeEnum.AUDITION.getCode().equals(source.getStageMode())
            || StageModeEnum.RANK.getCode().equals(source.getStageMode());
    }

    /**
     * 按出边座位规则写入 {@link AssembledRow#seedRank}。
     *
     * @param compact true = 整体下移 minRank-1 压掉前部空档；false = 原样复制来源座号
     */
    public void assignEdgeSeats(List<AssembledRow> rows, int plan,
                                Set<Long> occupiedSeeds, boolean compact) {
        Set<Long> occupied = occupiedSeeds == null ? new HashSet<>() : occupiedSeeds;
        if (compact) {
            compactAssign(rows, plan, occupied);
        } else {
            copyAssign(rows, plan, occupied);
        }
    }

    /** 按出边来源赛制的压紧口径落座。 */
    public void assignEdgeSeats(List<AssembledRow> rows, int plan, TStageRosterGroupBo edge) {
        assignEdgeSeats(rows, plan, new HashSet<>(), shouldCompact(edge));
    }

    /** 按出边 quota 截取候选并包装成装配行（纯计算，不查来源数据）。 */
    private List<AssembledRow> slice(TStageRosterGroupBo edge, List<TCompetitor> candidates) {
        List<AssembledRow> rows = new ArrayList<>();
        int quota = edge.getQuota() != null && edge.getQuota() > 0
            ? edge.getQuota() : Integer.MAX_VALUE;
        int n = 0;
        for (TCompetitor c : candidates) {
            if (n++ >= quota) {
                log.warn("来源组(源赛段 {})配额 {} 已满,剩余候选截断",
                    edge.getSourceStageId(), edge.getQuota());
                break;
            }
            AssembledRow row = new AssembledRow();
            row.source = c;
            row.sourceGroupId = edge.getId();
            row.entryTag = OutcomeStatusEnum.ADVANCE.getCode().equals(c.getOutcomeStatus())
                ? RosterConstants.ENTRY_ADVANCE : RosterConstants.ENTRY_REVIVE;
            rows.add(row);
        }
        return rows;
    }

    /** 海选/排名出口压紧:名次整体下移 min-1，中间空洞保留。 */
    private void compactAssign(List<AssembledRow> rows, int plan, Set<Long> occupied) {
        int minRank = rows.stream()
            .map(r -> r.source == null ? null : r.source.getFinalRank())
            .filter(Objects::nonNull)
            .mapToInt(Long::intValue)
            .min().orElse(1);
        long shift = Math.max(0L, (long) minRank - 1L);
        for (AssembledRow r : rows) {
            Long rank = r.source == null ? null : r.source.getFinalRank();
            Long seat = rank == null ? null : rank - shift;
            if (seat != null && seat >= 1L && (plan <= 0 || seat <= plan) && occupied.add(seat)) {
                r.seedRank = seat;
                continue;
            }
            if (rank != null) {
                r.seedRank = null;
                continue;
            }
            Long free = nextFreeSeedOrNull(occupied, plan);
            r.seedRank = free;
            if (free != null) {
                occupied.add(free);
            }
        }
    }

    /** 签表/默认:原样复制来源座号（空洞即位置）。 */
    private void copyAssign(List<AssembledRow> rows, int plan, Set<Long> occupied) {
        for (AssembledRow r : rows) {
            Long seat = r.source == null ? null : r.source.getFinalRank();
            if (seat != null && seat >= 1L && (plan <= 0 || seat <= plan) && occupied.add(seat)) {
                r.seedRank = seat;
                continue;
            }
            if (seat != null && seat >= 1L && plan > 0 && seat > plan) {
                r.seedRank = null;
                continue;
            }
            Long free = nextFreeSeedOrNull(occupied, plan);
            r.seedRank = free;
            if (free != null) {
                occupied.add(free);
            }
        }
    }

    private Long nextFreeSeedOrNull(Set<Long> occupiedSeeds, int plan) {
        if (plan > 0) {
            for (long seat = 1; seat <= plan; seat++) {
                if (!occupiedSeeds.contains(seat)) {
                    return seat;
                }
            }
            return null;
        }
        return occupiedSeeds.stream().mapToLong(Long::longValue).max().orElse(0L) + 1L;
    }

    /** 按边取人:每个人记住自己来自哪条边(汇合/平行边时中间态要能追溯到入边) */
    private List<AssembledRow> autoCandidateRows(List<TStageRosterGroupBo> groups) {
        Map<Long, AssembledRow> merged = new LinkedHashMap<>();
        for (TStageRosterGroupBo g : groups) {
            // 非自动边返回空,不取人、不落座;多条边取到同一人时先到先得
            for (AssembledRow row : sliceEdge(g, () -> groupRows(g))) {
                merged.putIfAbsent(row.source.getId(), row);
            }
        }
        return new ArrayList<>(merged.values());
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

}

package com.dance.street.game.service.impl.roster;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TStage;
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
 * <p>口径:座位来自来源赛段给出的座号(淘汰赛的空洞即下一级的轮空座位,不许压紧);
 * 入边只有一条时按座号自动落位,多条(汇合/多圈)时全部进待落座;</p>
 *
 * @author duane
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RosterAssembler {

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
        return groups.size() > 1;
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
     *   <li><b>入边只有一条</b> → 自动落座:每人的座号 = 来源赛段给出的名次
     *       ({@link #assignSeeds}),1..N 每个座位都有一行实体,轮空不压塌陷;</li>
     *   <li><b>入边 ≥2 条</b>(汇合/多圈)→ 不做座位计算,所有人进待落位区,
     *       座位 1..N 照铺空位实体,由导播在中间态拖入。</li>
     * </ol>
     */
    public Projection project(TStage target, List<TStageRosterGroupBo> groups) {
        int plan = target.getTeamCountStart() == null || target.getTeamCountStart() <= 0
            ? 0 : target.getTeamCountStart().intValue();
        List<AssembledRow> rows = assembleRows(groups);
        if (multiEntry(groups)) {
            int slots = Math.max(plan, rows.size());
            return new Projection(true, slots, Map.of(), rows);
        }
        assignSeeds(rows, plan, new HashSet<>(), compactSeats(groups));
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
     * 按来源组规则取人(纯规则口径):人工调整(加人/外卡/剔除/换位)不在这里参与合并——
     * 它们直接改中间层的行,只有"全量重建"这一步才会回到这里从规则重新算。
     */
    public List<AssembledRow> assembleRows(List<TStageRosterGroupBo> groups) {
        return autoCandidateRows(groups);
    }

    /**
     * 落座:单一入边时,<b>把来源赛段给出的座号原样复制成新座号</b>。
     *
     * <p>「每一种赛段都有为输出选手提供座号的义务」:淘汰赛写 {@code displayRow + 1}
     * (双方轮空留下的空洞即下一级的空座位,签表不塌陷),海选/排名赛按圈名额累加写全局座号,
     * 擂台赛写最终名次。因此排座层不区分赛制,直接复制来源座号;只有座号缺失、
     * 超出计划规模、或该座位已被占用时才退化为"顺延取空位"。</p>
     */
    public void assignSeeds(List<AssembledRow> rows, int plan, Set<Long> occupiedSeeds) {
        assignSeeds(rows, plan, occupiedSeeds, false);
    }

    /**
     * 落座。
     *
     * @param compact true = 不复制来源座号,按取人顺序压成 1..N(见 {@link #compactSeats})
     */
    public void assignSeeds(List<AssembledRow> rows, int plan, Set<Long> occupiedSeeds, boolean compact) {
        Set<Long> occupied = new HashSet<>(occupiedSeeds);
        if (compact) {
            // 海选/排名赛 + 单入口:名次是"按名额累加的全局序号"(如某条出口取 9~24 名),
            // 不是签表位置 → 整体下移 min-1,把前面的空档压掉;
            // 中间夹着的空档保留(名次 3/4/7 → 座位 1/2/5),原座号仍记在 source_slot 里。
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
                    // 压紧后仍然超出本赛段容量(或名次撞座):不占座位,交给待落座区
                    r.seedRank = null;
                    continue;
                }
                // 名次缺失(来源还没定案):在容量内找空位,满了同样进待落座
                Long free = nextFreeSeedOrNull(occupied, plan);
                r.seedRank = free;
                if (free != null) {
                    occupied.add(free);
                }
            }
            return;
        }
        for (AssembledRow r : rows) {
            Long seat = r.source == null ? null : r.source.getFinalRank();
            if (seat != null && seat >= 1L && (plan <= 0 || seat <= plan) && occupied.add(seat)) {
                r.seedRank = seat;
                continue;
            }
            // 来源给的座号超出本赛段容量:不占座位,交给待落座区。
            // 复制成计划外的座位号会让签表/对阵看不见这个人(签表按计划人数铺)。
            if (seat != null && seat >= 1L && plan > 0 && seat > plan) {
                r.seedRank = null;
                continue;
            }
            // 座号缺失或已被占:在本赛段容量内顺延找空位;容量已满同样进待落座区
            Long free = nextFreeSeedOrNull(occupied, plan);
            r.seedRank = free;
            if (free != null) {
                occupied.add(free);
            }
        }
    }

    /**
     * 是否要把来源座号"压紧"成 1..N。
     *
     * <p>只有<b>单入口 + 来源是海选/排名赛</b>才这么做:它们给的名次是按名额累加的全局序号
     * (例如某条出口取 9~24 名,共 16 人),直接复制会把这 16 人放在目标赛段的 9~24 号座位,
     * 前面 1~8 号白白空着、后面还会被判成"超出容量"。</p>
     *
     * <p>淘汰赛不能压:它的名次就是签表位置,空洞代表下一级的轮空座位。</p>
     */
    private boolean compactSeats(List<TStageRosterGroupBo> groups) {
        if (groups.size() != 1) {
            return false;
        }
        Long sourceStageId = groups.get(0).getSourceStageId();
        if (sourceStageId == null) {
            return false;
        }
        TStage source = stageMapper.selectById(sourceStageId);
        if (source == null) {
            return false;
        }
        String mode = source.getStageMode();
        return StageModeEnum.AUDITION.getCode().equals(mode) || StageModeEnum.RANK.getCode().equals(mode);
    }

    /** 容量内下一个空座位;容量已满(或计划为 0 以外取不到)时返回 null = 进待落座区 */
    private Long nextFreeSeedOrNull(Set<Long> occupiedSeeds, int plan) {
        if (plan > 0) {
            for (long s = 1; s <= plan; s++) {
                if (!occupiedSeeds.contains(s)) {
                    return s;
                }
            }
            return null;
        }
        return occupiedSeeds.stream().mapToLong(Long::longValue).max().orElse(0L) + 1L;
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

    private static AssembledRow sourceRow(TCompetitor c) {
        AssembledRow r = new AssembledRow();
        r.source = c;
        r.entryTag = OutcomeStatusEnum.ADVANCE.getCode().equals(c.getOutcomeStatus())
            ? RosterConstants.ENTRY_ADVANCE : RosterConstants.ENTRY_REVIVE;
        return r;
    }

    /** 按边取人:每个人记住自己来自哪条边(汇合/平行边时中间态要能追溯到入边) */
    private List<AssembledRow> autoCandidateRows(List<TStageRosterGroupBo> groups) {
        Map<Long, AssembledRow> merged = new LinkedHashMap<>();
        for (TStageRosterGroupBo g : groups) {
            if (g.getSourceStageId() == null
                || RosterConstants.FILL_STREAM.equals(g.getFillMode())
                || RosterConstants.FILL_MANUAL.equals(g.getFillMode())) {
                continue;
            }
            List<TCompetitor> rows = new ArrayList<>(groupRows(g));
            int quota = g.getQuota() != null && g.getQuota() > 0 ? g.getQuota() : Integer.MAX_VALUE;
            int n = 0;
            for (TCompetitor c : rows) {
                if (n++ >= quota) {
                    log.warn("来源组(源赛段 {})配额 {} 已满,剩余候选截断",
                        g.getSourceStageId(), g.getQuota());
                    break;
                }
                AssembledRow row = sourceRow(c);
                row.sourceGroupId = g.getId();
                merged.putIfAbsent(c.getId(), row);
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

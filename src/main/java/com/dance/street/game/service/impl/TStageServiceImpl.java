package com.dance.street.game.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.mybatis.core.page.PageQuery;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TStageConfigBo;
import com.dance.street.game.domain.bo.TStageRosterGroupBo;
import com.dance.street.game.domain.TMatch;
import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.TMatchRound;
import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.TCompetitorMember;
import com.dance.street.game.domain.TPlayer;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TRefereeStage;
import com.dance.street.game.domain.bo.StageRefereeBo;
import com.dance.street.game.domain.TRoundScore;
import com.dance.street.game.domain.TStageRosterEntry;
import com.dance.street.game.domain.vo.PreBracketVo;
import com.dance.street.game.domain.vo.StageFlowVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.domain.vo.TStageRosterVo;
import com.dance.street.game.domain.TStage;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.SnowflakeJson;
import com.dance.street.game.engine.common.RuleConfigParser;
import com.dance.street.game.engine.common.StageConstants;
import com.dance.street.game.engine.common.PairingModeResolver;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import com.dance.street.game.service.RefereeSseNotifier;
import com.dance.street.game.service.TournamentEventNotifier;
import com.dance.street.game.engine.generator.KnockoutGenerator;
import com.dance.street.game.mapper.TCompetitorMapper;
import com.dance.street.game.mapper.TCompetitorMemberMapper;
import com.dance.street.game.mapper.TMatchMapper;
import com.dance.street.game.mapper.TMatchParticipantMapper;
import com.dance.street.game.mapper.TMatchRoundMapper;
import com.dance.street.game.mapper.TPlayerMapper;
import com.dance.street.game.mapper.TRefereeStageMapper;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TStageMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITStageRosterService;
import com.dance.street.game.service.ITStageService;
import com.dance.street.game.service.ITRefereeStageService;
import com.dance.street.game.service.impl.stage.StageDeletionService;

import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 赛段流程Service业务层处理
 *
 * @author duane
 * @date 2026-01-06
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TStageServiceImpl implements ITStageService {

    private final TStageMapper baseMapper;
    private final TTournamentMapper tournamentMapper;
    private final TMatchMapper matchMapper;
    private final TMatchParticipantMapper participantMapper;
    private final TMatchRoundMapper matchRoundMapper;
    private final TCompetitorMapper competitorMapper;
    private final TCompetitorMemberMapper competitorMemberMapper;
    private final TRefereeMapper refereeMapper;
    private final TRefereeStageMapper refereeStageMapper;
    private final ITRefereeStageService refereeStageService;
    private final TPlayerMapper playerMapper;
    private final ITStageRosterService rosterService;
    /** 赛段链遍历的唯一入口(以 next 链为事实源) */
    private final StageChain stageChain;
    /** 赛段图节点删除的统一用例 */
    private final StageDeletionService stageDeletionService;
    /** 赛段配置变化要推裁判端:判罚方式在「裁判判罚 ↔ 导播台判定」之间切换会改变裁判端界面 */
    private final RefereeSseNotifier refereeSseNotifier;
    private final TournamentEventNotifier tournamentEventNotifier;

    /**
     * 查询赛段流程
     *
     * @param id 主键
     * @return 赛段流程
     */
    @Override
    public TStageVo queryById(Long id){
        TStageVo vo = baseMapper.selectVoById(id);
        fillChainPrev(vo);
        return vo;
    }

    /**
     * 给单个 VO 填"上一赛段"(展示字段):库里已不再有 prev 列,一律按 next 链推导。
     * 前端(流程图找头节点、导播台"上一赛段"提示、对战树取上游场次)都读这个字段。
     */
    private void fillChainPrev(TStageVo vo) {
        if (vo == null || vo.getId() == null) {
            return;
        }
        TStage self = baseMapper.selectById(vo.getId());
        if (self == null) {
            return;
        }
        TStage prev = stageChain.prevOf(self);
        vo.setPrevStageId(prev == null ? null : prev.getId());
    }

    /**
     * 分页查询赛段流程列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 赛段流程分页列表
     */
    @Override
    public TableDataInfo<TStageVo> queryPageList(TStageBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TStage> lqw = buildQueryWrapper(bo);
        Page<TStageVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        enrichIncoming(result.getRecords());
        return TableDataInfo.build(result);
    }

    /**
     * 查询符合条件的赛段流程列表
     *
     * @param bo 查询条件
     * @return 赛段流程列表
     */
    @Override
    public List<TStageVo> queryList(TStageBo bo) {
        LambdaQueryWrapper<TStage> lqw = buildQueryWrapper(bo);
        List<TStageVo> list = baseMapper.selectVoList(lqw);
        enrichIncoming(list);
        return list;
    }

    /**
     * 补入名单摘要,并填充"上一赛段"展示字段。
     *
     * <p>库里已没有 prev 列,这一字段只能是链上推导值({@code 谁的 next 指向我})。
     * 管理端流程图用它找头节点并排序整条链,导播台用它显示"上一赛段",
     * 所以这里统一一次性推导,避免逐段回查。</p>
     */
    private void enrichIncoming(List<TStageVo> stages) {
        if (stages == null || stages.isEmpty()) {
            return;
        }
        Set<Long> tournamentIds = stages.stream()
            .map(TStageVo::getTournamentId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, Long> prevByStageId = new HashMap<>();
        for (Long tid : tournamentIds) {
            prevByStageId.putAll(stageChain.prevIdsFromChain(tid));
        }
        // 名单一次批量取回:此前逐赛段 listByTarget,且每个来源组还要回查一次来源赛段
        Map<Long, List<TStageRosterVo>> rostersByStage = rosterService.listByTargets(
            stages.stream().map(TStageVo::getId).filter(Objects::nonNull).toList());
        for (TStageVo stage : stages) {
            if (stage.getId() != null) {
                if (stage.getTournamentId() != null && tournamentIds.contains(stage.getTournamentId())) {
                    // 链上没有前驱即入口赛段:显式置 null
                    stage.setPrevStageId(prevByStageId.get(stage.getId()));
                }
                List<TStageRosterVo> rosters = rostersByStage.getOrDefault(stage.getId(), List.of());
                stage.setIncoming(rosters.isEmpty() ? null : rosters);
            }
        }
    }

    private LambdaQueryWrapper<TStage> buildQueryWrapper(TStageBo bo) {
        Map<String, Object> params = bo.getParams();
        LambdaQueryWrapper<TStage> lqw = Wrappers.lambdaQuery();
        lqw.orderByAsc(TStage::getId);
        lqw.eq(bo.getTournamentId() != null, TStage::getTournamentId, bo.getTournamentId());
        lqw.eq(bo.getParentStageId() != null, TStage::getParentStageId, bo.getParentStageId());
        lqw.like(StringUtils.isNotBlank(bo.getName()), TStage::getName, bo.getName());
        lqw.eq(StringUtils.isNotBlank(bo.getStageMode()), TStage::getStageMode, bo.getStageMode());
        lqw.eq(bo.getVisualColIndex() != null, TStage::getVisualColIndex, bo.getVisualColIndex());
        lqw.eq(StringUtils.isNotBlank(bo.getRuleConfig()), TStage::getRuleConfig, bo.getRuleConfig());
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), TStage::getStatus, bo.getStatus());
        return lqw;
    }

    /**
     * 新增赛段流程
     *
     * <p>插入位置只认意图:{@code afterStageId} 表示"插到该赛段之后",
     * 为空则插到链头。前后指针由 {@link StageChain} 按链顺序写入,
     * 客户端传的 {@code nextStageId} 不参与。</p>
     *
     * @param bo 赛段流程
     * @return 新增后的赛段流程
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TStageVo insertByBo(TStageBo bo) {
        TStage add = MapstructUtils.convert(bo, TStage.class);
        if (add.getTournamentId() == null || tournamentMapper.selectById(add.getTournamentId()) == null) {
            throw new ServiceException("赛段必须归属一个有效赛事");
        }
        if (StringUtils.isBlank(add.getName())) {
            throw new ServiceException("赛段名称不能为空");
        }
        if (StringUtils.isBlank(add.getStageMode())) {
            throw new ServiceException("赛段模式不能为空");
        }
        StageModeEnum.require(add.getStageMode());
        if (StringUtils.isBlank(add.getStatus())) {
            add.setStatus(StageConstants.STAGE_DRAFT);
        }
        if (!StageConstants.STAGE_DRAFT.equals(add.getStatus())) {
            throw new ServiceException("新建赛段必须从 DRAFT 开始，不能直接写入状态 {}", add.getStatus());
        }

        // 归一化 ruleConfig:补齐与 teamCountStart/teamCountEnd 对应的配置字段
        normalizeRuleConfig(add, null);

        Long afterStageId = bo.getAfterStageId();

        // 指针列一律由 StageChain 写:先落一行不带指针的记录,再按链顺序接入
        add.setNextStageId(null);
        baseMapper.insert(add);
        bo.setId(add.getId());
        stageChain.insertAfter(add, afterStageId);

        // 名单:新赛段有直接前驱时同步写入默认来源组(source=prev, ADVANCE, AUTO)。
        // 晋级名额与人选一律以名单来源组 + 赛段配置为准,建链时不做任何名额联动。
        rosterService.ensureRosterForStage(add);
        // 中间插入(A→Z→B):移除旧的 A→B 直连边,按 Z 的类型建立 Z→B,保留 B 的其他汇合来源。
        if (add.getNextStageId() != null) {
            rosterService.reconcileAfterStageInsert(
                afterStageId, add.getId(), add.getNextStageId());
        }
        assignTournamentRefereesByDefault(add);

        TStageVo vo = MapstructUtils.convert(add, TStageVo.class);
        fillChainPrev(vo);
        return vo;
    }

    /**
     * 修改赛段流程
     *
     * <p><b>只改配置,不动赛段链。</b>入口 BO 里根本没有前后指针字段,改链只能调用
     * {@link #moveStageAfter(Long, Long)};此前"改一个场次配置却重写整条链"的根因
     * (客户端回传过期指针副本)从入口上就不存在了。</p>
     *
     * <p>配置写入见 {@link #updateConfig(TStageConfigBo)}。</p>
     *
     * @param bo 赛段流程
     * @return 修改后的赛段流程
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TStageVo updateByBo(TStageBo bo) {
        TStage update = MapstructUtils.convert(bo, TStage.class);
        return applyConfigUpdate(update);
    }

    /**
     * 修改赛段配置(不含链表指针)。
     *
     * <p>配置面板的唯一入口。BO 里没有 prev/next,因此这条路径在任何情况下都不会改链,
     * 也不会触发下游名单对账——纯配置保存不产生链写入。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TStageVo updateConfig(TStageConfigBo bo) {
        TStage patch = new TStage();
        patch.setId(bo.getId());
        patch.setName(bo.getName());
        patch.setStageMode(bo.getStageMode());
        patch.setMembers(bo.getMembers());
        patch.setVisualColIndex(bo.getVisualColIndex());
        patch.setRuleConfig(bo.getRuleConfig());
        patch.setStatus(bo.getStatus());
        patch.setTeamCountStart(bo.getTeamCountStart());
        patch.setTeamCountEnd(bo.getTeamCountEnd());
        patch.setVisualConfig(bo.getVisualConfig());
        patch.setRemark(bo.getRemark());
        return applyConfigUpdate(patch);
    }

    /**
     * 调整赛段链顺序:把 {@code stageId} 移到 {@code afterStageId} 之后(空 = 移到链头)。
     *
     * <p>改链从「回传前后指针」改成「声明意图」:顺序由 {@link StageChain} 按现有链推导后
     * 统一写入,不读客户端手里的指针副本,也就不存在过期副本把链写歪的窗口。</p>
     *
     * <p><b>已不再对外提供入口</b>:赛段在链上的位置不允许调整,HTTP 侧的移动接口已移除。
     * 本方法仅为内部/测试构造链场景保留,业务代码不要调用。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void moveStageAfter(Long stageId, Long afterStageId) {
        List<Long> changed = stageChain.moveAfter(stageId, afterStageId);
        // 链上位置变了的赛段:入口↔非入口会切换,默认名单来源(签到流/上游晋级)要跟着迁
        for (Long changedStageId : changed) {
            rosterService.reconcileAfterLinkChange(changedStageId);
        }
    }

    private static boolean changed(Object before, Object requested) {
        return requested != null && !Objects.equals(before, requested);
    }

    /**
     * 配置写入的公共实现:只写 patch 上显式提供的配置列。
     *
     * <p>patch 由调用方按入口裁剪({@link #updateByBo} 会清掉指针列),因此
     * {@code updateById} 生成的 UPDATE 里不会出现 prev/next——链的写入只发生在
     * {@link StageChain} 里。</p>
     */
    private TStageVo applyConfigUpdate(TStage patch) {
        if (patch.getId() == null) {
            throw new ServiceException("赛段ID不能为空");
        }
        TStage oldStage = baseMapper.selectById(patch.getId());
        if (oldStage == null) {
            throw new ServiceException("赛段不存在: {}", patch.getId());
        }
        if (patch.getStatus() != null && !Objects.equals(oldStage.getStatus(), patch.getStatus())) {
            throw new ServiceException(
                "赛段状态只能通过开始、完成或重置流程变更，不能直接标记为 {}（当前 {}）",
                patch.getStatus(), oldStage.getStatus());
        }
        boolean ruleConfigChanged = changed(oldStage.getRuleConfig(), patch.getRuleConfig())
            && !onlyResultPublishConfigChanged(oldStage.getRuleConfig(), patch.getRuleConfig());
        if (!StageConstants.STAGE_DRAFT.equals(oldStage.getStatus())
            && (changed(oldStage.getStageMode(), patch.getStageMode())
            || changed(oldStage.getMembers(), patch.getMembers())
            || changed(oldStage.getTeamCountStart(), patch.getTeamCountStart())
            || changed(oldStage.getTeamCountEnd(), patch.getTeamCountEnd())
            || ruleConfigChanged)) {
            throw new ServiceException(
                "赛段[{}]已开始，赛制、人数及结果公布设置以外的规则已锁定；如需调整请先重置为草稿",
                oldStage.getName());
        }
        // 指针列不参与配置更新:客户端值已被调用方清空,这里再兜一层,
        // 避免后续有人往 patch 里塞指针又把链写歪
        patch.setNextStageId(null);
        // 状态列同理:上面的守卫已保证「要么没传、要么与库中一致」,这里清掉后 UPDATE 里不会出现 status,
        // 配置入口无论如何不可能改状态(状态只走开始/完成/重置流程,见 StageRunService / StageSettlementService)
        patch.setStatus(null);

        // 归一化 ruleConfig:补齐与 teamCountStart/teamCountEnd 对应的配置字段
        normalizeRuleConfig(patch, oldStage);

        // 分圈保护:海选圈数只增不减、开始后锁定(前端已改为按钮加圈)
        validateAuditionCircleChange(patch, oldStage);

        baseMapper.updateById(patch);

        // 海选:裁判到底是谁由「圈配置」决定 —— 赛段级绑定跟着圈裁判同步一份
        syncAuditionCircleReferees(baseMapper.selectById(patch.getId()));

        // 判罚/公布方式等配置变了:裁判页需要重新决定是否展示判罚界面,
        // 导播端与大屏也需要重新判断哪些结果可见,因此两条 SSE 通道都推一次。
        TStage updated = baseMapper.selectById(patch.getId());
        if (liveConfigChanged(oldStage, updated)) {
            refereeSseNotifier.notifyStage(patch.getId(), "config");
            tournamentEventNotifier.notify(updated.getTournamentId(), updated.getId(), null, "config");
        }

        // 返回库中整行:prev/next 是展示列,链由 StageChain 维护,回读才是最新口径
        TStageVo vo = MapstructUtils.convert(updated, TStageVo.class);
        fillChainPrev(vo);
        return vo;
    }

    /**
     * 海选赛段的裁判按「圈配置」同步到赛段级绑定({@code t_referee_stage})。
     *
     * <p>海选的裁判在圈配置里逐圈指定({@code ruleConfig.circleRefereeIds}),赛段级「裁判组」
     * 不再单独配置;但裁判端的赛段列表、大屏记分板的裁判名、SSE 推送受众都读赛段级绑定,
     * 所以这里按圈裁判的并集同步一份,保证"圈上配了谁"与"谁能看到这个赛段"是同一批人。</p>
     *
     * <p>非海选赛段、以及从未配过圈裁判(null)的赛段不动——后者可能是模板/手工分配的赛段级裁判。</p>
     */
    private void syncAuditionCircleReferees(TStage stage) {
        if (stage == null || stage.getId() == null
            || !stageCap(stage).audition()) {
            return;
        }
        RuleConfigHolder rc = RuleConfigParser.parse(stage.getRuleConfig());
        List<List<Long>> circleRefs = rc == null ? null : rc.getCircleRefereeIds();
        if (circleRefs == null) {
            return;
        }
        LinkedHashSet<Long> wanted = new LinkedHashSet<>();
        for (List<Long> circle : circleRefs) {
            if (circle == null) {
                continue;
            }
            circle.stream().filter(Objects::nonNull).forEach(wanted::add);
        }
        List<Long> current = refereeStageMapper.selectList(Wrappers.<TRefereeStage>lambdaQuery()
                .eq(TRefereeStage::getStageId, stage.getId()))
            .stream()
            .map(TRefereeStage::getRefereeId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (new LinkedHashSet<>(current).equals(wanted)) {
            return;
        }
        StageRefereeBo bo = new StageRefereeBo();
        bo.setStageId(stage.getId());
        bo.setTournamentId(stage.getTournamentId());
        bo.setRefereeIds(new ArrayList<>(wanted));
        refereeStageService.assignReferees(bo);
        log.info("海选赛段[{}]裁判按圈配置同步:{} 人", stage.getId(), wanted.size());
    }

    /**
     * 新建非海选赛段时默认关联当前赛事的全部裁判。
     *
     * <p>海选裁判由 ruleConfig.circleRefereeIds 逐圈决定,不能在这里提前写赛段级绑定;
     * 其他赛制没有圈级拆分,赛事裁判就是该赛段的默认裁判组。后续仍可在草稿期显式调整。</p>
     */
    private void assignTournamentRefereesByDefault(TStage stage) {
        if (stage == null || stage.getId() == null || stage.getTournamentId() == null
            || stageCap(stage).audition()) {
            return;
        }
        List<Long> refereeIds = refereeMapper.selectList(Wrappers.<TReferee>lambdaQuery()
                .eq(TReferee::getTournamentId, stage.getTournamentId())
                .select(TReferee::getId))
            .stream().map(TReferee::getId).filter(Objects::nonNull).distinct().toList();
        if (refereeIds.isEmpty()) {
            return;
        }
        StageRefereeBo bo = new StageRefereeBo();
        bo.setStageId(stage.getId());
        bo.setTournamentId(stage.getTournamentId());
        bo.setRefereeIds(refereeIds);
        refereeStageService.assignReferees(bo);
        log.info("非海选赛段[{}]默认关联赛事[{}]全部裁判:{} 人",
            stage.getId(), stage.getTournamentId(), refereeIds.size());
    }

    /**
     * 影响裁判端、导播端或大屏展示的配置是否变化:公布方式/范围、分数可见性、满分与打分口径。
     *
     * <p>只看这几项而不是整段 JSON:配置保存会做归一化(补字段、调顺序),
     * 按字符串比较会对每次保存都推一次无意义的刷新。</p>
     */
    private boolean liveConfigChanged(TStage oldStage, TStage updated) {
        if (oldStage == null || updated == null) {
            return false;
        }
        RuleConfigHolder oldRc = RuleConfigParser.parse(oldStage.getRuleConfig());
        RuleConfigHolder newRc = RuleConfigParser.parse(updated.getRuleConfig());
        if (oldRc == null || newRc == null) {
            return oldRc != newRc && !Objects.equals(oldStage.getRuleConfig(), updated.getRuleConfig());
        }
        return !Objects.equals(oldRc.resolvePublishMode(), newRc.resolvePublishMode())
            || !Objects.equals(oldRc.getPublishScope(), newRc.getPublishScope())
            || !Objects.equals(oldRc.getShowScore(), newRc.getShowScore())
            || !Objects.equals(oldRc.getScoreDisplay(), newRc.getScoreDisplay())
            || !Objects.equals(oldRc.getMaxScore(), newRc.getMaxScore())
            || !Objects.equals(oldRc.getScoring(), newRc.getScoring());
    }

    /**
     * 已开始赛段只允许改结果公布区:其余 ruleConfig 字段仍必须与库中一致。
     *
     * <p>比较 JSON 树而不是解析后的 Bean,避免未建模字段(如擂台赛 scale、顶层
     * advanceCount)被静默忽略后成为绕过锁定的入口。公布模式的位置兼容淘汰赛
     * {@code knockout.publishMode} 与其他赛制顶层 {@code publishMode}。</p>
     */
    private boolean onlyResultPublishConfigChanged(String beforeJson, String afterJson) {
        if (Objects.equals(beforeJson, afterJson)) {
            return true;
        }
        if (StringUtils.isBlank(beforeJson) || StringUtils.isBlank(afterJson)) {
            return false;
        }
        try {
            ObjectMapper mapper = SnowflakeJson.mapper();
            JsonNode before = mapper.readTree(beforeJson);
            JsonNode after = mapper.readTree(afterJson);
            stripResultPublishSettings(before);
            stripResultPublishSettings(after);
            return Objects.equals(before, after);
        } catch (Exception e) {
            log.warn("赛段结果公布配置比较失败,按锁定处理: {}", e.getMessage());
            return false;
        }
    }

    private void stripResultPublishSettings(JsonNode root) {
        if (!(root instanceof ObjectNode config)) {
            return;
        }
        config.remove("publishMode");
        config.remove("publishScope");
        config.remove("showScore");
        config.remove("scoreDisplay");
        JsonNode knockout = config.get("knockout");
        if (knockout instanceof ObjectNode knockoutConfig) {
            knockoutConfig.remove("publishMode");
        }
    }

    /**
     * 海选分圈变更保护:
     * 1) 赛段开始/结束后分圈结构(圈数/每圈名额/每圈裁判)锁定,禁止修改;
     * 2) 规划中允许增加圈,不允许减少到少于当前已生成的圈场次数。
     */
    private void validateAuditionCircleChange(TStage update, TStage oldStage) {
        if (oldStage == null
            || !stageCap(oldStage).audition()
            || StringUtils.isBlank(update.getRuleConfig())) {
            return;
        }
        RuleConfigHolder oldRc = RuleConfigParser.parse(oldStage.getRuleConfig());
        RuleConfigHolder newRc = RuleConfigParser.parse(update.getRuleConfig());
        if (oldRc == null || newRc == null) {
            return;
        }
        int oldCircles = oldRc.getCircles() == null ? 1 : Math.max(1, oldRc.getCircles());
        int newCircles = newRc.getCircles() == null ? oldCircles : Math.max(1, newRc.getCircles());
        boolean structureChanged = oldCircles != newCircles
            || !Objects.equals(oldRc.getCircleAdvanceCounts(), newRc.getCircleAdvanceCounts())
            || !Objects.equals(oldRc.getCircleRefereeIds(), newRc.getCircleRefereeIds());
        if (!structureChanged) {
            return;
        }
        boolean locked = StageConstants.STAGE_GAMING.equals(oldStage.getStatus())
            || StageConstants.STAGE_SETTLED.equals(oldStage.getStatus())
            || StageConstants.STAGE_DISCARD.equals(oldStage.getStatus());
        if (locked) {
            throw new ServiceException("赛段已开始或结束,分圈结构(圈数/每圈名额/每圈裁判)已锁定,不能修改");
        }
        if (newCircles < oldCircles) {
            throw new ServiceException("分圈数只能增加不能减少(当前 {} 圈);如某圈不再使用,请把该圈晋级名额设为 0", oldCircles);
        }
        // 圈数增加时,新增圈必须在原有圈之后追加;以实际已建场次为下限兜底
        long actualZones = matchMapper.selectCount(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, oldStage.getId())
            .likeRight(TMatch::getDisplayZone, "ZONE-"));
        if (newCircles < actualZones) {
            throw new ServiceException("已实际建成 {} 个圈,分圈数不能再减少", actualZones);
        }
    }

    /**
     * 归一化赛段 rule_config,保证与赛段权威字段(teamCountStart/teamCountEnd)一致。
     *
     * <p>rule_config 是前后端共用的配置契约(前端按 {@code knockout.advanceCount} 判定决赛等),
     * 但新增/修改赛段时客户端可能遗漏嵌套字段(尤其直接走 API 的场景),
     * 造成「赛段表字段与 rule_config 不一致 → 前端渲染/引擎解析异常」。
     * 此处仅做补全,不覆盖客户端已显式提供的值。</p>
     *
     * <ul>
     *   <li>KNOCKOUT:补齐 {@code knockout.teamsCount} / {@code knockout.advanceCount}(取赛段字段)</li>
     *   <li>AUDITION:补齐顶层 {@code advanceCount}(取赛段字段)</li>
     *   <li>通用:补齐顶层 {@code mode}</li>
     * </ul>
     *
     * @param stage 本次要写入的字段(patch,可能只带了部分配置)
     * @param old   库中现值:patch 没带的赛段字段按它补,避免归一化把已有值冲掉
     */
    private void normalizeRuleConfig(TStage stage, TStage old) {
        String mode = StringUtils.isNotBlank(stage.getStageMode())
            ? stage.getStageMode()
            : (old == null ? null : old.getStageMode());
        if (StringUtils.isBlank(mode) || StringUtils.isBlank(stage.getRuleConfig())) {
            return;
        }
        Long teamCountStart = stage.getTeamCountStart() != null
            ? stage.getTeamCountStart()
            : (old == null ? null : old.getTeamCountStart());
        Long teamCountEnd = stage.getTeamCountEnd() != null
            ? stage.getTeamCountEnd()
            : (old == null ? null : old.getTeamCountEnd());
        try {
            ObjectMapper mapper = SnowflakeJson.mapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> rc = mapper.readValue(stage.getRuleConfig(), Map.class);
            if (rc == null) {
                return;
            }
            boolean changed = false;
            if (StageModeEnum.KNOCKOUT.getCode().equals(mode)) {
                Map<String, Object> ko = nestedMap(rc, "knockout");
                if (teamCountStart != null && teamCountStart > 0 && ko.get("teamsCount") == null) {
                    ko.put("teamsCount", teamCountStart);
                    changed = true;
                }
                if (teamCountEnd != null && teamCountEnd > 0 && ko.get("advanceCount") == null) {
                    ko.put("advanceCount", teamCountEnd);
                    changed = true;
                }
            } else if (StageModeEnum.AUDITION.getCode().equals(mode)) {
                if (teamCountEnd != null && teamCountEnd > 0 && rc.get("advanceCount") == null) {
                    rc.put("advanceCount", teamCountEnd);
                    changed = true;
                }
            } else if (StageModeEnum.RANK.getCode().equals(mode)) {
                if (teamCountEnd != null && teamCountEnd > 0 && rc.get("advanceCount") == null) {
                    rc.put("advanceCount", teamCountEnd);
                    changed = true;
                }
            } else if (StageModeEnum.ARENA.getCode().equals(mode)) {
                // 擂台赛最终只决出 1 个胜者
                if (isBlankCount(teamCountEnd)) {
                    stage.setTeamCountEnd(1L);
                    teamCountEnd = 1L;
                }
            }
            if (rc.get("mode") == null) {
                rc.put("mode", mode);
                changed = true;
            }
            if (changed) {
                stage.setRuleConfig(mapper.writeValueAsString(rc));
            }
        } catch (Exception e) {
            log.warn("赛段[{}] ruleConfig 归一化失败,保留原配置: {}", stage.getId(), e.getMessage());
        }
    }

    private static boolean isBlankCount(Long v) {
        return v == null || v == 0L;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nestedMap(Map<String, Object> rc, String key) {
        Object v = rc.get(key);
        if (v instanceof Map) {
            return (Map<String, Object>) v;
        }
        Map<String, Object> m = new HashMap<>();
        rc.put(key, m);
        return m;
    }

    /**
     * 校验并批量删除赛段流程信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        return stageDeletionService.delete(ids);
    }

    /**
     * 根据比赛ID获取第一个赛段
     *
     * @param tournamentId 比赛ID
     * @return 第一个赛段，不关心赛段状态（DRAFT/GAMING/SETTLED 均返回）
     */
    @Override
    public TStageVo getFirstStageByTournamentId(Long tournamentId) {
        // 链头 = 没有任何赛段以 next 指向它(排除 DISCARD),由 StageChain 统一推导。
        // 刻意不走 queryList:那会把全赛事每个赛段的中间层名单(全量行)都读出来做 VO 装配,
        // 而签到等热路径只用 id / 赛制 / 状态 / 成员数——人一多,"顺手读全量"就成了签到的延迟来源。
        TStage head = stageChain.headOf(tournamentId);
        if (head == null) {
            log.warn("赛事[{}]未找到任何赛段(已排除 DISCARD)", tournamentId);
            return null;
        }
        return MapstructUtils.convert(head, TStageVo.class);
    }

    /**
     * 大屏赛程流转:按链表顺序返回全部赛段,并附带当前进行中赛段/场次
     */
    @Override
    public StageFlowVo getFlowByTournamentId(Long tournamentId) {
        return getFlowByTournamentId(tournamentId, null);
    }

    /**
     * 大屏赛程流转(可指定赛段):{@code stageId} 非空时"当前"固定取该赛段自身的进行中场次,
     * 供多赛段并行时控件绑定具体赛段;为空时回退"链上第一个 GAMING"(旧行为)。
     */
    @Override
    public StageFlowVo getFlowByTournamentId(Long tournamentId, Long stageId) {
        StageFlowVo vo = new StageFlowVo();
        // 链顺序统一由 StageChain 推导(以 next 链为事实源,断链按 id 兜底补齐)
        List<TStage> chain = stageChain.orderedChain(tournamentId);
        if (chain.isEmpty()) {
            vo.setStages(List.of());
            return vo;
        }

        vo.setStages(chain.stream().map(s -> {
            StageFlowVo.StageFlowItem item = new StageFlowVo.StageFlowItem();
            item.setId(s.getId());
            item.setName(s.getName());
            item.setStageMode(s.getStageMode());
            item.setStatus(s.getStatus());
            item.setTeamCountStart(s.getTeamCountStart() == null ? null : Long.valueOf(s.getTeamCountStart()));
            item.setTeamCountEnd(s.getTeamCountEnd() == null ? null : Long.valueOf(s.getTeamCountEnd()));
            item.setIsInitialized(s.getIsInitialized());
            return item;
        }).toList());

        // 当前赛段:绑定则取绑定的赛段,未绑定/绑定失效则回退"链上第一个 GAMING"
        TStage current = stageId == null ? null : chain.stream()
            .filter(s -> Objects.equals(s.getId(), stageId))
            .findFirst().orElse(null);
        if (current == null) {
            current = firstGamingStage(chain);
        }
        if (current == null) {
            return vo;
        }
        vo.setCurrentStageId(current.getId());
        // 绑定赛段尚未开赛/已结束:没有"当前场次",控件保持透明(但仍回 currentStageId 供标题展示)
        if (!StageConstants.STAGE_GAMING.equals(current.getStatus())) {
            return vo;
        }

        List<TMatch> matches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
            .eq(TMatch::getStageId, current.getId())
            .eq(TMatch::getStatus, StageConstants.MATCH_GAMING)
            .orderByAsc(TMatch::getId));
        if (matches.isEmpty()) {
            return vo;
        }
        TMatch match = matches.get(0);
        StageFlowVo.StageFlowMatch m = new StageFlowVo.StageFlowMatch();
        m.setId(match.getId());
        m.setName(match.getName());
        m.setStatus(match.getStatus());
        m.setMatchMode(match.getMatchMode());
        vo.setCurrentMatch(m);

        List<TMatchParticipant> parts = participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
            .eq(TMatchParticipant::getMatchId, match.getId())
            .orderByAsc(TMatchParticipant::getDisplaySlotIndex));
        // 批量取参赛方首位成员的照片(competitor -> member -> player.avatar)
        List<Long> compIds = parts.stream()
            .map(TMatchParticipant::getCompetitorId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        Map<Long, String> avatarMap = new HashMap<>();
        if (!compIds.isEmpty()) {
            List<TCompetitorMember> members = competitorMemberMapper.selectList(Wrappers.<TCompetitorMember>lambdaQuery()
                .in(TCompetitorMember::getCompetitorId, compIds));
            if (!members.isEmpty()) {
                List<Long> playerIds = members.stream()
                    .map(TCompetitorMember::getPlayerId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
                Map<Long, String> playerAvatar = playerMapper.selectList(Wrappers.<TPlayer>lambdaQuery()
                        .in(TPlayer::getId, playerIds))
                    .stream()
                    .filter(p -> StringUtils.isNotBlank(p.getAvatar()))
                    .collect(Collectors.toMap(TPlayer::getId, TPlayer::getAvatar, (a, b) -> a));
                for (TCompetitorMember mem : members) {
                    String av = playerAvatar.get(mem.getPlayerId());
                    if (StringUtils.isNotBlank(av)) {
                        avatarMap.putIfAbsent(mem.getCompetitorId(), av);
                    }
                }
            }
        }
        // 参赛方姓名一次批量取回(compIds 上面已算好),避免逐个 selectById
        Map<Long, String> compNameById = new HashMap<>();
        if (!compIds.isEmpty()) {
            for (TCompetitor c : competitorMapper.selectByIds(compIds)) {
                compNameById.put(c.getId(), c.getName());
            }
        }
        vo.setCurrentMatchParticipants(parts.stream().map(p -> {
            StageFlowVo.StageFlowParticipant sp = new StageFlowVo.StageFlowParticipant();
            sp.setCompetitorId(p.getCompetitorId());
            sp.setAvatar(avatarMap.get(p.getCompetitorId()));
            sp.setDisplaySlotIndex(p.getDisplaySlotIndex());
            sp.setSlotKind(p.getSlotKind());
            sp.setScoreValue(p.getScoreValue());
            sp.setRankInMatch(p.getRankInMatch());
            sp.setOutcomeStatus(p.getOutcomeStatus());
            if (p.getCompetitorId() != null) {
                sp.setCompetitorName(compNameById.containsKey(p.getCompetitorId())
                    ? compNameById.get(p.getCompetitorId())
                    : ("选手 " + p.getCompetitorId()));
            } else {
                sp.setCompetitorName(StageConstants.SLOT_BYE.equals(p.getSlotKind()) ? "轮空" : "待定");
            }
            return sp;
        }).toList());
        return vo;
    }

    /** 链上第一个进行中(GAMING)赛段;无则返回 null */
    private static TStage firstGamingStage(List<TStage> chain) {
        return chain.stream()
            .filter(s -> StageConstants.STAGE_GAMING.equals(s.getStatus()))
            .findFirst().orElse(null);
    }

    /**
     * 下一赛段对战树预排:优先返回本赛段真实参赛方(GENERATED);
     * 未生成时取上一赛段胜者(含未最终确认的已结算胜者),按 finalRank 顺序作为预排种子。
     */
    @Override
    public PreBracketVo getPreBracket(Long stageId) {
        TStage stage = baseMapper.selectById(stageId);
        if (stage == null) {
            throw new ServiceException("赛段不存在");
        }
        PreBracketVo vo = new PreBracketVo();
        vo.setStageId(stage.getId());
        vo.setStageName(stage.getName());
        vo.setStageMode(stage.getStageMode());

        // 本赛段已有参赛方:已初始化或已写入晋级者时视为真实名单(GENERATED);
        // 仅提前加入 GUEST(未初始化、未确认晋级)时仍进入 PREVIEW,与上一赛段晋级者合并展示
        List<TCompetitor> own = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
            .eq(TCompetitor::getStageId, stage.getId())
            .orderByAsc(TCompetitor::getSeedRank)
            .orderByAsc(TCompetitor::getId));
        boolean initialized = Long.valueOf(1L).equals(stage.getIsInitialized());
        boolean hasConfirmedAdvancer = own.stream().anyMatch(c -> c.getSourceCompetitorId() != null);
        if (!own.isEmpty() && (initialized || hasConfirmedAdvancer)) {
            vo.setStatus("GENERATED");
            // 座位 = seedRank(座位号),不能用数组下标占位:中间态移出/轮空会在座位上留空洞,
            // 按下标压紧后,对战树(含擂台赛名单)显示的位置会和中间态看到的不一致
            List<PreBracketVo.PreSeed> generated = new ArrayList<>(own.size());
            for (TCompetitor c : own) {
                // 没有座位号(未落位/异常)不参与座位排布:绝不按下标占位,否则与中间态座位口径不一致
                if (c.getSeedRank() == null || c.getSeedRank() <= 0) {
                    continue;
                }
                generated.add(toPreSeed(c, c.getSeedRank(), null));
            }
            vo.setSeededCompetitors(generated);
            return vo;
        }

        // 预排只做"中间态 → 对阵"的视图变换:内容全部来自中间层名单,与赛段链、来源组配置无关
        // (依赖方向/开赛条件那些由开赛守卫与名单就绪度负责,这里不重复判断)。
        // 淘汰赛与擂台赛段要预排(对战树控件绑上去后,名单写入前也能看到"目前谁进来了");
        // 其余赛制不做对阵预排。
        StageModeEnum targetCap = stageCap(stage);
        boolean targetKnockout = targetCap.knockout();
        boolean targetArena = targetCap.arena();
        if (!targetKnockout && !targetArena) {
            vo.setStatus("UNSUPPORTED");
            return vo;
        }

        // 预排来自中间层名单(唯一事实):生成/重建时已经算好并落成行,这里只做"座位 → 对阵"的视图变换。
        // 空位(BYE/PENDING)照样占号,按 slot 索引落座,绝不把"有人的行"重新编号压紧。
        List<TStageRosterEntry> entries = rosterService.entriesOf(stage.getId());
        List<TStageRosterEntry> filled = entries.stream()
            .filter(e -> StageConstants.SLOT_PLAYER.equals(e.getSlotKind()))
            .toList();
        if (filled.isEmpty()) {
            // 来源还没结算/名单还没生成:仅返回已提前加入的参赛方(如已确认过的名单)
            if (!own.isEmpty()) {
                vo.setSeededCompetitors(own.stream()
                    .map(c -> toPreSeed(c, c.getSeedRank(), null)).toList());
            }
            vo.setStatus("WAIT_PREV");
            return vo;
        }

        // 来源场次名取预排候选的真实来源赛段(直接从中间态行的 sourceStageId 推导,不查来源组)
        Map<Long, String> sourceMatch = new HashMap<>();
        List<Long> sourceCompetitorIds = filled.stream()
            .map(TStageRosterEntry::getSourceCompetitorId)
            .filter(Objects::nonNull).distinct().toList();
        Map<Long, TCompetitor> sourceById = sourceCompetitorIds.isEmpty() ? Map.of()
            : competitorMapper.selectByIds(sourceCompetitorIds).stream()
                .collect(Collectors.toMap(TCompetitor::getId, c -> c, (a, b) -> a));
        List<Long> sourceStageIds = sourceById.values().stream()
            .map(TCompetitor::getStageId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (!sourceStageIds.isEmpty()) {
            List<TMatch> sourceMatches = matchMapper.selectList(Wrappers.<TMatch>lambdaQuery()
                .in(TMatch::getStageId, sourceStageIds));
            List<Long> sourceMatchIds = sourceMatches.stream().map(TMatch::getId).toList();
            if (!sourceMatchIds.isEmpty()) {
                Map<Long, String> matchNameById = sourceMatches.stream()
                    .collect(Collectors.toMap(TMatch::getId, TMatch::getName));
                participantMapper.selectList(Wrappers.<TMatchParticipant>lambdaQuery()
                        .in(TMatchParticipant::getMatchId, sourceMatchIds)
                        .isNotNull(TMatchParticipant::getCompetitorId))
                    .forEach(p -> sourceMatch.putIfAbsent(p.getCompetitorId(),
                        matchNameById.get(p.getMatchId())));
            }
        }

        // 座位直接来自中间层:slot 即座位号,空位(BYE/PENDING)留空但照样占号,后面的人不前移。
        // 座位数优先取赛段计划规模,否则就按中间层的行数(中间层已经把 1..N 每个座位都铺了一行)
        int totalSlots = stage.getTeamCountStart() != null && stage.getTeamCountStart() > 0
            ? stage.getTeamCountStart().intValue()
            : entries.size();
        totalSlots = Math.max(1, totalSlots);
        PreBracketVo.PreSeed[] seedArr = new PreBracketVo.PreSeed[totalSlots];
        for (TStageRosterEntry e : filled) {
            if (e.getSlot() == null || e.getSlot() < 1 || e.getSlot() > totalSlots) {
                continue; // 超出计划规模的候选不进预排
            }
            PreBracketVo.PreSeed s;
            if ("GUEST".equals(e.getRefType())) {
                s = new PreBracketVo.PreSeed();
                s.setName(e.getGuestName());
            } else {
                TCompetitor src = sourceById.get(e.getSourceCompetitorId());
                s = src == null ? new PreBracketVo.PreSeed()
                    : toPreSeed(src, null, sourceMatch.get(src.getId()));
                s.setSourceCompetitorId(e.getSourceCompetitorId());
            }
            s.setSeedRank(e.getSlot());
            seedArr[(int) (e.getSlot() - 1)] = s;
        }
        List<PreBracketVo.PreSeed> seeds = new ArrayList<>();
        for (PreBracketVo.PreSeed s : seedArr) {
            if (s != null) {
                seeds.add(s);
            }
        }
    vo.setSeededCompetitors(seeds);
        vo.setStatus("PREVIEW");

        // 擂台赛段没有对阵树:只返回"谁进来了"的座位名单,不生成预排配对
        if (targetArena) {
            return vo;
        }

        List<PreBracketVo.PrePair> pairList = new ArrayList<>();
        // 空位显示"待定"还是"轮空":来源赛段没打完才是待定,来源全部结算后空位就是轮空
        boolean sourcesReady = rosterService.isRosterReady(stage.getId());
        // 配对模式:与生成对阵同一口径(显式配置优先;种子来自名次则默认种子摆位)
        RuleConfigHolder stageRc = RuleConfigParser.parse(stage.getRuleConfig());
        String configuredPairing = stageRc != null && stageRc.getKnockout() != null
            ? stageRc.getKnockout().getPairingMode() : null;
        // 配对方式只看本赛段配置(未配置 = 顺序相邻),预排不对配对方式做任何推断
        String pairingMode = PairingModeResolver.resolve(configuredPairing);
        if ("SEED".equalsIgnoreCase(pairingMode)) {
            int bracketSize = Math.max(2, nextPowerOfTwo(seedArr.length));
            int[] layout = KnockoutGenerator.seedLayout(bracketSize);
            int pairCount = Math.max(1, bracketSize / 2);
            int half = (int) Math.ceil(pairCount / 2.0);
            for (int i = 0; i < pairCount; i++) {
                PreBracketVo.PrePair p = new PreBracketVo.PrePair();
                p.setPosition(i + 1);
                p.setZone(i < half ? "LEFT" : "RIGHT");
                // SEED 标准种子摆位(与 KnockoutGenerator.SEED_LAYOUT 一致):第 i 场 = layout[2i] vs layout[2i+1]
                int leftPos = layout[2 * i];
                int rightPos = layout[2 * i + 1];
                p.setLeft(leftPos <= seedArr.length ? seedArr[leftPos - 1] : null);
                p.setRight(rightPos <= seedArr.length ? seedArr[rightPos - 1] : null);
                p.setLeftStatus(sideStatus(p.getLeft(), sourcesReady));
                p.setRightStatus(sideStatus(p.getRight(), sourcesReady));
                pairList.add(p);
            }
        } else {
            int pairs = Math.max(1, (int) Math.ceil(seedArr.length / 2.0));
            int half = (int) Math.ceil(pairs / 2.0);
            for (int i = 0; i < pairs; i++) {
                PreBracketVo.PrePair p = new PreBracketVo.PrePair();
                p.setPosition(i + 1);
                p.setZone(i < half ? "LEFT" : "RIGHT");
                int leftPos = i * 2 + 1;
                int rightPos = i * 2 + 2;
                p.setLeft(leftPos <= seedArr.length ? seedArr[leftPos - 1] : null);
                p.setRight(rightPos <= seedArr.length ? seedArr[rightPos - 1] : null);
                p.setLeftStatus(sideStatus(p.getLeft(), sourcesReady));
                p.setRightStatus(sideStatus(p.getRight(), sourcesReady));
                pairList.add(p);
            }
        }
        vo.setPairs(pairList);
        return vo;
    }

    /**
     * 填充导播台赛段列表的展示字段(上一赛段 + 是否等待中间态确认晋级)。
     *
     * <p>导播台首页每次刷新都会调,所以整页只跑 3 条 SQL:一次取赛事全部赛段、
     * 一次取相关赛段的名单来源组、一次取相关赛段的参赛方统计,其余在内存里算。</p>
     */
    @Override
    public void fillAwaitingAdvancement(List<TStageVo> stages) {
        if (stages == null || stages.isEmpty()) {
            return;
        }
        Set<Long> tournamentIds = stages.stream()
            .map(TStageVo::getTournamentId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (tournamentIds.isEmpty()) {
            return;
        }
        List<TStage> allStages = baseMapper.selectList(Wrappers.<TStage>lambdaQuery()
            .in(TStage::getTournamentId, tournamentIds));
        Map<Long, TStage> stageById = allStages.stream()
            .collect(Collectors.toMap(TStage::getId, s -> s, (a, b) -> a));
        // 前驱按 next 链一次性推导(prev 仅展示:导播台显示「上一赛段」)
        Map<Long, Long> prevByStage = new HashMap<>();
        for (Long tid : tournamentIds) {
            prevByStage.putAll(stageChain.prevIdsFromChain(tid));
        }
        // 依赖按名单来源组(边)解析:开赛/待确认的依据是"来源都结束了",不是"链上前一段结束了"
        Map<Long, List<TStageRosterGroupBo>> groupsByStage = new HashMap<>();
        Map<Long, List<Long>> sourcesByStage = new HashMap<>();
        Map<Long, List<TStageRosterVo>> rosters = rosterService.listByTargets(
            stages.stream().map(TStageVo::getId).filter(Objects::nonNull).toList());
        rosters.forEach((targetId, list) -> {
            List<TStageRosterGroupBo> groups = list.isEmpty() ? List.of() : list.get(0).getGroups();
            groupsByStage.put(targetId, groups);
            sourcesByStage.put(targetId, groups.stream()
                .map(TStageRosterGroupBo::getSourceStageId)
                .filter(Objects::nonNull).distinct().toList());
        });
        // 参赛方统计:一次取回相关赛段的行,在内存里数
        Set<Long> involved = new HashSet<>();
        for (TStageVo s : stages) {
            if (s.getId() != null) {
                involved.add(s.getId());
            }
            involved.addAll(sourcesByStage.getOrDefault(s.getId(), List.of()));
        }
        // stageId -> [已接收晋级者数, 晋级数, 待定数]
        Map<Long, long[]> stats = new HashMap<>();
        if (!involved.isEmpty()) {
            List<TCompetitor> rows = competitorMapper.selectList(Wrappers.<TCompetitor>lambdaQuery()
                .in(TCompetitor::getStageId, involved)
                .select(TCompetitor::getStageId, TCompetitor::getOutcomeStatus,
                    TCompetitor::getSourceCompetitorId));
            for (TCompetitor c : rows) {
                long[] st = stats.computeIfAbsent(c.getStageId(), k -> new long[3]);
                if (c.getSourceCompetitorId() != null) {
                    st[0]++;
                }
                if (OutcomeStatusEnum.ADVANCE.getCode().equals(c.getOutcomeStatus())) {
                    st[1]++;
                } else if (OutcomeStatusEnum.PENDING.getCode().equals(c.getOutcomeStatus())) {
                    st[2]++;
                }
            }
        }
        for (TStageVo stage : stages) {
            stage.setPrevStageId(prevByStage.get(stage.getId()));
            List<Long> sources = sourcesByStage.getOrDefault(stage.getId(), List.of());
            if (sources.isEmpty()) {
                continue;   // 入口赛段(签到/外部来源):没有依赖,谈不上等确认
            }
            boolean allSettled = sources.stream().allMatch(id -> {
                TStage src = stageById.get(id);
                return src != null && StageConstants.STAGE_SETTLED.equals(src.getStatus());
            });
            if (!allSettled) {
                continue;
            }
            if (stats.getOrDefault(stage.getId(), new long[3])[0] > 0) {
                continue;   // 已经接收过晋级者 = 名单已确认
            }
            long candidates = sources.stream().mapToLong(id -> {
                long[] s = stats.getOrDefault(id, new long[3]);
                return s[1] + s[2];
            }).sum();
            stage.setAwaitingAdvancement(candidates > 0);
        }
    }

    /**
     * 预排空位状态:该位置有胜者=WINNER;对应上一场次存在且有人=待定(TBD);否则轮空(BYE)。
     */
    private String sideStatus(PreBracketVo.PreSeed seed, boolean sourcesReady) {
        if (seed != null) {
            return "WINNER";
        }
        // 空座位只有两种可能,必须和中间层(t_stage_roster_entry)同一口径:
        //  · 来源赛段还没打完 → 待定(TBD):这个位子还会有人来;
        //  · 来源全部结算后还是空的 → 轮空(BYE)。
        // 之前按"对应场次有没有人"细分,结果同一场比赛在中间态显示轮空、在大屏显示待定。
        return sourcesReady ? "BYE" : "TBD";
    }

    private static int nextPowerOfTwo(int v) {
        int p = 1;
        while (p < v) {
            p <<= 1;
        }
        return p;
    }

    /** 本赛段赛制画像(赛制语义判断统一走这里,避免编排代码出现 mode 字符串比较)。 */
    private StageModeEnum stageCap(TStage stage) {
        return StageModeEnum.require(stage.getStageMode());
    }

    private PreBracketVo.PreSeed toPreSeed(TCompetitor c, Long seedRank, String sourceMatchName) {
        PreBracketVo.PreSeed s = new PreBracketVo.PreSeed();
        s.setCompetitorId(c.getId());
        s.setName(c.getName());
        s.setSeedRank(seedRank);
        s.setSourceCompetitorId(c.getSourceCompetitorId());
        s.setSourceMatchName(sourceMatchName);
        return s;
    }

}

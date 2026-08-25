package com.dance.street.game.service.impl;

import cn.hutool.core.lang.UUID;
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
import org.springframework.transaction.annotation.Transactional;
import org.dromara.common.core.exception.ServiceException;
import com.dance.street.game.domain.bo.TTournamentBo;
import com.dance.street.game.domain.bo.TTournamentTemplateBo;
import com.dance.street.game.domain.bo.TRefereeBo;
import com.dance.street.game.domain.bo.StageRefereeBo;
import com.dance.street.game.domain.bo.TStageBo;
import com.dance.street.game.domain.bo.TVisSceneBo;
import com.dance.street.game.domain.bo.TVisWidgetBo;
import com.dance.street.game.domain.vo.TTournamentVo;
import com.dance.street.game.domain.vo.TStageVo;
import com.dance.street.game.domain.vo.TVisSceneVo;
import com.dance.street.game.domain.TTournament;
import com.dance.street.game.domain.TPlayer;
import com.dance.street.game.domain.TReferee;
import com.dance.street.game.domain.TRefereeStage;
import com.dance.street.game.domain.TVisScene;
import com.dance.street.game.domain.TVisWidget;
import com.dance.street.game.mapper.TPlayerMapper;
import com.dance.street.game.mapper.TRefereeMapper;
import com.dance.street.game.mapper.TRefereeStageMapper;
import com.dance.street.game.mapper.TVisSceneMapper;
import com.dance.street.game.mapper.TVisWidgetMapper;
import com.dance.street.game.mapper.TTournamentMapper;
import com.dance.street.game.service.ITTournamentService;
import com.dance.street.game.service.ITStageService;
import com.dance.street.game.service.ITVisSceneService;
import com.dance.street.game.service.ITVisWidgetService;
import com.dance.street.game.service.ITRefereeService;
import com.dance.street.game.service.ITRefereeStageService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collection;

/**
 * 赛事主Service业务层处理
 *
 * @author duane
 * @date 2026-01-06
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TTournamentServiceImpl implements ITTournamentService {

    private final TTournamentMapper baseMapper;
    private final TPlayerMapper playerMapper;
    private final TRefereeMapper refereeMapper;
    private final TRefereeStageMapper refereeStageMapper;
    private final TVisSceneMapper visSceneMapper;
    private final TVisWidgetMapper visWidgetMapper;
    private final ITStageService stageService;
    private final ITVisSceneService visSceneService;
    private final ITVisWidgetService visWidgetService;
    private final ITRefereeService refereeService;
    private final ITRefereeStageService refereeStageService;

    /** 赛事模版:模版编码 → 赛段定义(海选 + 淘汰赛链) */
    private static final Map<String, List<StageDef>> TEMPLATES = buildTemplates();

    /** 32 模板(海选→32强→16强→8强→半决赛→决赛)对战树尺寸:按赛段起始人数 {宽, 高} */
    private static final Map<Long, long[]> BRACKET_SIZES_AUDITION_32 = Map.of(
        32L, new long[]{1848L, 1022L},
        16L, new long[]{1463L, 945L},
        8L, new long[]{1095L, 812L},
        4L, new long[]{715L, 574L},
        2L, new long[]{458L, 212L}
    );

    /** 16 模板(海选→16强→8强→半决赛→决赛)对战树尺寸:按赛段起始人数 {宽, 高} */
    private static final Map<Long, long[]> BRACKET_SIZES_AUDITION_16 = Map.of(
        16L, new long[]{1843L, 1025L},
        8L, new long[]{1414L, 890L},
        4L, new long[]{944L, 598L},
        2L, new long[]{446L, 204L}
    );

    /** 画布尺寸:与赛事创建时的 logicalWidth/logicalHeight 保持一致 */
    private static final long CANVAS_W = 1920L;
    private static final long CANVAS_H = 1080L;

    private static Map<String, List<StageDef>> buildTemplates() {
        Map<String, List<StageDef>> m = new LinkedHashMap<>();
        // 海选 → 32强 → 16强 → 8强 → 半决赛 → 决赛
        m.put("AUDITION_32", List.of(
            new StageDef("海选", "AUDITION", 40L, 32L),
            new StageDef("32强", "KNOCKOUT", 32L, 16L),
            new StageDef("16强", "KNOCKOUT", 16L, 8L),
            new StageDef("8强", "KNOCKOUT", 8L, 4L),
            new StageDef("半决赛", "KNOCKOUT", 4L, 2L),
            new StageDef("决赛", "KNOCKOUT", 2L, 1L)
        ));
        // 海选 → 16强 → 8强 → 半决赛 → 决赛
        m.put("AUDITION_16", List.of(
            new StageDef("海选", "AUDITION", 20L, 16L),
            new StageDef("16强", "KNOCKOUT", 16L, 8L),
            new StageDef("8强", "KNOCKOUT", 8L, 4L),
            new StageDef("半决赛", "KNOCKOUT", 4L, 2L),
            new StageDef("决赛", "KNOCKOUT", 2L, 1L)
        ));
        // 海选 → 32强 → 16强 → 擂台赛(淘汰至 8 人后进擂台)
        m.put("AUDITION_ARENA", List.of(
            new StageDef("海选", "AUDITION", 40L, 32L),
            new StageDef("32强", "KNOCKOUT", 32L, 16L),
            new StageDef("16强", "KNOCKOUT", 16L, 8L),
            new StageDef("擂台赛", "ARENA", 8L, 1L)
        ));
        return m;
    }

    /** 赛段定义:名称 / 赛制 / 起始人数 / 晋级人数 */
    private record StageDef(String name, String mode, Long start, Long end) {
    }

    /**
     * 对战树尺寸:按模板编码 + 赛段起始人数取 {宽, 高}。
     * 32/16/擂台模板均采用居中嵌套排版;擂台模板复用 32 模板的尺寸表。
     * 尺寸参考手工调优后的线上排版;未命中(如人数不在表内)时给默认尺寸兜底。
     *
     * @param templateCode   模板编码(AUDITION_32 / AUDITION_16 / AUDITION_ARENA)
     * @param teamCountStart 赛段起始人数
     * @return {宽, 高};未知模板编码返回 null
     */
    private static long[] bracketSize(String templateCode, Long teamCountStart) {
        Map<Long, long[]> sizes = switch (templateCode) {
            case "AUDITION_32" -> BRACKET_SIZES_AUDITION_32;
            case "AUDITION_16" -> BRACKET_SIZES_AUDITION_16;
            // 擂台模板淘汰链为 32强→16强→8强(擂台赛段),与 32 模板同尺寸
            case "AUDITION_ARENA" -> BRACKET_SIZES_AUDITION_32;
            default -> null;
        };
        if (sizes == null) {
            return null;
        }
        long[] wh = teamCountStart == null ? null : sizes.get(teamCountStart);
        return wh != null ? wh : new long[]{1200L, 700L};
    }

    /**
     * 查询赛事主
     *
     * @param id 主键
     * @return 赛事主
     */
    @Override
    public TTournamentVo queryById(Long id){
        return baseMapper.selectVoById(id);
    }

    /**
     * 分页查询赛事主列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 赛事主分页列表
     */
    @Override
    public TableDataInfo<TTournamentVo> queryPageList(TTournamentBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TTournament> lqw = buildQueryWrapper(bo);
        Page<TTournamentVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        return TableDataInfo.build(result);
    }

    /**
     * 查询符合条件的赛事主列表
     *
     * @param bo 查询条件
     * @return 赛事主列表
     */
    @Override
    public List<TTournamentVo> queryList(TTournamentBo bo) {
        LambdaQueryWrapper<TTournament> lqw = buildQueryWrapper(bo);
        return baseMapper.selectVoList(lqw);
    }

    private LambdaQueryWrapper<TTournament> buildQueryWrapper(TTournamentBo bo) {
        Map<String, Object> params = bo.getParams();
        LambdaQueryWrapper<TTournament> lqw = Wrappers.lambdaQuery();
        lqw.orderByAsc(TTournament::getId);
        lqw.like(StringUtils.isNotBlank(bo.getName()), TTournament::getName, bo.getName());
        lqw.eq(bo.getStatus() != null, TTournament::getStatus, bo.getStatus());
        lqw.eq(bo.getLogicalWidth() != null, TTournament::getLogicalWidth, bo.getLogicalWidth());
        lqw.eq(bo.getLogicalHeight() != null, TTournament::getLogicalHeight, bo.getLogicalHeight());
        lqw.eq(StringUtils.isNotBlank(bo.getThemeConfig()), TTournament::getThemeConfig, bo.getThemeConfig());
        return lqw;
    }

    /**
     * 新增赛事主
     *
     * @param bo 赛事主
     * @return 新增后的赛事主
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TTournamentVo insertByBo(TTournamentBo bo) {
        TTournament add = MapstructUtils.convert(bo, TTournament.class);
        // 手机导播台登录凭证:创建赛事时自动生成,泄露后可调用 regenerateAuthKey 重置
        add.setAuthKey(UUID.randomUUID().toString(true));
        validEntityBeforeSave(add);
        baseMapper.insert(add);
        bo.setId(add.getId());
        // 按表单选择自动创建裁判(普通创建无赛段,不绑定)
        createRefereesIfNeeded(add.getId(), bo.getRefereeCount(), null);
        return MapstructUtils.convert(add, TTournamentVo.class);
    }

    /**
     * 按模版创建赛事:赛事 + 赛段链(自动关联) + 场景(主视觉/对战) + 对战树 widget 关联。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TTournamentVo createByTemplate(TTournamentTemplateBo bo) {
        List<StageDef> defs = TEMPLATES.get(bo.getTemplateCode());
        if (defs == null || defs.isEmpty()) {
            throw new ServiceException("未知的赛事模版: " + bo.getTemplateCode());
        }
        // 1. 创建赛事
        TTournamentBo tb = new TTournamentBo();
        tb.setName(bo.getName());
        tb.setStatus(0L);
        tb.setLogicalWidth(1920L);
        tb.setLogicalHeight(1080L);
        tb.setThemeConfig("{\"bgColor\":\"#000000\",\"fontFamily\":\"Roboto\"}");
        tb.setRemark(bo.getRemark());
        TTournamentVo tournament = insertByBo(tb);
        Long tid = tournament.getId();

        // 2. 创建赛段链(首个无 prev,后续依次挂 prev;insert 自动回填相邻 next/prev)
        List<TStageVo> stages = new ArrayList<>();
        Long prevId = null;
        for (int i = 0; i < defs.size(); i++) {
            StageDef d = defs.get(i);
            TStageBo sb = new TStageBo();
            sb.setTournamentId(tid);
            sb.setName(d.name());
            sb.setStageMode(d.mode());
            sb.setTeamCountStart(d.start());
            sb.setTeamCountEnd(d.end());
            sb.setStatus("DRAFT");
            sb.setVisualColIndex((long) i);
            sb.setRuleConfig(buildRuleConfig(d));
            if (prevId != null) {
                sb.setPrevStageId(prevId);
            }
            TStageVo created = stageService.insertByBo(sb);
            stages.add(created);
            prevId = created.getId();
        }

        // 3. 场景:主视觉 + 对战
        TVisSceneVo mainScene = createScene(tid, "主视觉", 1L);
        TVisSceneVo bracketScene = createScene(tid, "对战", 2L);

        // 4. 对战场景:每个淘汰赛赛段一个对战树 widget,统一「大框套小框」居中嵌套排版——
        //    外层(人数最多)最宽,内层逐级缩小,所有 widget 中心对齐画布中心 (960,540),
        //    外层左右两列卡片恰好露在内层两侧,形成树形层叠效果。
        //    尺寸取自手工调优后的线上排版(参考赛事 2087560015994183682 / 2087560627817308162)。
        int idx = 0;
        for (TStageVo stage : stages) {
            if (!"KNOCKOUT".equals(stage.getStageMode())) {
                continue;
            }
            long[] wh = bracketSize(bo.getTemplateCode(), stage.getTeamCountStart());
            long x = (CANVAS_W - wh[0]) / 2;
            long y = (CANVAS_H - wh[1]) / 2;
            TVisWidgetBo wb = new TVisWidgetBo();
            wb.setTournamentId(tid);
            wb.setSceneId(bracketScene.getId());
            wb.setName("对战树-" + stage.getName());
            wb.setType("BRACKET");
            wb.setLayoutConfig("{}");
            wb.setDataConfig("{\"stageId\":\"" + stage.getId() + "\",\"tournamentId\":\"" + tid + "\"}");
            wb.setRenderConfig("{}");
            wb.setX(x);
            wb.setY(y);
            wb.setW(wh[0]);
            wb.setH(wh[1]);
            wb.setZIndex((long) (idx + 1));
            wb.setVisible(1L);
            wb.setLocked(0L);
            visWidgetService.insertByBo(wb);
            idx++;
        }

        // 擂台模板专用:定位擂台赛段(8 强展示 + 擂台场景均绑定它)
        TStageVo arenaStage = stages.stream()
            .filter(s -> "ARENA".equals(s.getStageMode()))
            .findFirst()
            .orElse(null);

        // 4.5 擂台模板:对战场景补「对战树-8强」控件(绑定擂台赛段)。
        //     16 强结算晋级出的 8 人写入擂台赛段后,由对战树组件按标准 8 强种子摆位展示,
        //     与 32强/16强 对战树构成标准三层对战树(32 → 16 → 8)。
        if (arenaStage != null) {
            long[] wh = BRACKET_SIZES_AUDITION_32.getOrDefault(arenaStage.getTeamCountStart(),
                new long[]{1095L, 812L});
            TVisWidgetBo arenaBracket = new TVisWidgetBo();
            arenaBracket.setTournamentId(tid);
            arenaBracket.setSceneId(bracketScene.getId());
            arenaBracket.setName("对战树-8强");
            arenaBracket.setType("BRACKET");
            arenaBracket.setLayoutConfig("{}");
            arenaBracket.setDataConfig("{\"stageId\":\"" + arenaStage.getId()
                + "\",\"tournamentId\":\"" + tid + "\"}");
            arenaBracket.setRenderConfig("{}");
            arenaBracket.setX((CANVAS_W - wh[0]) / 2);
            arenaBracket.setY((CANVAS_H - wh[1]) / 2);
            arenaBracket.setW(wh[0]);
            arenaBracket.setH(wh[1]);
            arenaBracket.setZIndex((long) (idx + 1));
            arenaBracket.setVisible(1L);
            arenaBracket.setLocked(0L);
            visWidgetService.insertByBo(arenaBracket);
            idx++;
        }

        // 4.6 擂台模板:第三个场景「擂台」,全屏放置擂台积分组件(ARENA_SCORE)。
        //     绑定擂台赛段后,16 强结算晋级出的 8 强名单(轮转队列/积分/当前对决)
        //     由 ArenaOverview 实时提供并展示在大屏,避免晋级后看不到是谁进了擂台赛。
        if (arenaStage != null) {
            TVisSceneVo arenaScene = createScene(tid, "擂台", 3L);
            TVisWidgetBo arenaWidget = new TVisWidgetBo();
            arenaWidget.setTournamentId(tid);
            arenaWidget.setSceneId(arenaScene.getId());
            arenaWidget.setName("擂台赛");
            arenaWidget.setType("ARENA_SCORE");
            arenaWidget.setLayoutConfig("{}");
            arenaWidget.setDataConfig("{\"stageId\":\"" + arenaStage.getId() + "\",\"tournamentId\":\"" + tid + "\"}");
            arenaWidget.setRenderConfig("{}");
            arenaWidget.setX(0L);
            arenaWidget.setY(0L);
            arenaWidget.setW(CANVAS_W);
            arenaWidget.setH(CANVAS_H);
            arenaWidget.setZIndex(1L);
            arenaWidget.setVisible(1L);
            arenaWidget.setLocked(0L);
            visWidgetService.insertByBo(arenaWidget);
        }

        // 5. 当前场次 widget:置于对战场景最上层,全屏显示(自动关联赛事当前进行中的赛段与场次)
        TVisWidgetBo currentMatchWidget = new TVisWidgetBo();
        currentMatchWidget.setTournamentId(tid);
        currentMatchWidget.setSceneId(bracketScene.getId());
        currentMatchWidget.setName("当前场次");
        currentMatchWidget.setType("MATCH_DETAIL");
        currentMatchWidget.setLayoutConfig("{}");
        currentMatchWidget.setDataConfig("{\"bgImage\":\"\",\"tournamentId\":\"" + tid + "\"}");
        currentMatchWidget.setRenderConfig("{}");
        currentMatchWidget.setX(0L);
        currentMatchWidget.setY(0L);
        currentMatchWidget.setW(CANVAS_W);
        currentMatchWidget.setH(CANVAS_H);
        // 对战树 zIndex 从 1 递增,取 idx+1 保证在所有图层之上
        currentMatchWidget.setZIndex((long) idx + 1);
        currentMatchWidget.setVisible(1L);
        currentMatchWidget.setLocked(0L);
        visWidgetService.insertByBo(currentMatchWidget);

        // 6. 按表单选择自动创建裁判,并绑定到所有赛段
        List<Long> refereeIds = createRefereesIfNeeded(tid, bo.getRefereeCount(),
            stages.stream().map(TStageVo::getId).toList());

        log.info("按模版[{}]创建赛事[{}]完成:{} 个赛段,{} 个对战树 widget,1 个当前场次 widget", bo.getTemplateCode(), tid,
            stages.size(), idx);
        if (refereeIds != null && !refereeIds.isEmpty()) {
            log.info("按模版[{}]创建赛事[{}]完成:自动创建 {} 位裁判并绑定到 {} 个赛段", bo.getTemplateCode(), tid,
                refereeIds.size(), stages.size());
        }
        return tournament;
    }

    /**
     * 按 refereeCount 自动创建裁判;stageIds 非空时把裁判绑定到这些赛段。
     *
     * @param tournamentId 赛事ID
     * @param refereeCount 裁判数量(空或≤0 不创建)
     * @param stageIds     需要绑定的赛段ID列表(可为 null)
     * @return 创建的裁判ID列表
     */
    private List<Long> createRefereesIfNeeded(Long tournamentId, Integer refereeCount, List<Long> stageIds) {
        if (refereeCount == null || refereeCount <= 0) {
            return List.of();
        }
        if (refereeCount > 100) {
            throw new ServiceException("裁判数量最多 100 人");
        }
        List<Long> refereeIds = new ArrayList<>();
        for (int i = 1; i <= refereeCount; i++) {
            TRefereeBo rb = new TRefereeBo();
            rb.setTournamentId(tournamentId);
            rb.setName("裁判" + i);
            refereeService.insertByBo(rb);
            refereeIds.add(rb.getId());
        }
        // 绑定到所有赛段(全量替换,新赛事赛段无旧关联)
        if (stageIds != null && !stageIds.isEmpty() && !refereeIds.isEmpty()) {
            for (Long stageId : stageIds) {
                StageRefereeBo sb = new StageRefereeBo();
                sb.setStageId(stageId);
                sb.setTournamentId(tournamentId);
                sb.setRefereeIds(refereeIds);
                refereeStageService.assignReferees(sb);
            }
        }
        return refereeIds;
    }

    private TVisSceneVo createScene(Long tournamentId, String name, Long sortOrder) {
        TVisSceneBo bo = new TVisSceneBo();
        bo.setTournamentId(tournamentId);
        bo.setName(name);
        bo.setDesignWidth(1920L);
        bo.setDesignHeight(1080L);
        bo.setFormat("DEFAULT");
        bo.setBgColor("#000000");
        bo.setSortOrder(sortOrder);
        return visSceneService.insertByBo(bo);
    }

    private String buildRuleConfig(StageDef d) {
        if ("AUDITION".equals(d.mode())) {
            return "{\"mode\":\"AUDITION\",\"format\":\"BO1\",\"circles\":1,"
                + "\"scoring\":{\"type\":\"TOTAL_SCORE\",\"matchMode\":\"VOTING\","
                + "\"aggregateRule\":\"SUM\",\"refereeAggregateRule\":\"SUM\"},"
                + "\"transition\":{},"
                + "\"advanceCount\":" + d.end() + "}";
        }
        if ("ARENA".equals(d.mode())) {
            // 擂台赛:不生成对阵,开始后由导播台逐场创建对决;
            // 配置与前端 ArenaStageConfig 默认值对齐(后端当前仅读胜负结算,其余为展示/预留)
            return "{\"mode\":\"ARENA\",\"format\":\"BO1\","
                + "\"challengerCount\":" + d.start() + ","
                + "\"maxChallenges\":3,\"challengeOrder\":\"RANDOM\","
                + "\"winStreakBonus\":10,\"defenseBonus\":5,"
                + "\"allowDefenderRest\":true,\"allowRechallenge\":false,\"timeoutReplacement\":true,"
                + "\"transition\":{}}";
        }
        return "{\"mode\":\"KNOCKOUT\",\"format\":\"BO1\","
            + "\"scoring\":{\"type\":\"WIN_LOSS_DRAW\",\"matchMode\":\"STANDARD\"},"
            + "\"knockout\":{\"teamsCount\":" + d.start()
            + ",\"pairingMode\":\"SEED\",\"singleRound\":true,\"advanceCount\":" + d.end() + "},"
            + "\"transition\":{}}";
    }

    /**
     * 修改赛事主
     *
     * @param bo 赛事主
     * @return 修改后的赛事主
     */
    @Override
    public TTournamentVo updateByBo(TTournamentBo bo) {
        TTournament update = MapstructUtils.convert(bo, TTournament.class);
        validEntityBeforeSave(update);
        baseMapper.updateById(update);
        return MapstructUtils.convert(update, TTournamentVo.class);
    }

    /**
     * 保存前的数据校验
     */
    private void validEntityBeforeSave(TTournament entity){
        //TODO 做一些数据校验,如唯一约束
    }

    /**
     * 校验并批量删除赛事主信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        if(isValid){
            //TODO 做一些业务上的校验,判断是否需要校验
        }
        List<Long> tids = ids.stream().map(Long::valueOf).toList();
        // 级联删除:赛段及其关联(场次/参赛方/打分/裁判关联)→ 可视化场景与控件 → 裁判 → 选手
        for (Long tid : tids) {
            TStageBo q = new TStageBo();
            q.setTournamentId(tid);
            List<TStageVo> stages = stageService.queryList(q);
            if (!stages.isEmpty()) {
                stageService.deleteWithValidByIds(
                    stages.stream().map(TStageVo::getId).toList(), false);
            }
        }
        visWidgetMapper.delete(Wrappers.<TVisWidget>lambdaQuery()
            .in(TVisWidget::getTournamentId, tids));
        visSceneMapper.delete(Wrappers.<TVisScene>lambdaQuery()
            .in(TVisScene::getTournamentId, tids));
        refereeStageMapper.delete(Wrappers.<TRefereeStage>lambdaQuery()
            .in(TRefereeStage::getTournamentId, tids));
        refereeMapper.delete(Wrappers.<TReferee>lambdaQuery()
            .in(TReferee::getTournamentId, tids));
        playerMapper.delete(Wrappers.<TPlayer>lambdaQuery()
            .in(TPlayer::getTournamentId, tids));
        return baseMapper.deleteByIds(ids) > 0;
    }

    /**
     * 获取赛事登录凭证
     *
     * @param id 主键
     * @return 登录凭证
     */
    @Override
    public String getAuthKeyById(Long id) {
        TTournament tournament = baseMapper.selectById(id);
        return tournament != null ? tournament.getAuthKey() : null;
    }

    /**
     * 生成新的赛事登录凭证
     *
     * @param id 主键
     * @return 新的登录凭证
     */
    @Override
    public String regenerateAuthKey(Long id) {
        TTournament tournament = baseMapper.selectById(id);
        if (tournament == null) {
            return null;
        }
        String newAuthKey = UUID.randomUUID().toString(true);
        tournament.setAuthKey(newAuthKey);
        baseMapper.updateById(tournament);
        return newAuthKey;
    }

    /**
     * 根据赛事登录凭证查找赛事
     *
     * @param authKey 登录凭证
     * @return 赛事信息;凭证无效返回 null
     */
    @Override
    public TTournamentVo findByAuthKey(String authKey) {
        TTournament tournament = baseMapper.selectOne(
            Wrappers.<TTournament>lambdaQuery().eq(TTournament::getAuthKey, authKey));
        if (tournament == null) {
            return null;
        }
        return baseMapper.selectVoById(tournament.getId());
    }
}

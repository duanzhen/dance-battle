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
    private final ITStageService stageService;
    private final ITVisSceneService visSceneService;
    private final ITVisWidgetService visWidgetService;
    private final ITRefereeService refereeService;
    private final ITRefereeStageService refereeStageService;

    /** 赛事模版:模版编码 → 赛段定义(海选 + 淘汰赛链) */
    private static final Map<String, List<StageDef>> TEMPLATES = buildTemplates();

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

        // 4. 对战场景:每个淘汰赛赛段一个对战树 widget,按 2 列网格排布——
        //    各 widget 坐标互不重叠(32强/16强等不再共用中心点互相遮挡),
        //    网格行数随淘汰赛赛段数量自动扩展,任意模板都适用
        int cols = 2;
        int gridGap = 25;
        int knockoutCount = (int) stages.stream().filter(s -> "KNOCKOUT".equals(s.getStageMode())).count();
        int gridRows = Math.max(1, (knockoutCount + cols - 1) / cols);
        long gridW = (1920L - (long) gridGap * (cols + 1)) / cols;
        long gridH = (1080L - (long) gridGap * (gridRows + 1)) / gridRows;
        int idx = 0;
        for (TStageVo stage : stages) {
            if (!"KNOCKOUT".equals(stage.getStageMode())) {
                continue;
            }
            int col = idx % cols;
            int row = idx / cols;
            long x = gridGap + (long) col * (gridW + gridGap);
            long y = gridGap + (long) row * (gridH + gridGap);
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
            wb.setW(gridW);
            wb.setH(gridH);
            wb.setZIndex((long) (idx + 1));
            wb.setVisible(1L);
            wb.setLocked(0L);
            visWidgetService.insertByBo(wb);
            idx++;
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
        currentMatchWidget.setW(1920L);
        currentMatchWidget.setH(1080L);
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
                + "\"transition\":{\"mode\":\"AUTO\",\"reshuffle\":false},"
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
                + "\"transition\":{\"mode\":\"AUTO\",\"reshuffle\":false}}";
        }
        return "{\"mode\":\"KNOCKOUT\",\"format\":\"BO1\","
            + "\"scoring\":{\"type\":\"WIN_LOSS_DRAW\",\"matchMode\":\"STANDARD\"},"
            + "\"knockout\":{\"teamsCount\":" + d.start()
            + ",\"pairingMode\":\"SEED\",\"singleRound\":true,\"advanceCount\":" + d.end() + "},"
            + "\"transition\":{\"mode\":\"AUTO\",\"reshuffle\":false}}";
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
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        if(isValid){
            //TODO 做一些业务上的校验,判断是否需要校验
        }
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

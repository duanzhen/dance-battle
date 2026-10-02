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
import com.dance.street.game.domain.bo.TVisWidgetBo;
import com.dance.street.game.domain.vo.TVisWidgetVo;
import com.dance.street.game.domain.TVisWidget;
import com.dance.street.game.domain.TVisScene;
import com.dance.street.game.mapper.TVisWidgetMapper;
import com.dance.street.game.service.ITVisWidgetService;
import org.dromara.common.sse.utils.TournamentSseMessageUtils;
import org.dromara.common.core.exception.ServiceException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import tools.jackson.databind.ObjectMapper;
import com.dance.street.game.engine.common.SnowflakeJson;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;

import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 场景控件元素Service业务层处理
 *
 * @author duane
 * @date 2026-01-06
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TVisWidgetServiceImpl implements ITVisWidgetService {

    private final TVisWidgetMapper baseMapper;
    private final com.dance.street.game.mapper.TVisSceneMapper sceneMapper;
    private final ObjectProvider<RedissonClient> redissonClientProvider;

    /** 无 Redis(单机模式)时的 JVM 本地场景锁 */
    private static final Map<String, ReentrantLock> JVM_SCENE_LOCKS = new ConcurrentHashMap<>();

    /**
     * 查询场景控件元素
     *
     * @param id 主键
     * @return 场景控件元素
     */
    @Override
    public TVisWidgetVo queryById(Long id){
        return baseMapper.selectVoById(id);
    }

    /** 倒计时状态允许回写的键:组件配置是 JSON,但只开放这两个计时键,避免大屏能改内容/布局 */
    private static final List<String> TIMER_STATE_KEYS = List.of("endAt", "remainMs");

    /**
     * 按 JSON 补丁合并进倒计时组件的 dataConfig(白名单键:endAt / remainMs)。
     *
     * <p>这里绕开 {@link #updateByBo} 的"已锁定控件禁止编辑"保护是有意为之:锁定是防误拖布局,
     * 而开始/暂停是现场操作,控件锁着也得能计时。</p>
     */
    @Override
    public void updateTimerState(Long widgetId, Map<String, Object> patch) {
        TVisWidget widget = baseMapper.selectById(widgetId);
        if (widget == null) {
            throw new ServiceException("组件不存在");
        }
        if (!"TIMER".equals(widget.getType())) {
            throw new ServiceException("仅倒计时组件支持回写计时状态");
        }
        ObjectMapper mapper = SnowflakeJson.mapper();
        Map<String, Object> config = new LinkedHashMap<>();
        String raw = widget.getDataConfig();
        if (StringUtils.isNotBlank(raw)) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = mapper.readValue(raw, Map.class);
                if (parsed != null) {
                    config.putAll(parsed);
                }
            } catch (Exception e) {
                // 配置坏了不阻塞计时:按空配置重建,计划时长等字段由组件属性兜底
                log.warn("倒计时组件[{}]配置解析失败,按空配置重建: {}", widgetId, e.getMessage());
            }
        }
        if (patch != null) {
            for (String key : TIMER_STATE_KEYS) {
                if (patch.containsKey(key)) {
                    config.put(key, patch.get(key));
                }
            }
        }
        TVisWidget upd = new TVisWidget();
        upd.setId(widgetId);
        try {
            upd.setDataConfig(mapper.writeValueAsString(config));
        } catch (Exception e) {
            throw new ServiceException("倒计时状态序列化失败: {}", e.getMessage());
        }
        baseMapper.updateById(upd);

        // 管理端改的计时状态要同步到正在投射该场景的大屏(sceneUpdate → 大屏重载场景配置续跑)
        notifySceneUpdate(widget.getSceneId());
    }

    /**
     * 分页查询场景控件元素列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 场景控件元素分页列表
     */
    @Override
    public TableDataInfo<TVisWidgetVo> queryPageList(TVisWidgetBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<TVisWidget> lqw = buildQueryWrapper(bo);
        Page<TVisWidgetVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        return TableDataInfo.build(result);
    }

    /**
     * 查询符合条件的场景控件元素列表
     *
     * @param bo 查询条件
     * @return 场景控件元素列表
     */
    @Override
    public List<TVisWidgetVo> queryList(TVisWidgetBo bo) {
        LambdaQueryWrapper<TVisWidget> lqw = buildQueryWrapper(bo);
        return baseMapper.selectVoList(lqw);
    }

    private LambdaQueryWrapper<TVisWidget> buildQueryWrapper(TVisWidgetBo bo) {
        Map<String, Object> params = bo.getParams();
        LambdaQueryWrapper<TVisWidget> lqw = Wrappers.lambdaQuery();
        lqw.orderByAsc(TVisWidget::getId);
        lqw.eq(bo.getTournamentId() != null, TVisWidget::getTournamentId, bo.getTournamentId());
        lqw.eq(bo.getSceneId() != null, TVisWidget::getSceneId, bo.getSceneId());
        lqw.like(StringUtils.isNotBlank(bo.getName()), TVisWidget::getName, bo.getName());
        lqw.eq(StringUtils.isNotBlank(bo.getType()), TVisWidget::getType, bo.getType());
        lqw.eq(StringUtils.isNotBlank(bo.getLayoutConfig()), TVisWidget::getLayoutConfig, bo.getLayoutConfig());
        lqw.eq(StringUtils.isNotBlank(bo.getDataConfig()), TVisWidget::getDataConfig, bo.getDataConfig());
        lqw.eq(StringUtils.isNotBlank(bo.getRenderConfig()), TVisWidget::getRenderConfig, bo.getRenderConfig());
        return lqw;
    }

    /**
     * 新增场景控件元素
     *
     * @param bo 场景控件元素
     * @return 新增后的场景控件元素
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TVisWidgetVo insertByBo(TVisWidgetBo bo) {
        TVisWidget add = MapstructUtils.convert(bo, TVisWidget.class);
        validEntityBeforeSave(add);
        Long sceneId = add.getSceneId();
        withSceneLock(sceneId, () -> {
            // 图层层级以服务端为准:取当前场景最大 zIndex + 1,
            // 保证新控件永远在最上层且层级不重复(客户端按数量分配会在删除/重排后产生冲突)
            TVisWidget top = baseMapper.selectOne(Wrappers.<TVisWidget>lambdaQuery()
                .eq(TVisWidget::getSceneId, sceneId)
                .orderByDesc(TVisWidget::getZIndex)
                .last("LIMIT 1"));
            long nextZ = (top == null || top.getZIndex() == null) ? 1L : top.getZIndex() + 1L;
            add.setZIndex(nextZ);
            baseMapper.insert(add);
        });
        bo.setId(add.getId());

        // 通知所有显示该场景的屏幕更新
        notifySceneUpdate(sceneId);

        return MapstructUtils.convert(add, TVisWidgetVo.class);
    }

    /**
     * 修改场景控件元素
     *
     * @param bo 场景控件元素
     * @return 修改后的场景控件元素
     */
    @Override
    public TVisWidgetVo updateByBo(TVisWidgetBo bo) {
        TVisWidget update = MapstructUtils.convert(bo, TVisWidget.class);
        // 锁定保护:已锁定控件仅允许切换可见性(visible)或解除锁定(locked),禁止修改其他字段
        if (update.getId() != null) {
            TVisWidget existing = baseMapper.selectById(update.getId());
            if (existing != null && isLocked(existing)) {
                assertNotLockedEdit(existing, update);
            }
        }
        validEntityBeforeSave(update);
        baseMapper.updateById(update);

        // 通知所有显示该场景的屏幕更新
        notifySceneUpdate(update.getSceneId());

        // 前端按局部更新提交(仅变更字段),更新后回查完整数据返回,保证 VO 字段完整
        TVisWidget saved = baseMapper.selectById(update.getId());
        return MapstructUtils.convert(saved, TVisWidgetVo.class);
    }

    /**
     * 图层排序:上移/下移交换相邻控件的 zIndex(按场景加 Redisson 锁,原子)。
     * z 降序:idx 小 = z 大 = 上层;up → 与 idx-1 交换,down → 与 idx+1 交换。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void moveLayer(Long widgetId, String dir) {
        TVisWidget widget = baseMapper.selectById(widgetId);
        if (widget == null) {
            throw new ServiceException("控件不存在");
        }
        Long sceneId = widget.getSceneId();
        withSceneLock(sceneId, () -> {
            List<TVisWidget> widgets = baseMapper.selectList(Wrappers.<TVisWidget>lambdaQuery()
                .eq(TVisWidget::getSceneId, sceneId).orderByDesc(TVisWidget::getZIndex));
            int idx = -1;
            for (int i = 0; i < widgets.size(); i++) {
                if (Objects.equals(widgets.get(i).getId(), widgetId)) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                throw new ServiceException("控件不存在");
            }
            int swapIdx = "down".equalsIgnoreCase(dir) ? idx + 1 : idx - 1;
            if (swapIdx < 0 || swapIdx >= widgets.size()) {
                return; // 已到顶/底
            }
            TVisWidget a = widgets.get(idx);
            TVisWidget b = widgets.get(swapIdx);
            if (isLocked(a) || isLocked(b)) {
                throw new ServiceException("存在已锁定的控件,请先解锁后再调整图层顺序");
            }
            Long az = a.getZIndex(), bz = b.getZIndex();
            a.setZIndex(bz);
            b.setZIndex(az);
            baseMapper.updateById(a);
            baseMapper.updateById(b);
        });
    }

    /**
     * 图层批量重排:按 widgetIds 顺序(上→下)重分配 zIndex(前 = z 大 = 上层)。按场景加锁原子。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reorder(Long sceneId, List<Long> widgetIds) {
        if (sceneId == null || widgetIds == null || widgetIds.isEmpty()) {
            return;
        }
        withSceneLock(sceneId, () -> {
            // 一次批量取回(此前先逐个 selectById 判锁定,再逐个 selectById + updateById 改 zIndex)
            Map<Long, TVisWidget> byId = baseMapper.selectByIds(widgetIds.stream().distinct().toList())
                .stream()
                .collect(java.util.stream.Collectors.toMap(TVisWidget::getId, w -> w, (a, b) -> a));
            boolean hasLocked = byId.values().stream().anyMatch(this::isLocked);
            if (hasLocked) {
                throw new ServiceException("存在已锁定的控件,请先解锁后再调整图层顺序");
            }
            int n = widgetIds.size();
            List<Map<String, Object>> items = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                Long id = widgetIds.get(i);
                TVisWidget w = byId.get(id);
                if (w != null && Objects.equals(w.getSceneId(), sceneId)) {
                    Map<String, Object> item = new java.util.HashMap<>();
                    item.put("id", id);
                    item.put("zIndex", (long) (n - i)); // 前 = z 大 = 上层
                    items.add(item);
                }
            }
            if (!items.isEmpty()) {
                baseMapper.batchUpdateZIndex(items);
            }
        });
    }

    /**
     * 按场景加锁执行动作:有 Redis 时用 Redisson 分布式锁(多实例原子),
     * 无 Redis(单机模式)时退化为 JVM 本地可重入锁。
     */
    private void withSceneLock(Long sceneId, Runnable action) {
        String lockKey = "vis:scene:" + sceneId;
        RedissonClient client = redissonClientProvider.getIfAvailable();
        if (client != null) {
            RLock lock = client.getLock(lockKey);
            lock.lock();
            try {
                action.run();
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
            return;
        }
        ReentrantLock lock = JVM_SCENE_LOCKS.computeIfAbsent(lockKey, k -> new ReentrantLock());
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 保存前的数据校验
     */
    private void validEntityBeforeSave(TVisWidget entity){
        //TODO 做一些数据校验,如唯一约束
    }

    /**
     * 是否处于锁定状态
     */
    private boolean isLocked(TVisWidget widget) {
        return widget != null && Objects.equals(widget.getLocked(), 1L);
    }

    /**
     * 校验已锁定控件的编辑请求:除可见性与锁定状态外,其余字段(名称/几何/层级/内容/归属)一律禁止修改。
     * 前端为全量提交,因此以「新值非空且与旧值不同」判定实际变更,避免误伤解锁/显隐等合法操作。
     */
    private void assertNotLockedEdit(TVisWidget existing, TVisWidget update) {
        if (changed(existing.getName(), update.getName())
            || changed(existing.getType(), update.getType())
            || changed(existing.getSceneId(), update.getSceneId())
            || changed(existing.getTournamentId(), update.getTournamentId())
            || changed(existing.getX(), update.getX())
            || changed(existing.getY(), update.getY())
            || changed(existing.getW(), update.getW())
            || changed(existing.getH(), update.getH())
            || changed(existing.getZIndex(), update.getZIndex())
            || changed(existing.getLayoutConfig(), update.getLayoutConfig())
            || changed(existing.getDataConfig(), update.getDataConfig())
            || changed(existing.getRenderConfig(), update.getRenderConfig())
            || changed(existing.getRemark(), update.getRemark())) {
            throw new ServiceException("控件已锁定,请先解锁后再编辑");
        }
    }

    /**
     * 新值非空且与旧值不同时视为发生变更
     */
    private boolean changed(Object oldVal, Object newVal) {
        return newVal != null && !Objects.equals(oldVal, newVal);
    }

    /**
     * 校验并批量删除场景控件元素信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    @Override
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        List<TVisWidget> widgets = baseMapper.selectBatchIds(ids);
        if (isValid) {
            boolean hasLocked = widgets.stream().anyMatch(this::isLocked);
            if (hasLocked) {
                throw new ServiceException("存在已锁定的控件,请先解锁后再删除");
            }
        }

        boolean result = baseMapper.deleteByIds(ids) > 0;

        // 通知所有显示相关场景的屏幕更新
        if (result) {
            widgets.forEach(widget -> notifySceneUpdate(widget.getSceneId()));
        }

        return result;
    }

    /**
     * 通知场景更新
     *
     * @param sceneId 场景ID
     */
    private void notifySceneUpdate(Long sceneId) {
        if (sceneId == null) {
            return;
        }
        // 获取场景信息以获得 tournamentId
        TVisScene scene = sceneMapper.selectById(sceneId);
        if (scene != null && scene.getTournamentId() != null) {
            TournamentSseMessageUtils.notifySceneUpdate(
                String.valueOf(scene.getTournamentId()),
                String.valueOf(sceneId)
            );
        }
    }
}

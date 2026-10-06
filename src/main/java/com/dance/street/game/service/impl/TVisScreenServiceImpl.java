package com.dance.street.game.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dance.street.game.domain.TVisScreen;
import com.dance.street.game.domain.bo.TVisScreenBo;
import com.dance.street.game.domain.vo.TVisScreenVo;
import com.dance.street.game.mapper.TVisScreenMapper;
import com.dance.street.game.service.ITVisScreenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.sse.utils.TournamentSseMessageUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 大屏屏幕配置Service业务层处理。
 *
 * <p>屏幕从客户端本地(history.state)上移到服务端后,任何一次屏幕增删改都要广播
 * {@code screenListChanged},让其它控制端重新拉取,从而多台控制端共用同一份屏幕列表。</p>
 *
 * @author duane
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TVisScreenServiceImpl implements ITVisScreenService {

    private final TVisScreenMapper baseMapper;

    /** 赛事级建默认屏的 JVM 内串行锁:并发首屏请求不至于各建一块(多实例下仍需业务侧容忍极小概率重复) */
    private static final Object DEFAULT_LOCK = new Object();

    @Override
    public List<TVisScreenVo> listByTournament(Long tournamentId) {
        if (tournamentId == null) {
            return List.of();
        }
        List<TVisScreenVo> list = baseMapper.selectVoList(buildQueryWrapper(tournamentId));
        if (!list.isEmpty()) {
            return list;
        }
        synchronized (DEFAULT_LOCK) {
            // 双重检查:第一个请求建完之后,后面进来的直接返回已存在的屏幕
            list = baseMapper.selectVoList(buildQueryWrapper(tournamentId));
            if (!list.isEmpty()) {
                return list;
            }
            TVisScreen def = new TVisScreen();
            def.setTournamentId(tournamentId);
            def.setName("屏幕 1");
            def.setSortOrder(0L);
            baseMapper.insert(def);
            notifyScreenListChanged(tournamentId);
            return baseMapper.selectVoList(buildQueryWrapper(tournamentId));
        }
    }

    @Override
    public TVisScreenVo insertByBo(TVisScreenBo bo) {
        TVisScreen add = MapstructUtils.convert(bo, TVisScreen.class);
        if (add.getSortOrder() == null) {
            add.setSortOrder((long) baseMapper.selectVoList(buildQueryWrapper(bo.getTournamentId())).size());
        }
        baseMapper.insert(add);
        notifyScreenListChanged(add.getTournamentId());
        return MapstructUtils.convert(add, TVisScreenVo.class);
    }

    @Override
    public TVisScreenVo updateByBo(TVisScreenBo bo) {
        TVisScreen update = MapstructUtils.convert(bo, TVisScreen.class);
        // 改名/排序不覆盖投射状态:投射状态只由投射入口改写
        update.setCurrentSceneId(null);
        baseMapper.updateById(update);
        notifyScreenListChanged(bo.getTournamentId());
        return MapstructUtils.convert(update, TVisScreenVo.class);
    }

    @Override
    public Boolean deleteByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        List<TVisScreen> screens = baseMapper.selectBatchIds(ids);
        boolean result = baseMapper.deleteByIds(ids) > 0;
        if (result) {
            Set<Long> tournamentIds = new LinkedHashSet<>();
            for (TVisScreen screen : screens) {
                if (screen.getTournamentId() != null) {
                    tournamentIds.add(screen.getTournamentId());
                }
                // 断掉该屏幕上的管理端/浏览端连接,避免已删除的屏幕还挂着长连接
                TournamentSseMessageUtils.disconnect(String.valueOf(screen.getId()));
            }
            tournamentIds.forEach(this::notifyScreenListChanged);
        }
        return result;
    }

    @Override
    public void bindCurrentScene(Long screenId, Long sceneId) {
        if (screenId == null) {
            return;
        }
        LambdaUpdateWrapper<TVisScreen> luw = Wrappers.lambdaUpdate();
        luw.eq(TVisScreen::getId, screenId);
        luw.set(TVisScreen::getCurrentSceneId, sceneId);
        // 屏幕不存在时 update 影响 0 行,静默忽略(兼容历史遗留的本地屏幕ID)
        baseMapper.update(null, luw);
    }

    private LambdaQueryWrapper<TVisScreen> buildQueryWrapper(Long tournamentId) {
        LambdaQueryWrapper<TVisScreen> lqw = Wrappers.lambdaQuery();
        lqw.eq(TVisScreen::getTournamentId, tournamentId);
        lqw.orderByAsc(TVisScreen::getSortOrder);
        lqw.orderByAsc(TVisScreen::getId);
        return lqw;
    }

    private void notifyScreenListChanged(Long tournamentId) {
        if (tournamentId != null) {
            TournamentSseMessageUtils.notifyScreenListChanged(String.valueOf(tournamentId));
        }
    }
}

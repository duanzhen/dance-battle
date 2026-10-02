package com.dance.street.game.service.impl.flow;

import com.dance.street.game.domain.TStage;
import com.dance.street.game.mapper.TStageMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

/**
 * 「取赛段」的唯一入口:按主键取,取不到就报「赛段不存在」。
 *
 * <p>这条守卫此前是 {@code TStageLifecycleServiceImpl} 的私有方法,却被类内 27 处调用,
 * 横跨初始化、赛中推进、结算、擂台、自由对抗、结果导出所有业务线 —— 是那个类拆不干净
 * 的根本原因。抽成独立组件后,各协作者都能直接注入,不必各自复制一份查询。</p>
 *
 * @author duane
 */
@Component
@RequiredArgsConstructor
public class StageLookup {

    private final TStageMapper stageMapper;

    /** 取赛段,不存在时抛业务异常(与拆分前的 mustGetStage 行为一致,不做额外校验) */
    public TStage get(Long stageId) {
        TStage stage = stageMapper.selectById(stageId);
        if (stage == null) {
            throw new ServiceException("赛段不存在");
        }
        return stage;
    }
}

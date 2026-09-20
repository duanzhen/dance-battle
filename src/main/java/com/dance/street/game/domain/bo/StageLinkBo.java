package com.dance.street.game.domain.bo;

import lombok.Data;

/**
 * 赛段链顺序调整请求:把目标赛段移动到 {@code afterStageId} 之后。
 *
 * <p>只表达意图,不传 prev/next 指针:链由后端 {@code StageChain} 自己推导并写入,
 * 客户端拿到的指针只是展示副本。</p>
 *
 * <p>{@code afterStageId} 为空表示移动到链头(成为入口赛段)。</p>
 *
 * @author duane
 */
@Data
public class StageLinkBo {

    /** 移动到这个赛段之后;空 = 移到链头 */
    private Long afterStageId;

}

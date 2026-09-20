package com.dance.street.game.domain.bo;

import org.dromara.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 赛段配置业务对象(只含配置字段,不含链表指针)。
 *
 * <p>「改配置」和「改链」是两件事,此前共用一个 BO:配置面板保存时会把它本地缓存的
 * {@code prevStageId}/{@code nextStageId} 一起回传,后端据此重写整条链——只要前端那份
 * 副本过期,一次纯配置保存就能把链写歪。</p>
 *
 * <p>本 BO 刻意不提供指针字段,{@code PUT /game/stage/{id}/config} 因此不可能动链;
 * 需要改链请走 {@code PUT /game/stage/{id}/link}(意图 = 移到哪个赛段之后)。</p>
 *
 * @author duane
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TStageConfigBo extends BaseEntity {

    /**
     * 赛段主键(路径参数回填)
     */
    private Long id;

    /**
     * 所属赛事:仅用于校验,不允许通过配置入口改归属
     */
    private Long tournamentId;

    /**
     * 赛段名称
     */
    private String name;

    /**
     * AUDITION, KNOCKOUT, GROUP, ARENA, RANK, FREE_MATCH
     */
    private String stageMode;

    /**
     * 每队成员数量
     */
    private Long members;

    /**
     * 在大图中处于第几列 (X轴)
     */
    private Long visualColIndex;

    /**
     * 赛制配置(JSON)
     */
    private String ruleConfig;

    /**
     * 状态
     */
    private String status;

    /**
     * 起始选手数量
     */
    private Long teamCountStart;

    /**
     * 晋级选手数量
     */
    private Long teamCountEnd;

    /**
     * 视觉配置：{"color": "#f59e0b", "icon": "trophy"}
     */
    private String visualConfig;

    /**
     * 备注
     */
    private String remark;

}

package com.dance.street.game.domain.vo;

import com.dance.street.game.domain.TStage;
import com.dance.street.game.excel.ExcelIgnoreUnannotated;
import com.dance.street.game.excel.ExcelProperty;
import com.dance.street.game.excel.ExcelDictFormat;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.List;



/**
 * 赛段流程视图对象 t_stage
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@ExcelIgnoreUnannotated
@AutoMapper(target = TStage.class)
public class TStageVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     *
     */
    @ExcelProperty(value = "")
    private Long id;

    /**
     *
     */
    @ExcelProperty(value = "")
    private Long tournamentId;

    /**
     * 上一赛段ID
     */
    @ExcelProperty(value = "上一赛段ID")
    private Long prevStageId;

    /**
     * 下一赛段ID (可修改以实现途中变轨)
     */
    @ExcelProperty(value = "下一赛段ID (可修改以实现途中变轨)")
    private Long nextStageId;

    /**
     * 父ID (用于同分加赛)
     */
    @ExcelProperty(value = "父ID (用于同分加赛)")
    private Long parentStageId;

    /**
     * 32进16 / 复活赛
     */
    @ExcelProperty(value = "32进16 / 复活赛")
    private String name;

    /**
     * AUDITION, KNOCKOUT, GROUP, ARENA, RANK
     */
    @ExcelProperty(value = "AUDITION, KNOCKOUT, GROUP, ARENA, RANK")
    private String stageMode;

    /**
     * 每队成员数量
     */
    private Long members;

    /**
     * 在大图中处于第几列 (X轴)
     */
    @ExcelProperty(value = "在大图中处于第几列 (X轴)")
    private Long visualColIndex;

    /**
     *
     */
    @ExcelProperty(value = "")
    private String ruleConfig;

    /**
     * 状态
     */
    @ExcelProperty(value = "状态")
    private String status;

    /**
     * 起始选手数量
     */
    @ExcelProperty(value = "起始选手数量")
    private Long teamCountStart;

    /**
     * 晋级选手数量
     */
    @ExcelProperty(value = "晋级选手数量")
    private Long teamCountEnd;

    /**
     * 是否完成初始化配置：0-否 1-是
     */
    @ExcelProperty(value = "是否完成初始化配置：0-否 1-是")
    private Long isInitialized;

    /**
     * 视觉配置：{"color": "#f59e0b", "icon": "trophy"}
     */
    @ExcelProperty(value = "视觉配置")
    private String visualConfig;

    /**
     * 备注
     */
    @ExcelProperty(value = "备注")
    private String remark;

    /**
     * 是否等待中间态确认晋级(导播台用):上一赛段已结算且存在待确认晋级者,
     * 本赛段尚未接收晋级者时为 true,此时不可开始赛段
     */
    private Boolean awaitingAdvancement;

    /**
     * 是否开启「跳过中间态确认阶段」(赛事级配置,导播台用):
     * 开启后中间态未确认时也可点开始赛段,弹窗确认后自动确认晋级再开始
     */
    private Boolean skipConfirm;

    /**
     * 名单摘要(roster;无名单/未加载时为 null)
     */
    private List<TStageRosterVo> incoming;

}

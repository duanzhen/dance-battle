package com.dance.street.game.domain.vo;

import com.dance.street.game.domain.TCompetitor;
import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import org.dromara.common.excel.annotation.ExcelDictFormat;
import org.dromara.common.excel.convert.ExcelDictConvert;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.List;



/**
 * 参赛单位视图对象 t_competitor
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@ExcelIgnoreUnannotated
@AutoMapper(target = TCompetitor.class)
public class TCompetitorVo implements Serializable {

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
     *
     */
    @ExcelProperty(value = "")
    private Long stageId;

    /**
     * 上一阶段的CompetitorID
     */
    @ExcelProperty(value = "上一阶段的CompetitorID")
    private Long sourceCompetitorId;

    /**
     * 0:个人, 1:队伍
     */
    @ExcelProperty(value = "0:个人, 1:队伍")
    private Long type;

    /**
     * 展示名称
     */
    @ExcelProperty(value = "展示名称")
    private String name;

    /**
     * 选手号
     */
    private String number;

    /**
     * 本赛段初始种子顺位
     */
    @ExcelProperty(value = "本赛段初始种子顺位")
    private Long seedRank;

    /**
     * 本赛段最终排名
     */
    @ExcelProperty(value = "本赛段最终排名")
    private Long finalRank;

    /**
     * 本赛段结果
     */
    @ExcelProperty(value = "本赛段结果")
    private String outcomeStatus;

    /**
     * 备注
     */
    @ExcelProperty(value = "备注")
    private String remark;

    /**
     * 选手列表
     */
    private List<TPlayerVo> playerList;

}

package com.dance.street.game.domain.vo;

import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.excel.ExcelIgnoreUnannotated;
import com.dance.street.game.excel.ExcelProperty;
import com.dance.street.game.excel.ExcelDictFormat;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;



/**
 * 场次参赛人员记录视图对象 t_match_participant
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@ExcelIgnoreUnannotated
@AutoMapper(target = TMatchParticipant.class)
public class TMatchParticipantVo implements Serializable {

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
    private Long matchId;

    /**
     * 
     */
    @ExcelProperty(value = "")
    private Long competitorId;

    /**
     * 
     */
    @ExcelProperty(value = "")
    private Long displaySlotIndex;

    /**
     * 总分/票数
     */
    @ExcelProperty(value = "总分/票数")
    private BigDecimal scoreValue;

    /**
     * 本场排名
     */
    @ExcelProperty(value = "本场排名")
    private Long rankInMatch;

    /**
     * 选手结果
     */
    @ExcelProperty(value = "选手结果")
    private String outcomeStatus;

    /**
     * 备注
     */
    @ExcelProperty(value = "备注")
    private String remark;


}

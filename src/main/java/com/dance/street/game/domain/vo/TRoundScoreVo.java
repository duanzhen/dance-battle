package com.dance.street.game.domain.vo;

import com.dance.street.game.domain.TRoundScore;
import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import org.dromara.common.excel.annotation.ExcelDictFormat;
import org.dromara.common.excel.convert.ExcelDictConvert;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;



/**
 * 轮次打分结果视图对象 t_round_score
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@ExcelIgnoreUnannotated
@AutoMapper(target = TRoundScore.class)
public class TRoundScoreVo implements Serializable {

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
    private Long roundId;

    /**
     * 
     */
    @ExcelProperty(value = "")
    private Long competitorId;

    /**
     * 
     */
    @ExcelProperty(value = "")
    private String action;

    /**
     * 
     */
    @ExcelProperty(value = "")
    private Long refereeId;

    /**
     * 
     */
    @ExcelProperty(value = "")
    private BigDecimal score;

    /**
     * 
     */
    @ExcelProperty(value = "")
    private String dimension;

    /**
     * 备注
     */
    @ExcelProperty(value = "备注")
    private String remark;


}

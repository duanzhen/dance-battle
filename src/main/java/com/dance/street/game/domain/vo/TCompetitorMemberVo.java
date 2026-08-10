package com.dance.street.game.domain.vo;

import com.dance.street.game.domain.TCompetitorMember;
import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import org.dromara.common.excel.annotation.ExcelDictFormat;
import org.dromara.common.excel.convert.ExcelDictConvert;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;



/**
 * 参赛成员关联视图对象 t_competitor_member
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@ExcelIgnoreUnannotated
@AutoMapper(target = TCompetitorMember.class)
public class TCompetitorMemberVo implements Serializable {

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
    private Long competitorId;

    /**
     * 
     */
    @ExcelProperty(value = "")
    private Long playerId;

    /**
     * CAPTAIN, MEMBER
     */
    @ExcelProperty(value = "CAPTAIN, MEMBER")
    private String role;

    /**
     * 备注
     */
    @ExcelProperty(value = "备注")
    private String remark;


}

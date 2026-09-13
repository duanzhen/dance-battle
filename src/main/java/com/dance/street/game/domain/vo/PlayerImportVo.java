package com.dance.street.game.domain.vo;

import com.dance.street.game.excel.ExcelIgnoreUnannotated;
import com.dance.street.game.excel.ExcelProperty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 选手导入模板 VO
 *
 * @author duane
 * @date 2026-08-01
 */
@Data
@ExcelIgnoreUnannotated
public class PlayerImportVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @ExcelProperty(value = "选手名称")
    private String name;

    @ExcelProperty(value = "身份唯一标识")
    private String idCard;

    @ExcelProperty(value = "标签")
    private String tags;

    @ExcelProperty(value = "备注")
    private String remark;
}

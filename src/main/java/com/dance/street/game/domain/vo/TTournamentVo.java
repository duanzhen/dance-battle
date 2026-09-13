package com.dance.street.game.domain.vo;

import com.dance.street.game.domain.TTournament;
import com.dance.street.game.excel.ExcelIgnoreUnannotated;
import com.dance.street.game.excel.ExcelProperty;
import com.dance.street.game.excel.ExcelDictFormat;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;



/**
 * 赛事主视图对象 t_tournament
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@ExcelIgnoreUnannotated
@AutoMapper(target = TTournament.class)
public class TTournamentVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     *
     */
    @ExcelProperty(value = "")
    private Long id;

    /**
     * 赛事名称
     */
    @ExcelProperty(value = "赛事名称")
    private String name;

    /**
     * 封面图片URL
     */
    @ExcelProperty(value = "封面图片URL")
    private String coverImage;

    /**
     * 封面图片URLUrl
     */
    private String coverImageUrl;
    /**
     * 详情
     */
    @ExcelProperty(value = "详情")
    private String description;

    /**
     * 0:筹备 1:进行中 2:结束
     */
    @ExcelProperty(value = "0:筹备 1:进行中 2:结束")
    private Long status;

    /**
     * 设计稿宽度
     */
    @ExcelProperty(value = "设计稿宽度")
    private Long logicalWidth;

    /**
     * 设计稿高度
     */
    @ExcelProperty(value = "设计稿高度")
    private Long logicalHeight;

    /**
     * {"bgColor": "#000", "fontFamily": "Roboto"}
     */
    @ExcelProperty(value = "主题配置")
    private String themeConfig;

    /**
     * 备注
     */
    @ExcelProperty(value = "备注")
    private String remark;


}

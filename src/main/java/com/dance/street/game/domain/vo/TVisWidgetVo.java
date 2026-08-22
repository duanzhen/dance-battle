package com.dance.street.game.domain.vo;

import com.dance.street.game.domain.TVisWidget;
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
 * 场景控件元素视图对象 t_vis_widget
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@ExcelIgnoreUnannotated
@AutoMapper(target = TVisWidget.class)
public class TVisWidgetVo implements Serializable {

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
    private Long sceneId;

    /**
     * 控件备注
     */
    @ExcelProperty(value = "控件备注")
    private String name;

    /**
     * BRACKET, SCOREBOARD, PLAYER_CARD, IMAGE, RANKING
     */
    @ExcelProperty(value = "BRACKET, SCOREBOARD, PLAYER_CARD, IMAGE, RANKING")
    private String type;

    /**
     * 
     */
    @ExcelProperty(value = "")
    private String layoutConfig;

    /**
     * X坐标
     */
    @ExcelProperty(value = "X坐标")
    private Long x;

    /**
     * Y坐标
     */
    @ExcelProperty(value = "Y坐标")
    private Long y;

    /**
     * 宽度
     */
    @ExcelProperty(value = "宽度")
    private Long w;

    /**
     * 高度
     */
    @ExcelProperty(value = "高度")
    private Long h;

    /**
     * Z轴层级
     */
    @ExcelProperty(value = "Z轴层级")
    private Long zIndex;

    /**
     * 是否可见：0-隐藏 1-显示
     */
    @ExcelProperty(value = "是否可见：0-隐藏 1-显示")
    private Long visible;

    /**
     * 是否锁定：0-否 1-是（锁定后不可编辑）
     */
    @ExcelProperty(value = "是否锁定：0-否 1-是", converter = ExcelDictConvert.class)
    @ExcelDictFormat(readConverterExp = "锁=定后不可编辑")
    private Long locked;

    /**
     * 
     */
    @ExcelProperty(value = "")
    private String dataConfig;

    /**
     * 
     */
    @ExcelProperty(value = "")
    private String renderConfig;

    /**
     * 备注
     */
    @ExcelProperty(value = "备注")
    private String remark;


}

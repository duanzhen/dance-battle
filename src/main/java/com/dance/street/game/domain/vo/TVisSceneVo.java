package com.dance.street.game.domain.vo;

import com.dance.street.game.domain.TVisScene;
import com.dance.street.game.excel.ExcelIgnoreUnannotated;
import com.dance.street.game.excel.ExcelProperty;
import com.dance.street.game.excel.ExcelDictFormat;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;



/**
 * 可视化场景配置视图对象 t_vis_scene
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@ExcelIgnoreUnannotated
@AutoMapper(target = TVisScene.class)
public class TVisSceneVo implements Serializable {

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
     * 场景名: 总决赛KV / 竖屏比分
     */
    @ExcelProperty(value = "场景名: 总决赛KV / 竖屏比分")
    private String name;

    /**
     * 
     */
    @ExcelProperty(value = "")
    private Long designWidth;

    /**
     * 
     */
    @ExcelProperty(value = "")
    private Long designHeight;

    /**
     * 场景格式：DEFAULT / VERTICAL / CUSTOM
     */
    @ExcelProperty(value = "场景格式：DEFAULT / VERTICAL / CUSTOM")
    private String format;

    /**
     * 背景颜色
     */
    @ExcelProperty(value = "背景颜色")
    private String bgColor;

    /**
     * 场景排序
     */
    @ExcelProperty(value = "场景排序")
    private Long sortOrder;

    /**
     * 备注
     */
    @ExcelProperty(value = "备注")
    private String remark;


}

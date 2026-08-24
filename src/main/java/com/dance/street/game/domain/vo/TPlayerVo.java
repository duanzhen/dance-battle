package com.dance.street.game.domain.vo;

import com.dance.street.game.domain.TPlayer;
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
 * 选手自然人视图对象 t_player
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@ExcelIgnoreUnannotated
@AutoMapper(target = TPlayer.class)
public class TPlayerVo implements Serializable {

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
    @ExcelProperty(value = "选手名称")
    private String name;

    /**
     *
     */
    @ExcelProperty(value = "头像")
    private String avatar;

    /**
     * 身份唯一标识
     */
    @ExcelProperty(value = "身份唯一标识")
    private String idCard;

    /**
     * 首个赛段参赛选手id
     */
    private Long competitorId;

    /**
     * 首个赛段参赛选手
     */
    private TCompetitorVo competitorVo;


    /**
     * 标签: ["种子", "GUEST"]
     */
    @ExcelProperty(value = "标签")
    private String tags;

    /**
     * 备注
     */
    @ExcelProperty(value = "备注")
    private String remark;


}

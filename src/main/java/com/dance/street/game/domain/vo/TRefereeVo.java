package com.dance.street.game.domain.vo;

import com.dance.street.game.domain.TReferee;
import com.dance.street.game.excel.ExcelIgnoreUnannotated;
import com.dance.street.game.excel.ExcelProperty;
import com.dance.street.game.excel.ExcelDictFormat;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;



/**
 * 裁判视图对象 t_referee
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@ExcelIgnoreUnannotated
@AutoMapper(target = TReferee.class)
public class TRefereeVo implements Serializable {

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
     * 头像URL
     */
    private String avatar;

    /**
     *
     */
    @ExcelProperty(value = "")
    private String name;

    /**
     * 权限: ["STAGE_1_GROUP_A"]
     */
    @ExcelProperty(value = "权限")
    private String permissions;

    /**
     * 登录凭证
     */
    private String authKey;

    /**
     * 备注
     */
    @ExcelProperty(value = "备注")
    private String remark;

    /**
     * 已绑定的赛段数量(非持久化,用于前端状态展示:>0 视为 ACTIVE)
     */
    private Long assignedStageCount;

}

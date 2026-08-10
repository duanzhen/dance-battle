package com.dance.street.game.domain.bo;

import com.dance.street.game.domain.TRoundScore;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/**
 * 轮次打分结果业务对象 t_round_score
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = TRoundScore.class, reverseConvertGenerate = false)
public class TRoundScoreBo extends BaseEntity {

    /**
     * 
     */
    @NotNull(message = "不能为空", groups = { EditGroup.class })
    private Long id;

    /**
     * 
     */
    @NotNull(message = "不能为空", groups = { AddGroup.class, EditGroup.class })
    private Long tournamentId;

    /**
     * 
     */
    @NotNull(message = "不能为空", groups = { AddGroup.class, EditGroup.class })
    private Long roundId;

    /**
     * 
     */
    @NotNull(message = "不能为空", groups = { AddGroup.class, EditGroup.class })
    private Long competitorId;

    /**
     * 
     */
    private String action;

    /**
     * 
     */
    private Long refereeId;

    /**
     * 
     */
    private BigDecimal score;

    /**
     * 
     */
    private String dimension;

    /**
     * 备注
     */
    private String remark;


}

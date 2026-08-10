package com.dance.street.game.domain.bo;

import com.dance.street.game.domain.TMatchParticipant;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/**
 * 场次参赛人员记录业务对象 t_match_participant
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = TMatchParticipant.class, reverseConvertGenerate = false)
public class TMatchParticipantBo extends BaseEntity {

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
    private Long matchId;

    /**
     * 
     */
    @NotNull(message = "不能为空", groups = { AddGroup.class, EditGroup.class })
    private Long competitorId;

    /**
     * 
     */
    private Long displaySlotIndex;

    /**
     * 总分/票数
     */
    private BigDecimal scoreValue;

    /**
     * 本场排名
     */
    private Long rankInMatch;

    /**
     * 选手结果
     */
    private String outcomeStatus;

    /**
     * 备注
     */
    private String remark;


}

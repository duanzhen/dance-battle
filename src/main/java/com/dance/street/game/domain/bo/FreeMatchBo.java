package com.dance.street.game.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 自由对抗:导播台手动添加一场对战(线下抽签/指认确定的两名选手)。
 *
 * @author duane
 */
@Data
public class FreeMatchBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 左侧选手(参赛方ID) */
    @NotNull(message = "请选择左侧选手")
    private Long competitorAId;

    /** 右侧选手(参赛方ID) */
    @NotNull(message = "请选择右侧选手")
    private Long competitorBId;
}

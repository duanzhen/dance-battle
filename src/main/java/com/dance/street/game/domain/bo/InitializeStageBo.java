package com.dance.street.game.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 赛段初始化请求:锁定参赛方名单并排种子顺位。
 *
 * @author duane
 * @date 2026-08-01
 */
@Data
public class InitializeStageBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "赛段ID不能为空")
    private Long stageId;

    /** 显式指定参赛方ID(可选;默认取本赛段所有 PENDING 状态的 competitor) */
    private List<Long> competitorIds;

    /** 是否按上赛段 finalRank 自动排种子(首个赛段可按 number/抽签);默认 true */
    private Boolean autoSeedFromRank;
}

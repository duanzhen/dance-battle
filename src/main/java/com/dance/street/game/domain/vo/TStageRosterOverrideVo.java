package com.dance.street.game.domain.vo;

import com.dance.street.game.domain.TStageRosterOverride;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 名单人工覆盖视图对象。
 *
 * @author duane
 */
@Data
@AutoMapper(target = TStageRosterOverride.class)
public class TStageRosterOverrideVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long tournamentId;
    private Long targetStageId;
    private String op;
    private Long sourceCompetitorId;
    private Long playerId;
    private String guestName;
    private Long guestType;
    private String guestNumber;
    private Long seedRank;
    private String remark;
}

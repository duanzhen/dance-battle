package com.dance.street.game.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 名单人工调整视图对象(中间层行的人工调整投影,id 即 {@code t_stage_roster_entry.id})。
 *
 * @author duane
 */
@Data
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

package com.dance.street.game.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 海选加赛「手动指定晋级人员」请求。
 *
 * @author duane
 */
@Data
public class TiebreakAdvanceBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 导播指定晋级的人员(必须是本加赛场次的参赛方;顺序不敏感,落库按号码牌升序规范)。 */
    private List<Long> competitorIds;
}

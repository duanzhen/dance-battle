package com.dance.street.game.engine.generator;

import lombok.Data;

import java.util.List;

/**
 * 整个赛段的对阵计划(Generator 纯计算产出,Service 据此落库)。
 */
@Data
public class BracketPlan {

    /** 标准化后的选手数(2 的幂) */
    private int bracketSize;

    /** 所有场次计划 */
    private List<MatchPlan> matches;
}

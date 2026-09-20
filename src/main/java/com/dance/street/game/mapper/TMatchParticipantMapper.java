package com.dance.street.game.mapper;

import com.dance.street.game.domain.TMatchParticipant;
import com.dance.street.game.domain.vo.TMatchParticipantVo;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 场次参赛人员记录Mapper接口
 *
 * @author duane
 * @date 2026-01-06
 */
public interface TMatchParticipantMapper extends BaseMapperPlus<TMatchParticipant, TMatchParticipantVo> {

    /**
     * 批量回写分数/名次/结果(结算与累计打分用):一条 CASE WHEN 覆盖全部变动行。
     *
     * <p>结算时逐个 {@code update} 会变成 O(人数) 条 SQL(64 人 = 64 条),这里合并成一条。
     * {@code items} 每项含 id / scoreValue / rankInMatch / outcomeStatus / writeOutcome;
     * {@code writeOutcome=false} 表示该行不改结果列(与逐个 update 时"null 字段跳过"的老语义一致)。</p>
     */
    @Update("<script>UPDATE t_match_participant SET "
        + "score_value = CASE id <foreach collection='items' item='it'>WHEN #{it.id} THEN #{it.scoreValue} </foreach>ELSE score_value END, "
        + "rank_in_match = CASE id <foreach collection='items' item='it'>WHEN #{it.id} THEN #{it.rankInMatch} </foreach>ELSE rank_in_match END, "
        + "outcome_status = CASE id <foreach collection='items' item='it'>WHEN #{it.id} THEN "
        + "<choose><when test='it.writeOutcome'>#{it.outcomeStatus}</when><otherwise>outcome_status</otherwise></choose> "
        + "</foreach>ELSE outcome_status END "
        + "WHERE id IN <foreach collection='items' item='it' open='(' separator=',' close=')'>#{it.id}</foreach>"
        + "</script>")
    int batchWriteScores(@Param("items") List<Map<String, Object>> items);

}

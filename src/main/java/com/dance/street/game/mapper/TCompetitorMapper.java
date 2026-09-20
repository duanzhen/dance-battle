package com.dance.street.game.mapper;

import com.dance.street.game.domain.TCompetitor;
import com.dance.street.game.domain.vo.TCompetitorVo;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 参赛单位Mapper接口
 *
 * @author duane
 * @date 2026-01-06
 */
public interface TCompetitorMapper extends BaseMapperPlus<TCompetitor, TCompetitorVo> {

    /**
     * 批量回写种子顺位(初始化/外部抽签排种子用):一条 CASE WHEN 覆盖全部行。
     *
     * <p>逐个 {@code updateById} 在 100 人的赛段就是 100 条 SQL。{@code items} 每项含 id / seedRank。</p>
     */
    @Update("<script>UPDATE t_competitor SET seed_rank = CASE id "
        + "<foreach collection='items' item='it'>WHEN #{it.id} THEN #{it.seedRank} </foreach>"
        + "ELSE seed_rank END "
        + "WHERE id IN <foreach collection='items' item='it' open='(' separator=',' close=')'>#{it.id}</foreach>"
        + "</script>")
    int batchUpdateSeedRank(@Param("items") List<Map<String, Object>> items);

}

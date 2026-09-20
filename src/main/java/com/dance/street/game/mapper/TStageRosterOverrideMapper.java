package com.dance.street.game.mapper;

import com.dance.street.game.domain.TStageRosterOverride;
import com.dance.street.game.domain.vo.TStageRosterOverrideVo;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 名单人工覆盖 Mapper。
 *
 * @author duane
 */
public interface TStageRosterOverrideMapper extends BaseMapperPlus<TStageRosterOverride, TStageRosterOverrideVo> {

    /**
     * 批量改种子位(名单拖拽排序保存用):一条 CASE WHEN 更新所有变动行。
     *
     * <p>拖拽一次通常整份名单都要改种子位,逐行 {@code updateById} 会变成 N 条 SQL
     * (36 人 = 36 条)。这里合并成一条。</p>
     *
     * @param items 每项含 id(Long) 与 seedRank(Long)
     */
    @Update("<script>UPDATE t_stage_roster_override SET seed_rank = CASE id "
        + "<foreach collection='items' item='it'>WHEN #{it.id} THEN #{it.seedRank} </foreach>"
        + "END WHERE id IN <foreach collection='items' item='it' open='(' separator=',' close=')'>#{it.id}</foreach>"
        + "</script>")
    int batchUpdateSeedRank(@Param("items") List<Map<String, Object>> items);
}

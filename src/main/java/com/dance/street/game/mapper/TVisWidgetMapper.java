package com.dance.street.game.mapper;

import com.dance.street.game.domain.TVisWidget;
import com.dance.street.game.domain.vo.TVisWidgetVo;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 场景控件元素Mapper接口
 *
 * @author duane
 * @date 2026-01-06
 */
public interface TVisWidgetMapper extends BaseMapperPlus<TVisWidget, TVisWidgetVo> {

    /**
     * 批量回写图层 zIndex(图层排序用):一条 CASE WHEN 覆盖全部图层。
     * {@code items} 每项含 id / zIndex。
     */
    @Update("<script>UPDATE t_vis_widget SET z_index = CASE id "
        + "<foreach collection='items' item='it'>WHEN #{it.id} THEN #{it.zIndex} </foreach>"
        + "ELSE z_index END "
        + "WHERE id IN <foreach collection='items' item='it' open='(' separator=',' close=')'>#{it.id}</foreach>"
        + "</script>")
    int batchUpdateZIndex(@Param("items") List<Map<String, Object>> items);

}

package com.dance.street.game.mapper;

import com.dance.street.game.domain.TStageRosterEntry;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 赛段中间层名单 Mapper。
 *
 * <p>读路径统一走 {@code listByTarget}(按 target_stage_id + slot 升序);不要写裸查询,
 * 避免有人忘了"座位号即位置、空位也是行"这条口径。</p>
 *
 * @author duane
 */
public interface TStageRosterEntryMapper extends BaseMapperPlus<TStageRosterEntry, TStageRosterEntry> {
}

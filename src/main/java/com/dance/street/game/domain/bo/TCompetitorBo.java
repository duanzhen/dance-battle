package com.dance.street.game.domain.bo;

import com.dance.street.game.domain.TCompetitor;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.*;

/**
 * 参赛单位业务对象 t_competitor
 *
 * @author duane
 * @date 2026-01-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = TCompetitor.class, reverseConvertGenerate = false)
public class TCompetitorBo extends BaseEntity {

    /**
     *
     */
    @NotNull(message = "不能为空", groups = { EditGroup.class })
    private Long id;

    /**
     *
     */
    // 编辑是部分更新:调用方(如赛段选手列表改名)常只传 id+name,
    // 赛事/赛段由服务端按 id 反查已有记录补全,所以只在新增时强制要求。
    // 否则两个 NotNull 一起报错,前端只会看到一句「不能为空, 不能为空」。
    @NotNull(message = "不能为空", groups = { AddGroup.class })
    private Long tournamentId;

    /**
     *
     */
    @NotNull(message = "不能为空", groups = { AddGroup.class })
    private Long stageId;

    /**
     * 上一阶段的CompetitorID
     */
    private Long sourceCompetitorId;

    /**
     * 参赛方类型(保留字段;系统统一按选手处理,多成员即组队)
     */
    private Long type;

    /**
     * 展示名称
     */
    private String name;

    /**
     * 是否联动同步名下唯一选手姓名(默认联动;赛段配置内单独改名时传 false)
     */
    private Boolean syncPlayerName;

    /**
     * 选手号
     */
    private String number;

    /**
     * 本赛段初始种子顺位
     */
    private Long seedRank;

    /**
     * 本赛段最终排名
     */
    private Long finalRank;

    /**
     * 本赛段结果
     */
    private String outcomeStatus;

    /**
     * 备注
     */
    private String remark;


}

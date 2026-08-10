package org.dromara.common.tenant.core;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 租户基类
 *
 * @author Michelle.Chung
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TenantEntity extends BaseEntity {

    /**
     * 租户编号
     * 多租户不实现,但保留字段;由 MetaObjectHandler 插入时兜底填充 "0"
     */
    @TableField(fill = FieldFill.INSERT)
    private String tenantId;

}

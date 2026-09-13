package com.dance.street.game.excel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字段值 ↔ 展示标签的映射声明(用于导入/导出时的“字典”翻译)。
 *
 * <p>例:{@code @ExcelDictFormat(readConverterExp = "0=男,1=女,2=未知")} ——
 * 导出时把 {@code 0} 写成“男”,导入时把“男”还原成 {@code 0}。</p>
 *
 *
 * <p>匹配不上的值按原值输出(不转成空),这样漏配映射最多是“没翻译”,
 * 不会静默丢数据。</p>
 *
 * @author duane
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface ExcelDictFormat {

    /** 表达式内多项之间的分隔符(固定英文逗号,如 {@code 0=男,1=女}) */
    String ITEM_SEPARATOR = ",";

    /**
     * 读取内容转表达式,形如 {@code 0=男,1=女,2=未知}(用英文逗号分隔多项)
     */
    String readConverterExp() default "";

    /**
     * 多值字段内部的分隔符(如标签字段用 {@code |} 连接多个值时,可设为 {@code "|"})
     */
    String separator() default ITEM_SEPARATOR;
}

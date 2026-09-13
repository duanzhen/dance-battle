package com.dance.street.game.excel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 导入/导出时忽略该字段(优先级高于 {@link ExcelProperty})。
 *
 * @author duane
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExcelIgnore {
}

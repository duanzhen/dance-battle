package com.dance.street.game.excel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在类上:只处理带 {@link ExcelProperty} 的字段,其余字段忽略。
 *
 * @author duane
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface ExcelIgnoreUnannotated {
}

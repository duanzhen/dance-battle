package com.dance.street.game.excel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Excel 列声明:标注在需要导入/导出的字段上。
 *
 * <p>{@link #value()} 为表头文字;留空时回退为字段名(与原先 FastExcel 的行为一致)。
 * 列的顺序即字段声明顺序。</p>
 *
 * <p>本注解替代 FastExcel 的 {@code cn.idev.excel.annotation.ExcelProperty},
 * 由 {@link com.dance.street.game.excel.ExcelMapper} 读取,不依赖第三方库。
 * 换掉 FastExcel 的原因见 README「native 产物」一节:它用 cglib 在运行期生成
 * BeanMap 类,而 GraalVM native 不支持运行期类定义。</p>
 *
 * @author duane
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface ExcelProperty {

    /** 表头文字;留空表示用字段名 */
    String value() default "";
}

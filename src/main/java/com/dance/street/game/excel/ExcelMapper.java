package com.dance.street.game.excel;

import org.dhatim.fastexcel.Worksheet;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import com.dance.street.game.excel.ExcelDictFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 注解驱动的 POJO ↔ 工作表映射。
 *
 * <p>替换 FastExcel 的 {@code BeanMap}:后者在运行期用 cglib 生成类,而 GraalVM
 * native image 不支持运行期类定义,所以导出/导入在 native 下必然失败。这里改为
 * 朴素的字段反射,反射元数据由 {@code NativeRuntimeHints} 的包扫描统一登记。</p>
 *
 * <p>行为对齐原实现:</p>
 * <ul>
 *   <li>表头取 {@link ExcelProperty#value()},留空回退字段名;列序 = 字段声明顺序;</li>
 *   <li>{@link ExcelIgnoreUnannotated} 时只处理带 {@link ExcelProperty} 的字段;</li>
 *   <li>{@link ExcelIgnore} 的字段不处理;</li>
 *   <li>超过 15 位有效数字的整数按<b>文本</b>写单元格(Excel 数值只保证 15 位,
 *       否则 19 位的选手号会被舍入失真)——对应原 {@code ExcelBigNumberConvert};</li>
 *   <li>{@link ExcelDictFormat#readConverterExp()} 做值 ↔ 标签互转
 *       (仅支持 readConverterExp;本项目没有字典表,原 dictType 分支未使用故不保留)。</li>
 * </ul>
 *
 * @author duane
 */
public final class ExcelMapper {

    private static final Logger log = LoggerFactory.getLogger(ExcelMapper.class);

    /** Excel 数值只有 15 位有效数字 */
    private static final int EXCEL_SAFE_DIGITS = 15;

    private static final Map<Class<?>, List<Column>> COLUMN_CACHE = new ConcurrentHashMap<>();

    private ExcelMapper() {
    }

    /** 一列的元数据 */
    public record Column(Field field, String header, ExcelDictFormat dict) {
    }

    /**
     * 解析(并缓存)类型的导出列。
     */
    public static List<Column> columns(Class<?> clazz) {
        return COLUMN_CACHE.computeIfAbsent(clazz, ExcelMapper::resolveColumns);
    }

    private static List<Column> resolveColumns(Class<?> clazz) {
        boolean onlyAnnotated = clazz.isAnnotationPresent(ExcelIgnoreUnannotated.class);
        List<Column> columns = new ArrayList<>();
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()
                    || field.isAnnotationPresent(ExcelIgnore.class)) {
                    continue;
                }
                ExcelProperty property = field.getDeclaredAnnotation(ExcelProperty.class);
                if (property == null && onlyAnnotated) {
                    continue;
                }
                String header = (property == null || property.value().isBlank())
                    ? field.getName() : property.value();
                ExcelDictFormat dict = field.getDeclaredAnnotation(ExcelDictFormat.class);
                field.setAccessible(true);
                columns.add(new Column(field, header, dict));
            }
        }
        log.debug("ExcelMapper: {} 解析出 {} 列", clazz.getName(), columns.size());
        return List.copyOf(columns);
    }

    // ------------------------------------------------------------------ 写

    /** 写注解表的表头(第 0 行) */
    public static void writeHeader(Worksheet sheet, List<Column> columns) {
        for (int c = 0; c < columns.size(); c++) {
            sheet.value(0, c, columns.get(c).header());
        }
    }

    /** 写一行对象数据 */
    public static void writeBean(Worksheet sheet, int row, Object bean, List<Column> columns) {
        for (int c = 0; c < columns.size(); c++) {
            Column column = columns.get(c);
            writeValue(sheet, row, c, valueOf(bean, column), column.dict());
        }
    }

    /** 反射取字段值 */
    public static Object valueOf(Object bean, Column column) {
        try {
            return column.field().get(bean);
        } catch (IllegalAccessException e) {
            throw new ServiceException("读取字段失败: {}.{}",
                bean.getClass().getSimpleName(), column.field().getName());
        }
    }

    /** 值 → 展示文本(字典替换后的),用于列宽估算 */
    public static String display(Object value, ExcelDictFormat dict) {
        if (value == null) {
            return "";
        }
        if (dict != null) {
            value = toLabel(String.valueOf(value), dict);
        }
        return value == null ? "" : String.valueOf(value);
    }

    /** 写多层表头(第 i 列的第 r 个子标题落在第 r 行),用于手工组装的导出 */
    public static void writeRawHeader(Worksheet sheet, List<List<String>> head) {
        for (int c = 0; c < head.size(); c++) {
            List<String> titles = head.get(c);
            if (titles == null) {
                continue;
            }
            for (int r = 0; r < titles.size(); r++) {
                if (titles.get(r) != null) {
                    sheet.value(r, c, titles.get(r));
                }
            }
        }
    }

    /** 写一行原始数据(值已在内存里准备好) */
    public static void writeRawRow(Worksheet sheet, int row, List<?> values) {
        if (values == null) {
            return;
        }
        for (int c = 0; c < values.size(); c++) {
            writeValue(sheet, row, c, values.get(c), null);
        }
    }

    private static void writeValue(Worksheet sheet, int row, int col, Object value, ExcelDictFormat dict) {
        if (value == null) {
            return;
        }
        if (dict != null) {
            value = toLabel(String.valueOf(value), dict);
            if (value == null) {
                return;
            }
        }
        if (value instanceof Long l) {
            String text = Long.toString(l);
            if (text.length() > EXCEL_SAFE_DIGITS) {
                sheet.value(row, col, text);
            } else {
                sheet.value(row, col, l);
            }
        } else if (value instanceof Integer || value instanceof Short || value instanceof Byte
            || value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
            sheet.value(row, col, (Number) value);
        } else if (value instanceof Boolean b) {
            sheet.value(row, col, b);
        } else if (value instanceof Date d) {
            sheet.value(row, col, d);
        } else if (value instanceof LocalDate d) {
            sheet.value(row, col, d);
        } else if (value instanceof LocalDateTime d) {
            sheet.value(row, col, d);
        } else if (value instanceof Enum<?> e) {
            sheet.value(row, col, e.name());
        } else {
            sheet.value(row, col, String.valueOf(value));
        }
    }

    // ------------------------------------------------------------------ 读

    /**
     * 读第一个工作表为对象列表:首行是表头,按表头文字匹配字段。
     */
    public static <T> List<T> read(InputStream is, Class<T> clazz) {
        List<Column> columns = columns(clazz);
        Map<String, Column> byHeader = new HashMap<>();
        for (Column column : columns) {
            byHeader.put(column.header(), column);
        }
        List<T> result = new ArrayList<>();
        try (ReadableWorkbook workbook = new ReadableWorkbook(is)) {
            Sheet sheet = workbook.getFirstSheet();
            List<Row> rows = sheet.read();
            if (rows.isEmpty()) {
                return result;
            }
            Row headerRow = rows.get(0);
            Column[] byIndex = new Column[headerRow.getCellCount()];
            for (int i = 0; i < byIndex.length; i++) {
                String title = cellText(headerRow.getCell(i));
                if (StringUtils.isNotBlank(title)) {
                    byIndex[i] = byHeader.get(title.trim());
                }
            }
            Constructor<T> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            for (int r = 1; r < rows.size(); r++) {
                Row row = rows.get(r);
                T bean = constructor.newInstance();
                boolean anyValue = false;
                for (int i = 0; i < byIndex.length; i++) {
                    Column column = byIndex[i];
                    if (column == null) {
                        continue;
                    }
                    Cell cell = i < row.getCellCount() ? row.getCell(i) : null;
                    String text = cellText(cell);
                    if (StringUtils.isBlank(text)) {
                        continue;
                    }
                    try {
                        column.field().set(bean, convert(text, column, cell));
                    } catch (Exception e) {
                        throw new ServiceException("第 {} 行「{}」的值[{}]无法转换为 {}",
                            r + 1, column.header(), text,
                            column.field().getType().getSimpleName());
                    }
                    anyValue = true;
                }
                if (anyValue) {
                    result.add(bean);
                }
            }
        } catch (IOException e) {
            throw new ServiceException("读取 Excel 失败: {}", e.getMessage());
        } catch (ReflectiveOperationException e) {
            throw new ServiceException("实例化 {} 失败: {}", clazz.getSimpleName(), e.getMessage());
        }
        return result;
    }

    /** 单元格文本:数值走 BigDecimal.toPlainString,避免科学计数法 */
    private static String cellText(Cell cell) {
        if (cell == null) {
            return null;
        }
        Object value = cell.getValue();
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }

    private static Object convert(String text, Column column, Cell cell) {
        Class<?> type = column.field().getType();
        String value = text.trim();
        if (column.dict() != null) {
            value = toValue(value, column.dict());
        }
        if (type == String.class) {
            return value;
        }
        if (type == Long.class || type == long.class) {
            return Long.valueOf(value);
        }
        if (type == Integer.class || type == int.class) {
            return Integer.valueOf(value);
        }
        if (type == Short.class || type == short.class) {
            return Short.valueOf(value);
        }
        if (type == Byte.class || type == byte.class) {
            return Byte.valueOf(value);
        }
        if (type == Double.class || type == double.class) {
            return Double.valueOf(value);
        }
        if (type == Float.class || type == float.class) {
            return Float.valueOf(value);
        }
        if (type == BigDecimal.class) {
            return new BigDecimal(value);
        }
        if (type == Boolean.class || type == boolean.class) {
            return Boolean.valueOf(value);
        }
        if (type.isEnum()) {
            return enumOf(type, value);
        }
        if (cell != null && (type == LocalDate.class || type == LocalDateTime.class || type == Date.class)) {
            LocalDateTime dateTime = cell.asDate();
            if (type == LocalDate.class) {
                return dateTime.toLocalDate();
            }
            if (type == LocalDateTime.class) {
                return dateTime;
            }
            return java.sql.Timestamp.valueOf(dateTime);
        }
        throw new ServiceException("暂不支持把 Excel 值转换为 {}", type.getSimpleName());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumOf(Class<?> type, String value) {
        return Enum.valueOf((Class<? extends Enum>) type, value);
    }

    /** 值 → 展示标签;无匹配时保留原值(原实现的 convertByExp 无匹配会返回空串) */
    private static String toLabel(String value, ExcelDictFormat dict) {
        if (dict.readConverterExp().isBlank()) {
            return value;
        }
        String label = ExcelUtil.convertByExp(value, dict.readConverterExp(), dict.separator());
        return StringUtils.isEmpty(label) ? value : label;
    }

    /** 展示标签 → 值;无匹配时保留原文 */
    private static String toValue(String label, ExcelDictFormat dict) {
        if (dict.readConverterExp().isBlank()) {
            return label;
        }
        String value = ExcelUtil.reverseByExp(label, dict.readConverterExp(), dict.separator());
        return StringUtils.isEmpty(value) ? label : value;
    }
}

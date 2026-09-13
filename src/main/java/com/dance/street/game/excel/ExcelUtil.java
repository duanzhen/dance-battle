package com.dance.street.game.excel;

import cn.hutool.core.util.IdUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.core.utils.file.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.List;

/**
 * Excel 导入/导出。
 *
 * <p>底层用 {@code org.dhatim:fastexcel}(纯 StAX 写 xlsx)+ {@code fastexcel-reader}
 * (读 xlsx),不引入 Apache POI/xmlbeans,也不做运行期类生成——因此可以在 GraalVM
 * native image 下无 hint 直接工作。原先的 FastExcel 用 cglib 生成 BeanMap 类,
 * native 不支持运行期类定义,导出/导入必然失败。</p>
 *
 * <p>实际用到的只有两处:选手导入({@link #importExcel})与海选结果导出
 * ({@link #exportSheets},多 sheet 自定义报表)。注解驱动的
 * {@link #exportExcel} 保留给各实体的通用「导出」接口使用。</p>
 *
 * @author duane
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ExcelUtil {

    /** 写入 xlsx 的应用标识(仅影响文档属性) */
    private static final String APP_NAME = "dance-battle";
    private static final String APP_VERSION = "1.0";

    /** 列宽上限(字符数),过宽的备注类字段不撑爆版面 */
    private static final int MAX_COLUMN_WIDTH = 60;

    /**
     * 一个工作表的原始内容:表头(每列可多级)+ 数据行。
     *
     * <p>用于「表头/数据在内存里已经组装好」的报表,例如海选结果的
     * 主表 + 多张加赛表。</p>
     */
    public static final class RawSheet {

        private final String name;
        private final List<List<String>> head;
        private final List<List<Object>> rows;

        public RawSheet(String name, List<List<String>> head, List<List<Object>> rows) {
            this.name = name;
            this.head = head;
            this.rows = rows;
        }
    }

    /**
     * 同步导入(按表头文字匹配 {@code @ExcelProperty} 的字段)
     *
     * @param is    输入流
     * @param clazz 目标类型
     * @return 转换后集合(全空的行会被跳过)
     */
    public static <T> List<T> importExcel(InputStream is, Class<T> clazz) {
        return ExcelMapper.read(is, clazz);
    }

    /**
     * 导出到响应体(注解驱动)
     *
     * @param list      导出数据集合
     * @param sheetName 工作表名称
     * @param clazz     实体类
     * @param response  响应体
     */
    public static <T> void exportExcel(List<T> list, String sheetName, Class<T> clazz,
                                       HttpServletResponse response) {
        try {
            resetResponse(sheetName, response);
            exportExcel(list, sheetName, clazz, response.getOutputStream());
        } catch (IOException e) {
            throw new RuntimeException("导出Excel异常");
        }
    }

    /**
     * 导出到输出流(注解驱动)
     *
     * @param list      导出数据集合(空集合只写表头,可用于生成导入模板)
     * @param sheetName 工作表名称
     * @param clazz     实体类
     * @param os        输出流(调用方负责关闭)
     */
    public static <T> void exportExcel(List<T> list, String sheetName, Class<T> clazz, OutputStream os) {
        List<ExcelMapper.Column> columns = ExcelMapper.columns(clazz);
        try (Workbook workbook = new Workbook(os, APP_NAME, APP_VERSION)) {
            Worksheet sheet = workbook.newWorksheet(sheetName);
            ExcelMapper.writeHeader(sheet, columns);
            int[] widths = new int[columns.size()];
            for (int c = 0; c < columns.size(); c++) {
                widths[c] = displayWidth(columns.get(c).header());
            }
            for (int r = 0; r < list.size(); r++) {
                Object bean = list.get(r);
                ExcelMapper.writeBean(sheet, r + 1, bean, columns);
                for (int c = 0; c < columns.size(); c++) {
                    ExcelMapper.Column column = columns.get(c);
                    String display = ExcelMapper.display(ExcelMapper.valueOf(bean, column), column.dict());
                    widths[c] = Math.max(widths[c], displayWidth(display));
                }
            }
            applyWidths(sheet, widths);
            workbook.finish();
        } catch (IOException e) {
            throw new RuntimeException("导出Excel异常: " + e.getMessage(), e);
        }
    }

    /**
     * 导出多张原始工作表(海选结果这类自定义报表)
     *
     * @param sheets 工作表内容,按顺序写入
     * @param os     输出流(调用方负责关闭)
     */
    public static void exportSheets(List<RawSheet> sheets, OutputStream os) {
        try (Workbook workbook = new Workbook(os, APP_NAME, APP_VERSION)) {
            for (RawSheet data : sheets) {
                Worksheet sheet = workbook.newWorksheet(data.name);
                ExcelMapper.writeRawHeader(sheet, data.head);
                int headerRows = maxHeaderRows(data.head);
                int[] widths = rawWidths(data, headerRows);
                for (int i = 0; i < data.rows.size(); i++) {
                    ExcelMapper.writeRawRow(sheet, headerRows + i, data.rows.get(i));
                }
                applyWidths(sheet, widths);
            }
            workbook.finish();
        } catch (IOException e) {
            throw new RuntimeException("导出Excel异常: " + e.getMessage(), e);
        }
    }

    /** 表头占用行数(取所有列里最长的子标题列表) */
    private static int maxHeaderRows(List<List<String>> head) {
        int rows = 1;
        for (List<String> titles : head) {
            if (titles != null) {
                rows = Math.max(rows, titles.size());
            }
        }
        return rows;
    }

    /** 按表头与数据估算各列宽度 */
    private static int[] rawWidths(RawSheet data, int headerRows) {
        int columns = data.head == null ? 0 : data.head.size();
        int[] widths = new int[columns];
        for (int c = 0; c < columns; c++) {
            List<String> titles = data.head.get(c);
            if (titles != null) {
                for (String title : titles) {
                    widths[c] = Math.max(widths[c], displayWidth(title));
                }
            }
        }
        for (List<Object> row : data.rows) {
            for (int c = 0; c < row.size() && c < columns; c++) {
                widths[c] = Math.max(widths[c], displayWidth(ExcelMapper.display(row.get(c), null)));
            }
        }
        return widths;
    }

    private static void applyWidths(Worksheet sheet, int[] widths) {
        for (int c = 0; c < widths.length; c++) {
            sheet.width(c, Math.min(MAX_COLUMN_WIDTH, Math.max(8, widths[c] + 2)));
        }
    }

    /** 显示宽度:CJK 按 2 个字符宽度估算 */
    private static int displayWidth(String text) {
        if (StringUtils.isEmpty(text)) {
            return 0;
        }
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += text.charAt(i) > 0xFF ? 2 : 1;
        }
        return width;
    }

    /**
     * 解析导出值 0=男,1=女,2=未知
     *
     * @param propertyValue 参数值
     * @param converterExp  翻译注解
     * @param separator     分隔符
     * @return 解析后值
     */
    public static String convertByExp(String propertyValue, String converterExp, String separator) {
        StringBuilder propertyString = new StringBuilder();
        String[] convertSource = converterExp.split(ExcelDictFormat.ITEM_SEPARATOR);
        for (String item : convertSource) {
            String[] itemArray = item.split("=");
            if (StringUtils.containsAny(propertyValue, separator)) {
                for (String value : propertyValue.split(separator)) {
                    if (itemArray[0].equals(value)) {
                        propertyString.append(itemArray[1] + separator);
                        break;
                    }
                }
            } else {
                if (itemArray[0].equals(propertyValue)) {
                    return itemArray[1];
                }
            }
        }
        return StringUtils.stripEnd(propertyString.toString(), separator);
    }

    /**
     * 反向解析值 男=0,女=1,未知=2
     *
     * @param propertyValue 参数值
     * @param converterExp  翻译注解
     * @param separator     分隔符
     * @return 解析后值
     */
    public static String reverseByExp(String propertyValue, String converterExp, String separator) {
        StringBuilder propertyString = new StringBuilder();
        String[] convertSource = converterExp.split(ExcelDictFormat.ITEM_SEPARATOR);
        for (String item : convertSource) {
            String[] itemArray = item.split("=");
            if (StringUtils.containsAny(propertyValue, separator)) {
                for (String value : propertyValue.split(separator)) {
                    if (itemArray[1].equals(value)) {
                        propertyString.append(itemArray[0] + separator);
                        break;
                    }
                }
            } else {
                if (itemArray[1].equals(propertyValue)) {
                    return itemArray[0];
                }
            }
        }
        return StringUtils.stripEnd(propertyString.toString(), separator);
    }

    /**
     * 重置响应体
     */
    private static void resetResponse(String sheetName, HttpServletResponse response) throws UnsupportedEncodingException {
        String filename = encodingFilename(sheetName);
        FileUtils.setAttachmentResponseHeader(response, filename);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet;charset=UTF-8");
    }

    /**
     * 编码文件名
     */
    public static String encodingFilename(String filename) {
        return IdUtil.fastSimpleUUID() + "_" + filename + ".xlsx";
    }

}

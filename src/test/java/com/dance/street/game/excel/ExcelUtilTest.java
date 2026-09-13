package com.dance.street.game.excel;

import com.dance.street.game.domain.vo.PlayerImportVo;
import com.dance.street.game.domain.vo.TCompetitorVo;
import com.dance.street.game.domain.vo.TTournamentVo;
import com.dance.street.game.domain.vo.TVisWidgetVo;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Excel 导入/导出的行为基线(替换 FastExcel 后必须保持一致)。
 *
 * <p>表头断言取自替换前 FastExcel 的真实产物,相当于金标准。</p>
 */
class ExcelUtilTest {

    private static byte[] export(List<?> list, String sheet, Class<?> clazz) {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        //noinspection unchecked
        ExcelUtil.exportExcel((List<Object>) list, sheet, (Class<Object>) clazz, os);
        return os.toByteArray();
    }

    /** 读第 0 行(表头)的文本 */
    private static List<String> headerOf(byte[] xlsx) throws Exception {
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(xlsx))) {
            Row row = workbook.getFirstSheet().read().get(0);
            List<String> titles = new ArrayList<>();
            for (Cell cell : row) {
                titles.add(cell.getText());
            }
            return titles;
        }
    }

    private static List<Row> rowsOf(byte[] xlsx) throws Exception {
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(xlsx))) {
            return workbook.getFirstSheet().read();
        }
    }

    @Test
    void headerUsesAnnotationAndFallsBackToFieldName() throws Exception {
        assertEquals(List.of("选手名称", "身份唯一标识", "标签", "备注"),
            headerOf(export(List.of(), "选手导入模板", PlayerImportVo.class)));

        // @ExcelIgnoreUnannotated:未标注字段不出现;空 value 回退字段名
        assertEquals(List.of("id", "赛事名称", "封面图片URL", "详情", "0:筹备 1:进行中 2:结束",
                "设计稿宽度", "设计稿高度", "主题配置", "备注"),
            headerOf(export(List.of(), "赛事主", TTournamentVo.class)));
    }

    @Test
    void exportThenImportRoundTrip() {
        PlayerImportVo first = new PlayerImportVo();
        first.setName("张三");
        first.setIdCard("ID-001");
        first.setTags("bboy");
        first.setRemark("备注A");
        PlayerImportVo second = new PlayerImportVo();
        second.setName("李四");
        second.setIdCard("ID-002");
        second.setTags("bgirl");
        second.setRemark("备注B");

        byte[] xlsx = export(List.of(first, second), "选手", PlayerImportVo.class);

        List<PlayerImportVo> imported =
            ExcelUtil.importExcel(new ByteArrayInputStream(xlsx), PlayerImportVo.class);
        assertEquals(2, imported.size());
        assertEquals("张三", imported.get(0).getName());
        assertEquals("ID-001", imported.get(0).getIdCard());
        assertEquals("bboy", imported.get(0).getTags());
        assertEquals("备注A", imported.get(0).getRemark());
        assertEquals("李四", imported.get(1).getName());
    }

    @Test
    void emptyRowsAreSkippedOnImport() {
        PlayerImportVo filled = new PlayerImportVo();
        filled.setName("张三");
        filled.setIdCard("ID-001");
        PlayerImportVo empty = new PlayerImportVo();

        byte[] xlsx = export(List.of(filled, empty), "选手", PlayerImportVo.class);
        List<PlayerImportVo> imported =
            ExcelUtil.importExcel(new ByteArrayInputStream(xlsx), PlayerImportVo.class);
        assertEquals(1, imported.size());
        assertEquals("张三", imported.get(0).getName());
    }

    @Test
    void longIdOver15DigitsIsWrittenAsText() throws Exception {
        TCompetitorVo vo = new TCompetitorVo();
        vo.setId(2098838050464370691L);
        vo.setName("选手1");

        List<Row> rows = rowsOf(export(List.of(vo), "参赛单位", TCompetitorVo.class));
        Cell idCell = rows.get(1).getCell(0);

        // 超过 15 位有效数字必须写文本,否则 Excel 会把 19 位选手号舍入失真
        assertEquals(CellType.STRING, idCell.getType());
        assertEquals("2098838050464370691", idCell.getText());
    }

    @Test
    void dictFormatColumnKeepsUnmappedValue() throws Exception {
        TVisWidgetVo vo = new TVisWidgetVo();
        vo.setId(1L);
        vo.setLocked(1L);

        byte[] xlsx = export(List.of(vo), "场景控件元素", TVisWidgetVo.class);
        int column = headerOf(xlsx).indexOf("是否锁定：0-否 1-是");
        assertTrue(column >= 0, "应存在「是否锁定」列");
        // 字典表达式 "锁=定后不可编辑" 里没有 1 的对应项:保留原值,而不是被映射成空
        // (替换前的实现无匹配会返回空串,该列实际永远是空的)
        assertEquals("1", rowsOf(xlsx).get(1).getCell(column).getText());
    }
}

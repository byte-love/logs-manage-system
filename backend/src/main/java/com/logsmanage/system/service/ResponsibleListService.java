package com.logsmanage.system.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 负责人列表：从桌面 Excel 导入到 DataHub_Log 的独立集合，再供学校页下方展示。
 *
 * <p>这份表是多 sheet 结构，每个 sheet 对应一位负责人；源表字段是中文列名，
 * 入库时统一翻译成英文键，前端只认英文键渲染。
 */
@Service
public class ResponsibleListService {

    private static final String COLLECTION_NAME = "SchoolResponsibleList";
    private static final String WORKBOOK_PATH = "C:/Users/demo/Desktop\\responsible-list.xlsx";

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");
    private static final Pattern KEY_CLEANUP = Pattern.compile("[\\s\\u200B-\\u200D\\uFEFF]+");

    private static final Map<String, String> OWNER_EMAIL_MAP = new LinkedHashMap<>();

    static {
        OWNER_EMAIL_MAP.put("负责人A", "owner-a@example.com");
        OWNER_EMAIL_MAP.put("负责人B", "owner-b@example.com");
        OWNER_EMAIL_MAP.put("负责人C", "owner-c@example.com");
        OWNER_EMAIL_MAP.put("负责人D", "owner-d@example.com");
        OWNER_EMAIL_MAP.put("负责人E", "owner-e@example.com");
        OWNER_EMAIL_MAP.put("负责人F", "owner-f@example.com");
        OWNER_EMAIL_MAP.put("负责人G", "owner-g@example.com");
        OWNER_EMAIL_MAP.put("负责人H", "owner-h@example.com");
    }

    private final MongoTemplate mongoTemplate;

    public ResponsibleListService(@Qualifier("MongoTemplate") MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * 从桌面 Excel 增量导入负责人列表，写入 DataHub_Log 的独立集合。
     *
     * <p>导入策略是增量导入：把 Excel 全量解析成功后，直接批量写入新数据，
     * 不删除旧数据。对于重复的 _id（同一负责人/序号/学校的组合）会按 upsert 规则覆盖。
     * 若文件缺失或解析失败，旧数据保留不动。
     */
    public Map<String, Object> importFromDesktopWorkbook() throws IOException {
        File file = new File(WORKBOOK_PATH);
        if (!file.isFile()) {
            return fail("未找到桌面 Excel 文件：" + WORKBOOK_PATH);
        }

        List<Document> docs = new ArrayList<>();
        int sheetCount = 0;
        int rowCount = 0;

        try (Workbook workbook = WorkbookFactory.create(file)) {
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                if (sheet == null) {
                    continue;
                }
                sheetCount++;
                String sheetName = normalizeText(sheet.getSheetName());
                String ownerName = hasText(sheetName) ? sheetName : "未知负责人";
                String email = ownerEmail(ownerName);
                int displaySheetOrder = sheetIndex + 1;

                for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null || isBlankRow(row, formatter, evaluator)) {
                        continue;
                    }

                    String seqNoText = cellText(row.getCell(0), formatter, evaluator);
                    Integer seqNo = parseInteger(seqNoText);
                    if (seqNo == null) {
                        continue;
                    }

                    String region = normalizeText(cellText(row.getCell(1), formatter, evaluator));
                    String province = normalizeText(cellText(row.getCell(2), formatter, evaluator));
                    String schoolName = normalizeText(cellText(row.getCell(3), formatter, evaluator));
                    String expireDate = normalizeDate(row.getCell(4), formatter, evaluator);
                    String rowOwner = normalizeText(cellText(row.getCell(5), formatter, evaluator));
                    if (!hasText(rowOwner)) {
                        rowOwner = ownerName;
                    }
                    String rowEmail = ownerEmail(rowOwner);
                    if (!hasText(rowEmail)) {
                        rowEmail = email;
                    }

                    if (!hasText(schoolName)) {
                        continue;
                    }

                    Document doc = new Document();
                    doc.put("_id", buildId(ownerName, seqNo, schoolName));
                    doc.put("seqNo", seqNo);
                    doc.put("region", region);
                    doc.put("province", province);
                    doc.put("schoolName", schoolName);
                    doc.put("expireDate", expireDate);
                    doc.put("ownerName", hasText(rowOwner) ? rowOwner : ownerName);
                    doc.put("email", rowEmail);
                    doc.put("sheetName", ownerName);
                    doc.put("sheetOrder", displaySheetOrder);
                    doc.put("sourceRow", rowIndex + 1);
                    doc.put("importAt", new Date());
                    docs.add(doc);
                    rowCount++;
                }
            }
        }

        if (docs.isEmpty()) {
            return fail("Excel 中没有可导入的数据");
        }

        // 增量导入：不删除旧数据，用 upsert 批量写入新数据。重复的 _id 会被新数据覆盖。
        for (Document doc : docs) {
            Query query = new Query(Criteria.where("_id").is(doc.get("_id")));
            Update update = Update.fromDocument(doc);
            mongoTemplate.upsert(query, update, COLLECTION_NAME);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "负责人列表导入成功");
        result.put("sheetCount", sheetCount);
        result.put("rowCount", rowCount);
        result.put("collection", COLLECTION_NAME);
        return result;
    }

    /**
     * 分页读取负责人列表。
     *
     * <p>量级只有几百行，依然保持和学校列表一致的分页接口形状，前端接入最省事。
     */
    /**
     * 按学校名批量更新负责人信息。
     */
    public Map<String, Object> updateOwnersBySchools(List<Map<String, String>> updates) {
        if (updates == null || updates.isEmpty()) {
            return fail("更新列表为空");
        }

        int updated = 0;
        int failed = 0;

        for (Map<String, String> item : updates) {
            String schoolName = item.get("schoolName");
            String newOwnerName = item.get("ownerName");

            if (!hasText(schoolName) || !hasText(newOwnerName)) {
                failed++;
                continue;
            }

            String newEmail = ownerEmail(newOwnerName);
            if (!hasText(newEmail)) {
                failed++;
                continue;
            }

            Query query = new Query(Criteria.where("schoolName").is(schoolName.trim()));
            Update updateOp = new Update()
                    .set("ownerName", newOwnerName)
                    .set("email", newEmail);

            long modifiedCount = mongoTemplate.updateMulti(query, updateOp, COLLECTION_NAME).getModifiedCount();

            if (modifiedCount > 0) {
                updated += (int) modifiedCount;
            } else {
                failed++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "批量更新完成");
        result.put("updated", updated);
        result.put("failed", failed);
        return result;
    }

    public Map<String, Object> getPage(int pageNum, int pageSize, String keyword) {
        int safePageNum = Math.max(pageNum, 1);
        int safePageSize = Math.max(pageSize, 1);

        List<Criteria> criteriaList = new ArrayList<>();
        if (hasText(keyword)) {
            String quoted = Pattern.quote(keyword.trim());
            List<Criteria> ors = new ArrayList<>();
            for (String field : Arrays.asList("schoolName", "ownerName", "email", "region", "province")) {
                ors.add(Criteria.where(field).regex(quoted, "i"));
            }
            criteriaList.add(new Criteria().orOperator(ors.toArray(new Criteria[0])));
        }

        Query countQuery = new Query();
        applyCriteria(countQuery, criteriaList);
        long total = mongoTemplate.count(countQuery, COLLECTION_NAME);

        Query pageQuery = new Query();
        applyCriteria(pageQuery, criteriaList);
        pageQuery.fields().include("seqNo").include("region").include("province")
                .include("schoolName").include("expireDate").include("ownerName").include("email");
        pageQuery.with(Sort.by(Sort.Order.asc("sheetOrder"), Sort.Order.asc("seqNo"), Sort.Order.asc("_id")));
        pageQuery.skip((long) (safePageNum - 1) * safePageSize);
        pageQuery.limit(safePageSize);

        List<Map<String, Object>> records = new ArrayList<>();
        for (Document document : mongoTemplate.find(pageQuery, Document.class, COLLECTION_NAME)) {
            records.add(new LinkedHashMap<>(document));
        }

        long totalPages = total == 0 ? 0 : (total + safePageSize - 1) / safePageSize;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", hasText(keyword) ? "搜索成功" : "查询成功");
        result.put("records", records);
        result.put("total", total);
        result.put("pageNum", safePageNum);
        result.put("pageSize", safePageSize);
        result.put("totalPages", totalPages);
        return result;
    }

    private void applyCriteria(Query query, List<Criteria> criteriaList) {
        if (!criteriaList.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(criteriaList.toArray(new Criteria[0])));
        }
    }

    private static String buildId(String ownerName, Integer seqNo, String schoolName) {
        return cleanKey(ownerName) + "::" + seqNo + "::" + cleanKey(schoolName);
    }

    private static String ownerEmail(String ownerName) {
        return OWNER_EMAIL_MAP.get(cleanKey(ownerName));
    }

    private static boolean isBlankRow(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        for (int i = 0; i < 6; i++) {
            if (hasText(cellText(row.getCell(i), formatter, evaluator))) {
                return false;
            }
        }
        return true;
    }

    private static String cellText(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return formatDate(cell.getDateCellValue());
        }
        String value = formatter.formatCellValue(cell, evaluator);
        return value == null ? "" : value;
    }

    private static String normalizeDate(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell != null && cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return formatDate(cell.getDateCellValue());
        }
        return normalizeText(cellText(cell, formatter, evaluator));
    }

    private static String formatDate(Date date) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(date);
    }

    private static String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        return WHITESPACE_RUN.matcher(trimmed).replaceAll(" ");
    }

    private static String cleanKey(String value) {
        if (value == null) {
            return "";
        }
        return KEY_CLEANUP.matcher(value.trim()).replaceAll("");
    }

    private static Integer parseInteger(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            try {
                return (int) Math.round(Double.parseDouble(value.trim()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static Map<String, Object> fail(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        return body;
    }
}

package com.logsmanage.system.controller;

import com.logsmanage.system.dto.PageResponse;
import com.logsmanage.system.service.UserDataService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 导出账号数据 / 异常操作排行 / 日志为 Excel。
 *
 * <p>后端只能返回文件流，保存位置由前端通过浏览器的
 * showSaveFilePicker / &lt;a download&gt; 承接，后端不感知。
 */
@RestController
@RequestMapping("/api/export")
public class ExportController {

    // 导出行数上限。Excel 单表上限约 104 万行，这里取 50 万兜底
    // （防御性上限，防止极端情况把内存/临时文件撑爆；正常筛选远达不到）。
    // 大批量导出走 SXSSF 流式写 + Mongo 游标流式读，内存不随行数线性增长。
    private static final int MAX_EXPORT_ROWS = 500000;
    // 单张 Excel Sheet 的数据行上限。超量导出会滚到第 2、3…张表（每张都带表头），不截断。
    private static final int MAX_SHEET_ROWS = 500000;
    // SXSSF 内存中保留的滑动窗口行数，超出的行刷到临时文件，内存恒定
    private static final int SXSSF_WINDOW = 200;
    // 学校日志导出后台任务：完成/失败后临时文件保留多久给前端下载，超时即清理
    private static final long JOB_TTL_MS = 15 * 60 * 1000L;

    private final UserDataService userDataService;

    // 学校日志导出改成「后台任务 + 轮询进度」，避免大机构导出时浏览器长时间无反馈。
    // 任务状态/进度放内存：进程重启即丢，失败任务由前端提示重试，可接受。
    private final ConcurrentMap<String, SchoolExportJob> schoolJobs = new ConcurrentHashMap<>();
    // 同时最多跑 2 个导出；并发多于此会排队等空位
    private final ExecutorService schoolExportPool = Executors.newFixedThreadPool(2);

    public ExportController(UserDataService userDataService) {
        this.userDataService = userDataService;
    }

    @GetMapping("/account")
    public void exportAccount(
            @RequestParam(defaultValue = "false") boolean currentPage,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String userName,
            @RequestParam(required = false) String nickName,
            @RequestParam(required = false) String mail,
            @RequestParam(required = false) String institution,
            @RequestParam(required = false) String college,
            @RequestParam(required = false) String position,
            @RequestParam(required = false) String xuezhi,
            @RequestParam(required = false) String ruxueYear,
            @RequestParam(required = false) String accountType,
            @RequestParam(required = false) String accountStatus,
            @RequestParam(required = false) String applyChannel,
            HttpServletResponse response) throws Exception {
        // 只放有值的 filter key，空 map 就是无条件全量
        Map<String, String> filters = new LinkedHashMap<>();
        putIfHas(filters, "keyword", keyword);
        putIfHas(filters, "userName", userName);
        putIfHas(filters, "nickName", nickName);
        putIfHas(filters, "mail", mail);
        putIfHas(filters, "institution", institution);
        putIfHas(filters, "college", college);
        putIfHas(filters, "position", position);
        putIfHas(filters, "xuezhi", xuezhi);
        putIfHas(filters, "ruxueYear", ruxueYear);
        putIfHas(filters, "accountType", accountType);
        putIfHas(filters, "accountStatus", accountStatus);
        putIfHas(filters, "applyChannel", applyChannel);

        List<ColumnDef> columns = accountColumns();
        try {
            if (currentPage) {
                // 当前页：行数有限，直接取一页
                Map<String, Object> data = userDataService.getPage(pageNum, pageSize, filters);
                // getPage 返回 {success, message, data: PageResponse}，data 是 DTO 不是 Map
                PageResponse page = (PageResponse) data.get("data");
                List<Map<String, Object>> records = page != null ? page.getRecords() : new ArrayList<>();
                exportWorkbook(response, "账号数据", columns,
                        sink -> { records.forEach(sink); return records.size(); });
            } else {
                // 全部：Mongo 游标流式读 + SXSSF 流式写，导到几十万行也不 OOM
                exportWorkbook(response, "账号数据", columns,
                        sink -> userDataService.streamAccountRows(filters, MAX_EXPORT_ROWS, sink));
            }
        } catch (Exception e) {
            writeError(response, e);
        }
    }

    // 导出出错时回一段纯文本（前端 blob.type 非 spreadsheet 即读文本报错）。
    // 仅在尚未开始写 xlsx 字节时可用——exportWorkbook 把响应头/流放到最后，故此处安全。
    private void writeError(HttpServletResponse response, Exception e) throws java.io.IOException {
        if (response.isCommitted()) {
            return; // 已经开始写 xlsx，无法再改成错误响应
        }
        response.reset();
        response.setContentType("text/plain;charset=UTF-8");
        response.setStatus(500);
        java.io.PrintWriter w = response.getWriter();
        e.printStackTrace(w);
        w.flush();
    }

    private static void putIfHas(Map<String, String> m, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            m.put(key, value.trim());
        }
    }

    /**
     * 导出异常操作排行。口径与前端榜单严格对齐：
     *
     * <p>{@code limit} 是用户在榜单上选的「档位」（前 20/50/100/500 名，0=全部），
     * 不是分页大小。后端按同一次聚合取前 {@code limit} 名（operationCount 倒序），
     * 与前端 rankSliced 完全一致。
     *
     * <p>{@code currentPage=true} 再按 {@code pageNum/pageSize} 取当前这一页
     * （对应前端 rankData）；false 时导出整个档位（最多 5000 行兜底）。
     */
    @GetMapping("/ranking")
    public void exportRanking(
            @RequestParam(defaultValue = "false") boolean currentPage,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "50") int pageSize,
            @RequestParam(defaultValue = "0") int limit,
            @RequestParam(required = false) Integer withinDays,
            @RequestParam(required = false) String position,
            @RequestParam(required = false) String accountStatus,
            @RequestParam(required = false) String applyChannel,
            HttpServletResponse response) throws Exception {
        List<ColumnDef> columns = rankingColumns();
        try {
            // limit 直接透传：0=全部，其余=档位。前端榜单就是这么取的
            Map<String, Object> data = userDataService.getOperationRanking(
                    limit, withinDays, position, accountStatus, applyChannel, false);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> all = (List<Map<String, Object>>) data.getOrDefault("records", new ArrayList<>());
            List<Map<String, Object>> records;
            if (currentPage && pageSize > 0) {
                int start = Math.max(0, (pageNum - 1) * pageSize);
                int end = Math.min(start + pageSize, all.size());
                records = start < all.size() ? new ArrayList<>(all.subList(start, end)) : new ArrayList<>();
            } else if (all.size() > MAX_EXPORT_ROWS) {
                records = new ArrayList<>(all.subList(0, MAX_EXPORT_ROWS));
            } else {
                records = all;
            }
            exportWorkbook(response, "异常操作排行", columns,
                    sink -> { records.forEach(sink); return records.size(); });
        } catch (Exception e) {
            writeError(response, e);
        }
    }

    @GetMapping("/user-logs")
    public void exportUserLogs(
            @RequestParam String userName,
            HttpServletResponse response) throws Exception {
        List<ColumnDef> columns = userLogColumns();
        try {
            String safeUser = userName == null ? "" : userName.trim();
            if (safeUser.isEmpty()) {
                throw new IllegalArgumentException("用户名不能为空");
            }
            exportWorkbook(response, "用户操作日志", columns,
                    sink -> userDataService.streamUserLogRows(safeUser, MAX_EXPORT_ROWS, sink));
        } catch (Exception e) {
            writeError(response, e);
        }
    }

    // ===== 学校日志导出：后台任务 + 轮询进度 =====
    // 大机构几十万行，同步导出会让浏览器长时间只有一个转圈、也没有真实进度。
    // 改成：点「开始」→ 立刻返回 taskId（启动时同步 count 出总数）→ 前端每秒轮询
    // /progress 拿「已生成 X / 共 N」→ 状态 done 后 /download 取文件（xlsx 或 zip）。
    // 任务状态/临时文件都在内存：进程重启即失效；完成/失败保留 JOB_TTL_MS 供下载，
    // 下载完或超时清理。同一机构同一时间只开一个任务由前端（导出中禁用）保证。

    /** 一次后台导出任务的进度与结果。worker 线程写、查询线程并发读，靠 volatile/原子。 */
    private static final class SchoolExportJob {
        final String taskId;
        final long total;                          // 启动时精确 count
        final AtomicLong processed = new AtomicLong(); // 已写入的行数
        final long createdAt = System.currentTimeMillis();
        volatile String status = "running";        // running | done | error | cancelled
        volatile String error;
        volatile boolean cancelRequested;
        volatile List<Path> files;
        SchoolExportJob(String taskId, long total) {
            this.taskId = taskId;
            this.total = total;
        }
    }

    private static final AtomicLong TASK_SEQ = new AtomicLong();

    /** 发起一次导出：先 count 总数（秒级），再立刻返回 taskId 让前端轮询。 */
    @PostMapping("/school-logs/start")
    public ResponseEntity<Map<String, Object>> startSchoolExport(
            @RequestParam String institution,
            @RequestParam(required = false) String dateStart,
            @RequestParam(required = false) String dateEnd,
            @RequestParam(defaultValue = "true") boolean excludeAnonymous) {
        String safe = institution == null ? "" : institution.trim();
        if (safe.isEmpty()) {
            return ResponseEntity.badRequest().body(fail("机构名称不能为空"));
        }
        sweepSchoolJobs();
        long total;
        try {
            total = userDataService.countSchoolLogRows(safe, dateStart, dateEnd, excludeAnonymous);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(fail("统计导出行数失败：" + e.getMessage()));
        }
        String taskId = "exp-" + System.currentTimeMillis() + "-" + TASK_SEQ.incrementAndGet();
        SchoolExportJob job = new SchoolExportJob(taskId, total);
        schoolJobs.put(taskId, job);
        schoolExportPool.submit(() -> runSchoolExport(job, safe, dateStart, dateEnd, excludeAnonymous));
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("taskId", taskId);
        body.put("status", "running");
        body.put("total", total);
        return ResponseEntity.ok(body);
    }

    /** 后台线程里真正生成文件：滚动写临时 xlsx、逐行推进进度、支持取消。 */
    private void runSchoolExport(SchoolExportJob job, String institution,
                                 String dateStart, String dateEnd, boolean excludeAnonymous) {
        // lambda 里要用 writer，又不能 effectively-final 之外再赋值，用单元素数组持有
        final SplitWriter[] holder = new SplitWriter[1];
        try {
            holder[0] = new SplitWriter("机构操作日志", schoolLogColumns(), true);
            // 「学院」列由 UserDataService.streamSchoolLogRows 逐行补好（与页面同一套取值口径），
            // 这里只负责把列定义出去
            userDataService.streamSchoolLogRows(institution, dateStart, dateEnd, excludeAnonymous,
                    Integer.MAX_VALUE, rec -> {
                        if (job.cancelRequested) {
                            throw new CancellationException("导出已取消");
                        }
                        holder[0].accept(rec);
                        job.processed.incrementAndGet();
                    });
            job.files = holder[0].finish();
            job.status = "done";
        } catch (CancellationException e) {
            job.status = "cancelled";
        } catch (Throwable t) {
            job.status = "error";
            job.error = String.valueOf(t.getMessage());
            t.printStackTrace();
        } finally {
            SplitWriter w = holder[0];
            if (w != null && ("error".equals(job.status) || "cancelled".equals(job.status))) {
                w.cleanup();
            }
        }
    }

    /** 轮询进度：已生成 / 总数。任务不存在返回 404，前端据此结束轮询。 */
    @GetMapping("/school-logs/progress")
    public ResponseEntity<Map<String, Object>> schoolExportProgress(@RequestParam String taskId) {
        sweepSchoolJobs();
        SchoolExportJob job = schoolJobs.get(taskId);
        if (job == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(fail("导出任务不存在或已过期"));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("taskId", taskId);
        body.put("status", job.status);
        body.put("processed", job.processed.get());
        body.put("total", job.total);
        if (job.error != null) {
            body.put("error", job.error);
        }
        return ResponseEntity.ok(body);
    }

    /** 前端展示进度时给的取消出口（真能停：worker 每行检查标志后中断，删掉半成品）。 */
    @PostMapping("/school-logs/cancel")
    public ResponseEntity<Map<String, Object>> schoolExportCancel(@RequestParam String taskId) {
        SchoolExportJob job = schoolJobs.get(taskId);
        if (job == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(fail("导出任务不存在或已过期"));
        }
        job.cancelRequested = true;
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("cancelled", true);
        return ResponseEntity.ok(body);
    }

    /** 任务跑完取文件：单文件 xlsx、多文件 zip。下载完即删临时文件并移除任务。 */
    @GetMapping("/school-logs/download")
    public void schoolExportDownload(@RequestParam String taskId, HttpServletResponse response) throws IOException {
        SchoolExportJob job = schoolJobs.get(taskId);
        if (job == null) {
            writeJsonError(response, 404, "导出任务不存在或已过期");
            return;
        }
        if (!"done".equals(job.status)) {
            writeJsonError(response, 409,
                    "error".equals(job.status) ? "导出失败：" + job.error : "导出尚未完成");
            return;
        }
        List<Path> files = job.files;
        if (files == null || files.isEmpty()) {
            writeJsonError(response, 500, "导出文件为空");
            return;
        }
        String base = "机构操作日志_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date());
        try {
            if (files.size() == 1) {
                respondFile(response, files.get(0), base + ".xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            } else {
                respondZip(response, files, base, "机构操作日志");
            }
        } finally {
            deleteFiles(files);
            schoolJobs.remove(taskId, job);
        }
    }

    /** 清理过期任务（15 分钟没人下载就删临时文件），防止任务/文件堆积。 */
    private void sweepSchoolJobs() {
        long now = System.currentTimeMillis();
        for (SchoolExportJob job : schoolJobs.values()) {
            if (now - job.createdAt > JOB_TTL_MS) {
                schoolJobs.remove(job.taskId, job);
                deleteFiles(job.files);
            }
        }
    }

    private static void deleteFiles(List<Path> files) {
        if (files == null) {
            return;
        }
        for (Path p : files) {
            try { Files.deleteIfExists(p); } catch (IOException ignored) { }
        }
    }

    /** 同步阶段/下载前出错时的 JSON 错误响应（尚未提交响应字节时可用）。 */
    private void writeJsonError(HttpServletResponse response, int status, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.reset();
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"" + message.replace("\"", "'") + "\"}");
        response.getWriter().flush();
    }

    private static Map<String, Object> fail(String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", message);
        return body;
    }

    /** 单文件响应：把已生成的临时 xlsx 原样吐给浏览器。 */
    private void respondFile(HttpServletResponse response, Path file,
                             String downloadName, String contentType) throws IOException {
        response.setContentType(contentType);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(encodeDownloadName(downloadName)).build().toString());
        try (OutputStream out = response.getOutputStream();
             FileInputStream in = new FileInputStream(file.toFile())) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
        }
    }

    /**
     * Content-Disposition 里的下载文件名必须是 ASCII —— Tomcat 9 对非 ISO-8859-1 的
     * 头值会直接抛「Unicode 字符无法编码」并剥掉该头。中文文件名先按 UTF-8 URL 编码
     *（与 {@link #exportWorkbook} 同一套写法），浏览器会还原成可读文件名。
     */
    private static String encodeDownloadName(String raw) {
        try {
            return URLEncoder.encode(raw, StandardCharsets.UTF_8.name()).replaceAll("\\+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return raw; // UTF-8 必支持，走到这里纯属防御
        }
    }

    /** 多文件响应：把多个临时 xlsx 流式打成 zip。 */
    private void respondZip(HttpServletResponse response, List<Path> files,                            String base, String sheetTitle) throws IOException {
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(encodeDownloadName(base + ".zip")).build().toString());
        try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream())) {
            for (int i = 0; i < files.size(); i++) {
                String entryName = sheetTitle + "_" + (i + 1) + ".xlsx";
                zos.putNextEntry(new ZipEntry(entryName));
                byte[] buf = new byte[65536];
                int n;
                try (FileInputStream in = new FileInputStream(files.get(i).toFile())) {
                    while ((n = in.read(buf)) >= 0) {
                        zos.write(buf, 0, n);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    /**
     * 滚动写临时 xlsx 的辅助。每 {@link #MAX_SHEET_ROWS} 行落盘一个独立 workbook，
     * 全程只保留一个 workbook 在内存（SXSSF 窗口），磁盘上的临时文件最后统一打包。
     */
    private final class SplitWriter {
        private final String title;
        private final List<ColumnDef> columns;
        private final boolean dualHeader;
        private final List<Path> files = new ArrayList<>();
        private SXSSFWorkbook wb;
        private Sheet sheet;
        private CellStyle text;
        private int rowIdx;
        private int dataRows;

        SplitWriter(String title, List<ColumnDef> columns, boolean dualHeader) {
            this.title = title;
            this.columns = columns;
            this.dualHeader = dualHeader;
            startWorkbook();
        }

        private void startWorkbook() {
            wb = new SXSSFWorkbook(SXSSF_WINDOW);
            wb.setCompressTempFiles(true);
            CellStyle header = createHeaderStyle(wb);
            text = createTextStyle(wb);
            sheet = createSheet(wb, title, 1, columns, dualHeader, header);
            rowIdx = dualHeader ? 2 : 1;
            dataRows = 0;
        }

        private void flushCurrent() throws IOException {
            if (wb == null) {
                return;
            }
            Path tmp = Files.createTempFile("datahub-export-", ".xlsx");
            // 先登记再写：中途失败也能被 cleanup() 删掉，不会留孤儿文件
            files.add(tmp);
            try (FileOutputStream out = new FileOutputStream(tmp.toFile())) {
                wb.write(out);
            }
            wb.dispose();
            wb = null;
        }

        /** 被 produce 的 sink 逐行调用；满 50 万行就落盘当前文件、起下一个。 */
        void accept(Map<String, Object> record) {
            try {
                if (dataRows >= MAX_SHEET_ROWS) {
                    flushCurrent();
                    startWorkbook();
                }
                Row row = sheet.createRow(rowIdx++);
                dataRows++;
                for (int i = 0; i < columns.size(); i++) {
                    Cell cell = row.createCell(i);
                    cell.setCellStyle(text);
                    Object value = displayValue(columns.get(i).key, record.get(columns.get(i).key));
                    cell.setCellValue(value == null ? "" : String.valueOf(value));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** 收尾落盘最后一个文件，返回全部临时文件。 */
        List<Path> finish() throws IOException {
            flushCurrent();
            return files;
        }

        /** 出错/取消时清掉已生成的临时文件（当前未落盘的那个 workbook 直接不写）。 */
        void cleanup() {
            if (wb != null) {
                try { wb.dispose(); } catch (Throwable ignored) { }
                wb = null;
            }
            for (Path p : files) {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            }
            files.clear();
        }
    }

    /** 逐行推送导出数据的源：把每条原始记录喂给 sink，返回总行数。 */
    @FunctionalInterface
    private interface RowSource {
        long produce(Consumer<Map<String, Object>> sink) throws Exception;
    }

    /**
     * 流式写 xlsx。用 SXSSFWorkbook（内存只留 {@link #SXSSF_WINDOW} 行滑动窗口、
     * 其余刷临时文件），配合数据源的游标流式产出，导几十万行内存也恒定。
     *
     * <p>响应头/输出流放到所有行产出之后再取：若产出中途抛错，响应尚未提交，
     * 上层 {@link #writeError} 还能改成 500 文本；一旦开始写字节就无法回头。
     */
    private void exportWorkbook(HttpServletResponse response,
                                String title,
                                List<ColumnDef> columns,
                                RowSource source) throws Exception {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(SXSSF_WINDOW)) {
            wb.setCompressTempFiles(true);
            Sheet sheet = wb.createSheet(title);
            CellStyle header = createHeaderStyle(wb);
            CellStyle text = createTextStyle(wb);

            Row head = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = head.createCell(i);
                cell.setCellValue(columns.get(i).title);
                cell.setCellStyle(header);
            }
            // SXSSF 不支持产出后 autoSizeColumn（历史行已刷盘），改用固定列宽
            for (int i = 0; i < columns.size(); i++) {
                sheet.setColumnWidth(i, columns.get(i).widthChars * 256);
            }

            int[] rowIdx = {1};
            source.produce(record -> {
                Row row = sheet.createRow(rowIdx[0]++);
                for (int i = 0; i < columns.size(); i++) {
                    Cell cell = row.createCell(i);
                    cell.setCellStyle(text);
                    Object value = displayValue(columns.get(i).key, record.get(columns.get(i).key));
                    cell.setCellValue(value == null ? "" : String.valueOf(value));
                }
            });

            // 所有行已进 SXSSF（内存窗口 + 临时文件），现在才提交响应
            String rawFileName = title + "_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date()) + ".xlsx";
            String fileName = URLEncoder.encode(rawFileName, StandardCharsets.UTF_8.name())
                    .replaceAll("\\+", "%20");
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(fileName).build().toString());
            try (OutputStream out = response.getOutputStream()) {
                wb.write(out);
            }
            wb.dispose(); // 删除 SXSSF 临时文件
        }
    }

    /** 建一张表并写表头，返回该表。第 2 张起 sheet 名带序号（Excel sheet 名不能重）。 */
    private Sheet createSheet(Workbook wb, String title, int sheetNo,
                              List<ColumnDef> columns, boolean dualHeader, CellStyle header) {
        String sheetName = sheetNo <= 1 ? title : title + sheetNo;
        if (sheetName.length() > 31) {
            sheetName = sheetName.substring(0, 31);
        }
        Sheet sheet = wb.createSheet(sheetName);
        // SXSSF 不支持事后 autoSizeColumn（历史行已刷盘），用固定列宽
        for (int i = 0; i < columns.size(); i++) {
            sheet.setColumnWidth(i, columns.get(i).widthChars * 256);
        }
        Row head = sheet.createRow(0);
        for (int i = 0; i < columns.size(); i++) {
            Cell cell = head.createCell(i);
            // 双表头：第 1 行英文字段名；单表头沿用旧逻辑直接写中文
            cell.setCellValue(dualHeader ? columns.get(i).key : columns.get(i).title);
            cell.setCellStyle(header);
        }
        if (dualHeader) {
            Row head2 = sheet.createRow(1);
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = head2.createCell(i);
                cell.setCellValue(columns.get(i).title);
                cell.setCellStyle(header);
            }
        }
        return sheet;
    }

    // 枚举值 -> 中文，日期 -> 可读格式。与前端展示口径保持一致，避免导出里出现
    // formal/trial/normal 这类原始值或 ISO 时间戳
    private static final Map<String, String> ACCOUNT_TYPE_CN = new HashMap<String, String>() {{
        put("formal", "正式");
        put("trial", "试用");
    }};
    private static final Map<String, String> ACCOUNT_STATUS_CN = new HashMap<String, String>() {{
        put("normal", "正常");
        put("expired", "到期");
        put("upgraded", "升级");
        put("disabled", "禁用");
    }};
    private static final Map<String, String> APPLY_CHANNEL_CN = new HashMap<String, String>() {{
        put("apply", "申请");
        put("bulk", "批量");
    }};
    private Object displayValue(String key, Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Date) {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format((Date) value);
        }
        String s = String.valueOf(value);
        switch (key) {
            case "AccountType":
                return ACCOUNT_TYPE_CN.getOrDefault(s, s);
            case "AccountStatus":
                return ACCOUNT_STATUS_CN.getOrDefault(s, s);
            case "ApplyChannel":
                return APPLY_CHANNEL_CN.getOrDefault(s, s);
            default:
                return value;
        }
    }

    private CellStyle createHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.BLUE_GREY.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    private CellStyle createTextStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setAlignment(HorizontalAlignment.LEFT);
        return style;
    }

    private List<ColumnDef> accountColumns() {
        return Arrays.asList(
                new ColumnDef("UserName", "用户名"),
                new ColumnDef("NickName", "昵称"),
                new ColumnDef("Mail", "邮箱"),
                new ColumnDef("Institution", "机构"),
                new ColumnDef("College", "学院"),
                new ColumnDef("Position", "身份"),
                new ColumnDef("xuezhi", "学制"),
                new ColumnDef("ruxueYear", "入学年份"),
                new ColumnDef("AccountType", "账号类型"),
                new ColumnDef("AccountStatus", "账号状态"),
                new ColumnDef("ApplyChannel", "申请通道"),
                new ColumnDef("AgreeFile", "使用协议"),
                new ColumnDef("Workfile", "注册资料")
        );
    }

    private List<ColumnDef> rankingColumns() {
        return Arrays.asList(
                new ColumnDef("rank", "排名"),
                new ColumnDef("UserName", "用户名"),
                new ColumnDef("NickName", "姓名"),
                new ColumnDef("Institution", "学校"),
                new ColumnDef("Position", "身份"),
                new ColumnDef("AccountStatus", "状态"),
                new ColumnDef("ApplyChannel", "申请通道"),
                new ColumnDef("operationCount", "操作次数")
        );
    }

    private List<ColumnDef> schoolLogColumns() {
        return Arrays.asList(
                new ColumnDef("NickName", "姓名", 18),
                new ColumnDef("Institution", "机构", 34),
                // 学院：日志行本身没有这个字段，导出时按 UserName 从 User_Data 取，
                // 学校共用账号（schooluser）留空 —— 见 UserCollegeService
                new ColumnDef("College", "学院", 24),
                new ColumnDef("Mail", "邮箱", 32),
                new ColumnDef("UserName", "用户名", 32),
                new ColumnDef("O_srcIP", "来源IP", 18),
                new ColumnDef("O_system", "系统", 16),
                new ColumnDef("O_database", "数据库", 20),
                new ColumnDef("O_table", "数据表", 42),
                new ColumnDef("tableFieldCount", "表字段数", 16),
                new ColumnDef("O_Operation_type", "操作类型", 20),
                new ColumnDef("O_language", "语言版本", 12),
                new ColumnDef("O_date", "操作时间", 24)
        );
    }

    private List<ColumnDef> userLogColumns() {
        return Arrays.asList(
                new ColumnDef("UserName", "用户名"),
                new ColumnDef("NickName", "姓名"),
                new ColumnDef("Institution", "机构"),
                new ColumnDef("Mail", "邮箱"),
                new ColumnDef("O_srcIP", "来源IP"),
                new ColumnDef("O_city", "城市"),
                new ColumnDef("O_system", "系统"),
                new ColumnDef("O_database", "数据库"),
                new ColumnDef("O_table", "数据表"),
                new ColumnDef("O_Operation_type", "操作类型"),
                new ColumnDef("O_language", "语言版本"),
                new ColumnDef("O_date", "操作时间")
        );
    }

    private static final class ColumnDef {
        final String key;
        final String title;
        // 固定列宽（字符数）。SXSSF 流式写不能事后 autoSize，故预设。
        final int widthChars;

        ColumnDef(String key, String title) {
            this(key, title, defaultWidth(key));
        }

        ColumnDef(String key, String title, int widthChars) {
            this.key = key;
            this.title = title;
            this.widthChars = widthChars;
        }

        // 按字段给个合理默认宽度：邮箱/机构/文件链接偏宽，其余适中
        private static int defaultWidth(String key) {
            switch (key) {
                case "AgreeFile":
                case "Workfile":
                    return 48;
                case "Mail":
                case "Institution":
                    return 28;
                case "NickName":
                case "College":
                    return 18;
                default:
                    return 14;
            }
        }
    }
}

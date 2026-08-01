package com.geek.codeguard.scan.controller;

import com.geek.codeguard.common.result.Result;
import com.geek.codeguard.scan.model.ScanFinding;
import com.geek.codeguard.scan.model.ScanRecord;
import com.geek.codeguard.scan.service.DocxReportBuilder;
import com.geek.codeguard.scan.service.PdfReportBuilder;
import com.geek.codeguard.scan.service.ReportService;
import com.geek.codeguard.scan.service.ScanService;
import com.geek.codeguard.scan.service.XlsxReportBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 扫描报告导出：html（预览/打印 PDF）/ pdf / word / excel / markdown / json
 */
@RestController
@RequestMapping("/api/scans/{id}/report")
public class ReportController {

    private final ScanService scanService;
    private final ReportService reportService;
    private final PdfReportBuilder pdfBuilder;
    private final DocxReportBuilder docxBuilder;
    private final XlsxReportBuilder xlsxBuilder;

    public ReportController(ScanService scanService, ReportService reportService,
                            PdfReportBuilder pdfBuilder, DocxReportBuilder docxBuilder,
                            XlsxReportBuilder xlsxBuilder) {
        this.scanService = scanService;
        this.reportService = reportService;
        this.pdfBuilder = pdfBuilder;
        this.docxBuilder = docxBuilder;
        this.xlsxBuilder = xlsxBuilder;
    }

    @GetMapping
    public Mono<ResponseEntity<byte[]>> report(@PathVariable String id,
                                               @RequestParam(defaultValue = "html") String format) {
        return Mono.fromCallable(() -> {
            ScanRecord scan = scanService.getScan(id);
            List<ScanFinding> findings = scanService.getFindings(id, null, null, null, null);
            byte[] body;
            String fileName;
            MediaType mediaType;
            boolean inline = false;
            switch (format.toLowerCase()) {
                case "markdown", "md" -> {
                    body = reportService.buildMarkdown(scan, findings).getBytes(StandardCharsets.UTF_8);
                    fileName = "codeguard-report-" + scan.getProjectName() + ".md";
                    mediaType = new MediaType("text", "markdown", StandardCharsets.UTF_8);
                }
                case "json" -> {
                    body = reportService.buildJson(scan, findings);
                    fileName = "codeguard-report-" + scan.getProjectName() + ".json";
                    mediaType = MediaType.APPLICATION_JSON;
                }
                case "pdf" -> {
                    body = pdfBuilder.build(scan, findings);
                    fileName = "codeguard-report-" + scan.getProjectName() + ".pdf";
                    mediaType = MediaType.APPLICATION_PDF;
                }
                case "word", "docx", "doc" -> {
                    body = docxBuilder.build(scan, findings);
                    fileName = "codeguard-report-" + scan.getProjectName() + ".docx";
                    mediaType = MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
                }
                case "excel", "xlsx", "xls" -> {
                    body = xlsxBuilder.build(scan, findings);
                    fileName = "codeguard-report-" + scan.getProjectName() + ".xlsx";
                    mediaType = MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                }
                default -> {
                    body = reportService.buildHtml(scan, findings).getBytes(StandardCharsets.UTF_8);
                    fileName = "codeguard-report-" + scan.getProjectName() + ".html";
                    mediaType = MediaType.TEXT_HTML;
                    inline = true;
                }
            }
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            (inline ? "inline" : "attachment") + "; filename*=UTF-8''"
                                    + URLEncoder.encode(fileName, StandardCharsets.UTF_8))
                    .body(body);
        });
    }

    @GetMapping("/available")
    public Mono<Result<Boolean>> available(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            ScanRecord scan = scanService.getScan(id);
            return Result.success("COMPLETED".equals(scan.getStatus()));
        });
    }
}

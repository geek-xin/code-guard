package com.geek.codeguard.mail;

import com.geek.codeguard.project.model.Project;
import com.geek.codeguard.scan.model.ScanFinding;
import com.geek.codeguard.scan.model.ScanRecord;
import com.geek.codeguard.scan.service.PdfReportBuilder;
import com.geek.codeguard.scan.service.ReportService;
import com.geek.codeguard.settings.model.Settings;
import com.geek.codeguard.settings.service.SettingsService;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import org.springframework.core.io.ByteArrayResource;

/**
 * 扫描报告邮件推送：动态读取全局 SMTP 配置（settings.json），
 * 支持向多个收件人同时发送（HTML 摘要正文 + PDF 报告附件）。
 */
@Service
@Slf4j
public class MailService {

    private final SettingsService settingsService;
    private final ReportService reportService;
    private final PdfReportBuilder pdfBuilder;

    public MailService(SettingsService settingsService, ReportService reportService, PdfReportBuilder pdfBuilder) {
        this.settingsService = settingsService;
        this.reportService = reportService;
        this.pdfBuilder = pdfBuilder;
    }

    public boolean isReady() {
        return settingsService.smtpReady();
    }

    /** 扫描完成后推送报告：项目邮箱优先，未填则使用 SMTP 默认收件地址 */
    public void sendScanReport(Project project, ScanRecord scan, List<ScanFinding> findings) {
        if (!project.isEmailNotify()) {
            return;
        }
        Settings.Smtp smtp = settingsService.smtp();
        List<String> emails = project.getEmails() != null && !project.getEmails().isEmpty()
                ? project.getEmails()
                : smtp != null && smtp.getDefaultRecipients() != null ? smtp.getDefaultRecipients() : List.of();
        if (emails.isEmpty()) {
            log.warn("邮件通知未发送：项目未配置邮箱且 SMTP 未设置默认收件邮箱");
            return;
        }
        if (!isReady()) {
            log.warn("邮件通知未启用：SMTP 未配置完整（请在「设置」中配置）");
            return;
        }
        try {
            String html = reportService.buildHtml(scan, findings);
            byte[] pdf = pdfBuilder.build(scan, findings);
            String from = (smtp.getFrom() == null || smtp.getFrom().isBlank()) ? smtp.getUsername() : smtp.getFrom();
            String subject = buildSubject(scan);

            JavaMailSender sender = buildSender(smtp);
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(from));
            helper.setSubject(subject);
            helper.setText(html, true);
            helper.addAttachment("codeguard-report-" + scan.getProjectName() + ".pdf",
                    new ByteArrayResource(pdf), "application/pdf");
            for (String email : emails) {
                String e = email.trim();
                if (!e.isBlank() && e.contains("@")) {
                    helper.addTo(e);
                }
            }
            sender.send(msg);
            log.info("扫描报告邮件已发送：{} -> {} 个收件人", scan.getProjectName(), emails.size());
        } catch (Exception e) {
            log.error("发送扫描报告邮件失败 {}: {}", project.getName(), e.getMessage());
        }
    }

    private String buildSubject(ScanRecord scan) {
        var s = scan.getSummary() == null ? java.util.Map.<String, Object>of() : scan.getSummary();
        return String.format("[code-guard] 扫描完成：%s - 发现 %s 个问题（严重 %s / 高危 %s）",
                scan.getProjectName(), s.get("total"), s.get("critical"), s.get("high"));
    }

    private JavaMailSender buildSender(Settings.Smtp smtp) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(smtp.getHost());
        sender.setPort(smtp.getPort() == null ? 465 : smtp.getPort());
        sender.setUsername(smtp.getUsername());
        sender.setPassword(smtp.getPassword());
        boolean ssl = smtp.getSsl() == null || smtp.getSsl();
        if (ssl) {
            sender.setProtocol("smtps");
        }
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        if (ssl) {
            props.put("mail.smtps.auth", "true");
            props.put("mail.smtp.ssl.enable", "true");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
        }
        props.put("mail.smtp.connectiontimeout", 15000);
        props.put("mail.smtp.timeout", 15000);
        return sender;
    }
}

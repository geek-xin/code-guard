# CodeGuard Implementation Plan

**Goal:** Build a full-stack code security analysis platform (`SAST + SCA + Code Review Agent`) with
GitHub/GitLab/local-directory source access, real-time SSE scan progress, offline + OSV vulnerability
matching, AI review, and six-format report export.

**Architecture:** Spring Boot WebFlux backend with JSON-backed config store (`config/`), JGit clone,
30-rule multi-language SAST engine, SCA pipeline with offline DB + OSV.dev caching, streaming Review
Agent, and a React 19/Vite/Tailwind card-style admin console modeled after `web-sim`.

**Tech Stack:** Java 21, Spring Boot 3.5.2, WebFlux, Reactor Netty, JGit 6.10, OpenPDF 1.3.43,
Jackson, Maven, JUnit 5, React 19, Vite 7, TypeScript, Tailwind CSS, Radix UI, lucide-react, sonner.

---

## File Structure

### Backend (`src/main/java/com/geek/codeguard`)

- `Application.java` — Spring Boot entry point.
- `auth/` — local register/login, PBKDF2 hashing, token service, GitHub/GitLab OAuth, user store.
- `common/` — unified `Result`, `ErrorCodeEnum`, `BusinessException`, global exception handler, constants.
- `config/` — `CodeGuardProperties` (typed config), `JsonStore` (JSON file I/O), `AuthWebFilter` (token auth).
- `project/` — project CRUD, JGit clone/sync (`GitService`), scheduled sync, directory picker API.
- `group/` — group CRUD and assignment.
- `sast/` — `SastRuleEngine` (30 rules × 8 languages), `SastRule` model, `sast-rules.json`.
- `sca/` — dependency parsers (npm/Maven/PyPI/Go/RubyGems/Packagist), OSV client with rate limit
  and cache, offline vuln DB, version range matcher, DB update service, vuln DB controller.
- `agent/` — `ReviewAgentService` (OpenAI-compatible streaming review, Markdown report).
- `scan/` — scan orchestration (`ScanService`), SSE progress (`ScanProgressListener`), finding model,
  report builders (PDF/Word/Excel), scheduled scans, project file scanner.
- `mail/` — SMTP report push (`MailService`).
- `github/` — one-click GitHub Issue submission (`GitHubIssueService`).
- `settings/` — global settings (agent / SMTP / OAuth) with hot reload.
- `web/` — home redirect, health check, dashboard stats.

### Frontend (`frontend/src`)

- `features/auth/LoginPage.tsx` — local login + OAuth + remember-me.
- `features/dashboard/DashboardPage.tsx` + `TrendChart.tsx` — overview stats and 14-day trend.
- `features/projects/` — project cards, form dialog, group/tag management.
- `features/scans/` — scan list, scan detail with live SSE progress, findings filter, AI review tab.
- `features/vulndb/VulnDbPage.tsx` — offline DB status and manual update.
- `features/settings/SettingsPage.tsx` — Agent / SMTP / OAuth configuration.
- `features/feedback/FeedbackDialog.tsx` — one-click GitHub Issue feedback.
- `components/` — `Layout`, `DirectoryPicker`, `MarkdownView`, Radix UI primitives.

---

## Implementation Phases

- [x] **P0 脚手架**：Spring Boot WebFlux + Maven + React/Vite/Tailwind；`Result`/异常体系；首页跳转与健康检查。
- [x] **P1 认证**：本地注册/登录（PBKDF2）、Token 签发与过滤器、GitHub/GitLab OAuth、记住我。
- [x] **P2 项目管理**：GitHub/GitLab/LOCAL 三种接入、JGit 克隆与定时同步、目录选择、别名/标签/分组。
- [x] **P3 SAST**：30 条内置规则（16 类漏洞、8 语言）、外部规则扩展、文件级并行扫描与取消。
- [x] **P4 SCA**：六类清单解析、离线漏洞库（240 条）+ OSV 在线精确匹配（7 天缓存）、版本区间匹配。
- [x] **P5 扫描编排**：CLONE→DETECT→SCA→SAST→AGENT 流水线、SSE 实时进度与漏洞流、停止扫描、定时扫描。
- [x] **P6 Review Agent**：OpenAI 兼容流式审查、实时思考流、Markdown 报告、项目级开关。
- [x] **P7 报告**：PDF（中文字体嵌入）/ Word / Excel / HTML / Markdown / JSON 六格式导出。
- [x] **P8 增强**：漏洞库定时更新（03:30 + 手动）、SMTP 邮件推送、扫描结果一键提交 GitHub Issue、
  总览趋势图、扫描记录按项目过滤、问题反馈入口。
- [x] **P9 收尾**：代码清理（移除冗余字段/方法）、README 与设计文档、截图、MIT 许可证、发布 0.1.0。

## Verification

```bash
# 后端测试 / 打包
mvn test
mvn package

# 前端类型检查与构建
cd frontend && npm run typecheck && npm run build && cd ..

# 一键分发
./scripts/build-dist.sh --with-tests
```

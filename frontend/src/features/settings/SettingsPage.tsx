import { useEffect, useState } from 'react';
import { Sparkles, Save, Loader2, KeyRound, Github, Gitlab, ShieldCheck, RefreshCw, Mail, PlugZap, GitBranch, CircleCheck, CircleX } from 'lucide-react';
import { api, SettingsView } from '@/lib/api';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { toast } from 'sonner';
import { cn } from '@/lib/utils';

export default function SettingsPage() {
  const [view, setView] = useState<SettingsView | null>(null);
  const [enabled, setEnabled] = useState(true);
  const [baseUrl, setBaseUrl] = useState('');
  const [model, setModel] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<{ ok: boolean; latencyMs?: number; model?: string; reply?: string; error?: string } | null>(null);
  // SMTP
  const [smtpEnabled, setSmtpEnabled] = useState(false);
  const [smtpHost, setSmtpHost] = useState('');
  const [smtpPort, setSmtpPort] = useState(465);
  const [smtpUsername, setSmtpUsername] = useState('');
  const [smtpPassword, setSmtpPassword] = useState('');
  const [smtpFrom, setSmtpFrom] = useState('');
  const [smtpSsl, setSmtpSsl] = useState(true);
  const [smtpRecipients, setSmtpRecipients] = useState('');
  // OAuth
  const [ghClientId, setGhClientId] = useState('');
  const [ghSecret, setGhSecret] = useState('');
  const [ghRedirect, setGhRedirect] = useState('http://localhost:9997/api/auth/github/callback');
  const [glClientId, setGlClientId] = useState('');
  const [glSecret, setGlSecret] = useState('');
  const [glRedirect, setGlRedirect] = useState('http://localhost:9997/api/auth/gitlab/callback');
  const [glBaseUrl, setGlBaseUrl] = useState('https://gitlab.com');
  // Git 访问令牌
  const [gitGithubToken, setGitGithubToken] = useState('');
  const [gitGitlabToken, setGitGitlabToken] = useState('');
  const [gitGitlabUrl, setGitGitlabUrl] = useState('https://gitlab.com');
  const [gitTesting, setGitTesting] = useState<'GITHUB' | 'GITLAB' | null>(null);
  const [gitTestResult, setGitTestResult] = useState<{ source: string; ok: boolean; latencyMs?: number; user?: string; error?: string } | null>(null);

  const load = () => {
    api.getSettings().then((s) => {
      setView(s);
      setEnabled(s.agent.enabled);
      setBaseUrl(s.agent.baseUrl ?? '');
      setModel(s.agent.model ?? '');
      setApiKey('');
      setSmtpEnabled(s.smtp.enabled);
      setSmtpHost(s.smtp.host ?? '');
      setSmtpPort(s.smtp.port ?? 465);
      setSmtpUsername(s.smtp.username ?? '');
      setSmtpPassword('');
      setSmtpFrom(s.smtp.from ?? '');
      setSmtpSsl(s.smtp.ssl);
      setSmtpRecipients((s.smtp.defaultRecipients ?? []).join(', '));
      setGhClientId('');
      setGhSecret('');
      setGhRedirect(s.oauth.githubRedirectUri || 'http://localhost:9997/api/auth/github/callback');
      setGlClientId('');
      setGlSecret('');
      setGlRedirect(s.oauth.gitlabRedirectUri || 'http://localhost:9997/api/auth/gitlab/callback');
      setGlBaseUrl(s.oauth.gitlabBaseUrl || 'https://gitlab.com');
      setGitGithubToken('');
      setGitGitlabToken('');
      setGitGitlabUrl(s.git?.gitlabUrl || 'https://gitlab.com');
      setGitTestResult(null);
    }).catch(() => {});
  };

  useEffect(() => {
    load();
  }, []);

  const test = async () => {
    setTesting(true);
    setTestResult(null);
    try {
      const res = await api.testAgent({
        enabled,
        baseUrl,
        model,
        apiKey: apiKey.trim() || undefined,
      });
      setTestResult(res);
    } catch (e: any) {
      setTestResult({ ok: false, error: e?.message ?? '测试失败' });
    } finally {
      setTesting(false);
    }
  };

  const save = async () => {
    setSaving(true);
    try {
      const payload = {
        agent: {
          enabled,
          baseUrl,
          model,
          apiKey: apiKey.trim() || undefined,
        },
        smtp: {
          enabled: smtpEnabled,
          host: smtpHost.trim() || undefined,
          port: smtpPort || undefined,
          username: smtpUsername.trim() || undefined,
          password: smtpPassword.trim() || undefined,
          from: smtpFrom.trim() || undefined,
          ssl: smtpSsl,
          defaultRecipients: smtpRecipients ? smtpRecipients.split(/[,，;\n]/).map((e) => e.trim()).filter(Boolean) : undefined,
        },
        oauth: {
          githubClientId: ghClientId.trim() || undefined,
          githubClientSecret: ghSecret.trim() || undefined,
          githubRedirectUri: ghRedirect.trim() || undefined,
          gitlabClientId: glClientId.trim() || undefined,
          gitlabClientSecret: glSecret.trim() || undefined,
          gitlabRedirectUri: glRedirect.trim() || undefined,
          gitlabBaseUrl: glBaseUrl.trim() || undefined,
        },
        git: {
          githubToken: gitGithubToken.trim() || undefined,
          gitlabToken: gitGitlabToken.trim() || undefined,
          gitlabUrl: gitGitlabUrl.trim() || undefined,
        },
      };
      const updated = await api.updateSettings(payload);
      setView(updated);
      setApiKey('');
      setGhSecret('');
      setGlSecret('');
      setGitGithubToken('');
      setGitGitlabToken('');
      toast.success('全局配置已保存并生效（无需重启）');
    } catch (e: any) {
      toast.error(e?.message ?? '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const testGit = async (source: 'GITHUB' | 'GITLAB') => {
    setGitTesting(source);
    setGitTestResult(null);
    try {
      const res = await api.testGit(
        source,
        source === 'GITHUB' ? gitGithubToken.trim() || undefined : gitGitlabToken.trim() || undefined,
        gitGitlabUrl.trim() || undefined,
      );
      setGitTestResult({ source, ok: res.ok, user: res.user, error: res.error });
    } catch (e: any) {
      setGitTestResult({ source, ok: false, error: e?.message ?? '测试失败' });
    } finally {
      setGitTesting(null);
    }
  };

  if (!view) {
    return <div className="py-20 text-center text-sm font-semibold text-ink-muted">加载中...</div>;
  }

  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-2xl font-black text-ink">全局设置</h1>
        <p className="text-sm font-semibold text-ink-muted">保存后热生效，无需重启服务</p>
      </div>

      {/* Review Agent 配置 */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Sparkles className="h-4 w-4" /> Code Review Agent（AI 审查）
          </CardTitle>
          <CardDescription>
            配置 OpenAI 兼容接口后，每次扫描自动附加 AI 审查意见（漏洞评估、修复建议、Top5 优先修复清单）
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-wrap items-center gap-3">
            <div className="flex items-center gap-2">
              <span className="text-sm font-bold text-ink">启用 Agent</span>
              <button
                type="button"
                onClick={() => setEnabled(!enabled)}
                className={cn(
                  'relative h-6 w-11 rounded-full border-chunky border-ink transition-colors',
                  enabled ? 'bg-secondary' : 'bg-paper',
                )}
              >
                <span className={cn('absolute top-0.5 h-4 w-4 rounded-full border border-ink bg-white transition-all', enabled ? 'left-6' : 'left-0.5')} />
              </button>
            </div>
            <Badge variant={view.agent.apiKeyConfigured ? 'success' : 'info'}>
              {view.agent.apiKeyConfigured ? `已配置 API Key（来源：${view.agent.source === 'settings' ? '界面设置' : '环境变量'}）` : '未配置 API Key'}
            </Badge>
            <Badge variant="outline">模型：{view.agent.model}</Badge>
          </div>

          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div>
              <label className="mb-1 block text-xs font-bold text-ink-muted">Base URL</label>
              <Input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)}
                placeholder="https://api.openai.com/v1" />
            </div>
            <div>
              <label className="mb-1 block text-xs font-bold text-ink-muted">模型</label>
              <Input value={model} onChange={(e) => setModel(e.target.value)} placeholder="gpt-4o-mini" />
            </div>
            <div className="sm:col-span-2">
              <label className="mb-1 block text-xs font-bold text-ink-muted">API Key</label>
              <div className="flex items-center gap-2">
                <Input type="password" value={apiKey} onChange={(e) => setApiKey(e.target.value)}
                  placeholder={view.agent.apiKeyConfigured ? '已配置（输入新 Key 可覆盖，留空保持不变）' : 'sk-...'} />
                <KeyRound className="h-4 w-4 shrink-0 text-ink-subtle" />
              </div>
              <p className="mt-1 text-[11px] font-semibold text-ink-subtle">
                Key 仅保存在服务端 config/settings.json，接口不返回明文
              </p>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <Button variant="outline" onClick={test} disabled={testing}>
              {testing ? <Loader2 className="h-4 w-4 animate-spin" /> : <PlugZap className="h-4 w-4" />}
              {testing ? '测试中...' : '测试连接'}
            </Button>
            <Button onClick={save} disabled={saving}>
              {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
              保存配置
            </Button>
            <Button variant="ghost" onClick={load}>
              <RefreshCw className="h-4 w-4" /> 刷新
            </Button>
          </div>

          {/* 测试结果 */}
          {testResult && (
            <div className={cn(
              'rounded-md border-chunky p-3 text-sm',
              testResult.ok ? 'border-ink bg-secondary/30' : 'border-ink bg-error/10',
            )}>
              {testResult.ok ? (
                <div className="flex items-start gap-2">
                  <Badge variant="success">连接正常</Badge>
                  <div className="text-xs font-semibold text-ink-muted">
                    模型 {testResult.model} · 延迟 {testResult.latencyMs}ms
                    {testResult.reply && <span className="mt-1 block text-ink">模型回复：{testResult.reply}</span>}
                  </div>
                </div>
              ) : (
                <div className="flex items-start gap-2">
                  <Badge variant="danger">连接失败</Badge>
                  <div className="break-all text-xs font-semibold text-ink-muted">
                    {testResult.error ?? '未知错误'}
                    {testResult.latencyMs != null && <span>（{testResult.latencyMs}ms）</span>}
                  </div>
                </div>
              )}
            </div>
          )}
        </CardContent>
      </Card>

      {/* SMTP 邮件推送 */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Mail className="h-4 w-4" /> SMTP 邮件推送
          </CardTitle>
          <CardDescription>
            配置后，开启了「邮件报告」的项目在扫描完成时自动推送 PDF 报告到收件邮箱（支持多邮箱同时发送）
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-wrap items-center gap-3">
            <div className="flex items-center gap-2">
              <span className="text-sm font-bold text-ink">启用 SMTP</span>
              <button
                type="button"
                onClick={() => setSmtpEnabled(!smtpEnabled)}
                className={cn(
                  'relative h-6 w-11 rounded-full border-chunky border-ink transition-colors',
                  smtpEnabled ? 'bg-secondary' : 'bg-paper',
                )}
              >
                <span className={cn('absolute top-0.5 h-4 w-4 rounded-full border border-ink bg-white transition-all', smtpEnabled ? 'left-6' : 'left-0.5')} />
              </button>
            </div>
            {view.smtp.ready
              ? <Badge variant="success">配置就绪</Badge>
              : <Badge variant="info">未完整配置</Badge>}
            {view.smtp.passwordConfigured && <Badge variant="outline">授权码已设置</Badge>}
          </div>

          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div>
              <label className="mb-1 block text-xs font-bold text-ink-muted">SMTP 服务器</label>
              <Input value={smtpHost} onChange={(e) => setSmtpHost(e.target.value)} placeholder="smtp.qq.com / smtp.163.com" />
            </div>
            <div>
              <label className="mb-1 block text-xs font-bold text-ink-muted">端口</label>
              <Input type="number" value={smtpPort} onChange={(e) => setSmtpPort(Number(e.target.value))} />
            </div>
            <div>
              <label className="mb-1 block text-xs font-bold text-ink-muted">发件邮箱</label>
              <Input value={smtpUsername} onChange={(e) => setSmtpUsername(e.target.value)} placeholder="your@example.com" />
            </div>
            <div>
              <label className="mb-1 block text-xs font-bold text-ink-muted">SMTP 授权码 / 密码</label>
              <Input type="password" value={smtpPassword} onChange={(e) => setSmtpPassword(e.target.value)}
                placeholder={view.smtp.passwordConfigured ? '已配置（留空保持不变）' : '授权码'} />
            </div>
            <div>
              <label className="mb-1 block text-xs font-bold text-ink-muted">发件人显示地址（可选）</label>
              <Input value={smtpFrom} onChange={(e) => setSmtpFrom(e.target.value)} placeholder="留空使用发件邮箱" />
            </div>
            <div className="sm:col-span-2">
              <label className="mb-1 block text-xs font-bold text-ink-muted">默认收件邮箱</label>
              <Input value={smtpRecipients} onChange={(e) => setSmtpRecipients(e.target.value)}
                placeholder="多个用逗号分隔，如 a@example.com, b@example.com" />
              <p className="mt-1 text-[11px] font-semibold text-ink-subtle">
                项目未单独填写收件邮箱时，扫描报告发送到这里的地址（项目填了邮箱则优先发项目邮箱）
              </p>
            </div>
            <div className="flex items-end pb-1">
              <button
                type="button"
                onClick={() => setSmtpSsl(!smtpSsl)}
                className={cn(
                  'relative h-6 w-11 rounded-full border-chunky border-ink transition-colors',
                  smtpSsl ? 'bg-secondary' : 'bg-paper',
                )}
              >
                <span className={cn('absolute top-0.5 h-4 w-4 rounded-full border border-ink bg-white transition-all', smtpSsl ? 'left-6' : 'left-0.5')} />
              </button>
              <span className="ml-2 text-xs font-bold text-ink">SSL（465 端口默认开启；587 用 STARTTLS 请关闭）</span>
            </div>
          </div>

          {/* SMTP 保存 */}
          <div className="flex items-center gap-2">
            <Button onClick={save} disabled={saving}>
              {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
              保存配置
            </Button>
            <Button variant="ghost" onClick={load}>
              <RefreshCw className="h-4 w-4" /> 刷新
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* 第三方登录（OAuth） */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <ShieldCheck className="h-4 w-4" /> 第三方登录（OAuth）
          </CardTitle>
          <CardDescription>
            配置 GitHub / GitLab OAuth 应用后，登录页支持一键使用第三方账号登录；作为平台全局登录方式，保存后热生效（优先于环境变量）
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-wrap items-center gap-3">
            <div className="flex items-center gap-2">
              <span className="flex items-center gap-2 text-sm font-bold text-ink">
                <Github className="h-4 w-4" /> GitHub OAuth
              </span>
              {view.oauth.githubConfigured
                ? <Badge variant="success">已配置</Badge>
                : <Badge variant="info">未配置</Badge>}
            </div>
            <div className="flex items-center gap-2">
              <span className="flex items-center gap-2 text-sm font-bold text-ink">
                <Gitlab className="h-4 w-4" /> GitLab OAuth
              </span>
              {view.oauth.gitlabConfigured
                ? <Badge variant="success">已配置（{view.oauth.gitlabBaseUrl}）</Badge>
                : <Badge variant="info">未配置</Badge>}
            </div>
            <Badge variant="outline">
              来源：{view.oauth.source === 'settings' ? '界面设置' : '环境变量'}
            </Badge>
          </div>

          <div className="rounded-md border-2 border-ink/10 bg-paper p-3">
            <div className="mb-2 text-xs font-black text-ink">GitHub</div>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <div>
                <label className="mb-1 block text-xs font-bold text-ink-muted">Client ID</label>
                <Input value={ghClientId} onChange={(e) => setGhClientId(e.target.value)}
                  placeholder={view.oauth.githubConfigured ? '已配置（留空保持不变）' : 'GitHub OAuth App Client ID'} />
              </div>
              <div>
                <label className="mb-1 block text-xs font-bold text-ink-muted">Client Secret</label>
                <Input type="password" value={ghSecret} onChange={(e) => setGhSecret(e.target.value)}
                  placeholder={view.oauth.githubConfigured ? '已配置（留空保持不变）' : 'GitHub OAuth App Client Secret'} />
              </div>
              <div className="sm:col-span-2">
                <label className="mb-1 block text-xs font-bold text-ink-muted">回调地址（Redirect URI）</label>
                <Input value={ghRedirect} onChange={(e) => setGhRedirect(e.target.value)}
                  placeholder="http://localhost:9997/api/auth/github/callback" />
                <p className="mt-1 text-[11px] font-semibold text-ink-subtle">
                  在 GitHub → Settings → Developer settings → OAuth Apps 创建应用，授权范围勾选 read:user、repo
                </p>
              </div>
            </div>
          </div>

          <div className="rounded-md border-2 border-ink/10 bg-paper p-3">
            <div className="mb-2 text-xs font-black text-ink">GitLab</div>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <div>
                <label className="mb-1 block text-xs font-bold text-ink-muted">Base URL</label>
                <Input value={glBaseUrl} onChange={(e) => setGlBaseUrl(e.target.value)}
                  placeholder="https://gitlab.com" />
              </div>
              <div>
                <label className="mb-1 block text-xs font-bold text-ink-muted">Client ID</label>
                <Input value={glClientId} onChange={(e) => setGlClientId(e.target.value)}
                  placeholder={view.oauth.gitlabConfigured ? '已配置（留空保持不变）' : 'GitLab Application ID'} />
              </div>
              <div>
                <label className="mb-1 block text-xs font-bold text-ink-muted">Client Secret</label>
                <Input type="password" value={glSecret} onChange={(e) => setGlSecret(e.target.value)}
                  placeholder={view.oauth.gitlabConfigured ? '已配置（留空保持不变）' : 'GitLab Application Secret'} />
              </div>
              <div>
                <label className="mb-1 block text-xs font-bold text-ink-muted">回调地址（Redirect URI）</label>
                <Input value={glRedirect} onChange={(e) => setGlRedirect(e.target.value)}
                  placeholder="http://localhost:9997/api/auth/gitlab/callback" />
              </div>
              <div className="sm:col-span-2">
                <p className="text-[11px] font-semibold text-ink-subtle">
                  在 GitLab → User Settings → Applications 创建应用，回调地址填上面的 Redirect URI，勾选 read_api / api 范围
                </p>
              </div>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <Button onClick={save} disabled={saving}>
              {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
              保存配置
            </Button>
            <Button variant="ghost" onClick={load}>
              <RefreshCw className="h-4 w-4" /> 刷新
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* Git 访问令牌（GitHub / GitLab 私有仓库拉取、分支查询、创建 Issue） */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <GitBranch className="h-4 w-4" /> Git 访问令牌（GitHub / GitLab）
          </CardTitle>
          <CardDescription>
            在这里统一配置 GitHub / GitLab 私有仓库访问令牌，添加项目时无需再逐个填写；项目未单独配置令牌时自动复用这里的全局令牌
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="rounded-md border-2 border-ink/10 bg-paper p-3">
            <div className="mb-2 flex flex-wrap items-center gap-2">
              <span className="flex items-center gap-2 text-sm font-bold text-ink">
                <Github className="h-4 w-4" /> GitHub Personal Access Token
              </span>
              {view.git?.githubTokenConfigured
                ? <Badge variant="success">已配置</Badge>
                : <Badge variant="info">未配置</Badge>}
            </div>
            <div className="flex items-center gap-2">
              <Input type="password" value={gitGithubToken} onChange={(e) => setGitGithubToken(e.target.value)}
                placeholder={view.git?.githubTokenConfigured ? '已配置（输入新 Token 可覆盖，留空保持不变）' : 'ghp_... 或 github_pat_...'} />
              <Button type="button" variant="outline" onClick={() => testGit('GITHUB')} disabled={gitTesting !== null}>
                {gitTesting === 'GITHUB' ? <Loader2 className="h-4 w-4 animate-spin" /> : <PlugZap className="h-4 w-4" />}
                {gitTesting === 'GITHUB' ? '测试中' : '测试连接'}
              </Button>
            </div>
            <p className="mt-1 text-[11px] font-semibold text-ink-subtle">
              GitHub → Settings → Developer settings → Personal access tokens 生成（勾选 repo 范围）
            </p>
          </div>

          <div className="rounded-md border-2 border-ink/10 bg-paper p-3">
            <div className="mb-2 flex flex-wrap items-center gap-2">
              <span className="flex items-center gap-2 text-sm font-bold text-ink">
                <Gitlab className="h-4 w-4" /> GitLab Personal Access Token
              </span>
              {view.git?.gitlabTokenConfigured
                ? <Badge variant="success">已配置（{view.git.gitlabUrl}）</Badge>
                : <Badge variant="info">未配置</Badge>}
            </div>
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              <div>
                <label className="mb-1 block text-xs font-bold text-ink-muted">GitLab Base URL</label>
                <Input value={gitGitlabUrl} onChange={(e) => setGitGitlabUrl(e.target.value)} placeholder="https://gitlab.com" />
              </div>
              <div>
                <label className="mb-1 block text-xs font-bold text-ink-muted">访问令牌</label>
                <Input type="password" value={gitGitlabToken} onChange={(e) => setGitGitlabToken(e.target.value)}
                  placeholder={view.git?.gitlabTokenConfigured ? '已配置（留空保持不变）' : 'glpat-...'} />
              </div>
            </div>
            <div className="mt-2 flex items-center gap-2">
              <Button type="button" variant="outline" onClick={() => testGit('GITLAB')} disabled={gitTesting !== null}>
                {gitTesting === 'GITLAB' ? <Loader2 className="h-4 w-4 animate-spin" /> : <PlugZap className="h-4 w-4" />}
                {gitTesting === 'GITLAB' ? '测试中' : '测试连接'}
              </Button>
            </div>
            <p className="mt-1 text-[11px] font-semibold text-ink-subtle">
              GitLab → User Settings → Access Tokens 生成（勾选 read_api / api 范围）；内网 GitLab 请填写对应 Base URL
            </p>
          </div>

          {/* 测试结果 */}
          {gitTestResult && (
            <div className={cn(
              'rounded-md border-chunky p-3 text-sm',
              gitTestResult.ok ? 'border-ink bg-secondary/30' : 'border-ink bg-error/10',
            )}>
              <div className="flex items-start gap-2">
                {gitTestResult.ok
                  ? <Badge variant="success"><CircleCheck className="mr-1 h-3.5 w-3.5" /> 连接正常</Badge>
                  : <Badge variant="danger"><CircleX className="mr-1 h-3.5 w-3.5" /> 连接失败</Badge>}
                <div className="break-all text-xs font-semibold text-ink-muted">
                  {gitTestResult.source === 'GITHUB' ? 'GitHub' : 'GitLab'}
                  {gitTestResult.ok
                    ? (gitTestResult.user ? ` · 已认证为 ${gitTestResult.user}` : ' · 令牌有效')
                    : ` · ${gitTestResult.error ?? '未知错误'}`}
                  {gitTestResult.latencyMs != null && <span>（{gitTestResult.latencyMs}ms）</span>}
                </div>
              </div>
            </div>
          )}

          <div className="flex items-center gap-2">
            <Button onClick={save} disabled={saving}>
              {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
              保存配置
            </Button>
            <Button variant="ghost" onClick={load}>
              <RefreshCw className="h-4 w-4" /> 刷新
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

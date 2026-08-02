import { useEffect, useState } from 'react';
import { Sparkles, Save, Loader2, KeyRound, Github, Gitlab, ShieldCheck, RefreshCw, Mail, PlugZap } from 'lucide-react';
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
        },
      };
      const updated = await api.updateSettings(payload);
      setView(updated);
      setApiKey('');
      toast.success('全局配置已保存并生效（无需重启）');
    } catch (e: any) {
      toast.error(e?.message ?? '保存失败');
    } finally {
      setSaving(false);
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

      {/* OAuth 状态 */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <ShieldCheck className="h-4 w-4" /> 第三方登录（OAuth）
          </CardTitle>
          <CardDescription>OAuth 客户端密钥需通过环境变量配置（GITHUB_CLIENT_ID/SECRET、GITLAB_CLIENT_ID/SECRET）</CardDescription>
        </CardHeader>
        <CardContent className="space-y-2">
          <div className="flex items-center justify-between rounded-md border-2 border-ink/10 bg-paper px-3 py-2.5">
            <span className="flex items-center gap-2 text-sm font-bold text-ink"><Github className="h-4 w-4" /> GitHub OAuth</span>
            {view.oauth.githubConfigured
              ? <Badge variant="success">已配置</Badge>
              : <Badge variant="info">未配置</Badge>}
          </div>
          <div className="flex items-center justify-between rounded-md border-2 border-ink/10 bg-paper px-3 py-2.5">
            <span className="flex items-center gap-2 text-sm font-bold text-ink"><Gitlab className="h-4 w-4" /> GitLab OAuth</span>
            {view.oauth.gitlabConfigured
              ? <Badge variant="success">已配置（{view.oauth.gitlabBaseUrl}）</Badge>
              : <Badge variant="info">未配置</Badge>}
          </div>
          <p className="pt-1 text-xs font-semibold text-ink-subtle">
            配置方法见 README「GitHub / GitLab OAuth 配置」；设置回调地址为
            <code> http://localhost:9997/api/auth/github/callback</code> 与
            <code> /api/auth/gitlab/callback</code>
          </p>
        </CardContent>
      </Card>
    </div>
  );
}

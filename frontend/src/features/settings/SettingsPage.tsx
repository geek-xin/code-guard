import { useEffect, useState } from 'react';
import { Sparkles, Save, Loader2, KeyRound, Github, Gitlab, ShieldCheck, RefreshCw } from 'lucide-react';
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

  const load = () => {
    api.getSettings().then((s) => {
      setView(s);
      setEnabled(s.agent.enabled);
      setBaseUrl(s.agent.baseUrl ?? '');
      setModel(s.agent.model ?? '');
      setApiKey('');
    }).catch(() => {});
  };

  useEffect(() => {
    load();
  }, []);

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

          <div className="flex items-center gap-2">
            <Button onClick={save} disabled={saving}>
              {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
              保存配置
            </Button>
            <Button variant="outline" onClick={load}>
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

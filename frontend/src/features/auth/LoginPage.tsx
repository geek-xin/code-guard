import { useState } from 'react';
import { Shield, Github, Gitlab, KeyRound, Loader2 } from 'lucide-react';
import { api, setToken } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { toast } from 'sonner';
import { cn } from '@/lib/utils';

export default function LoginPage() {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [remember, setRemember] = useState(true);
  const [loading, setLoading] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    try {
      const res = mode === 'login'
        ? await api.login(username, password, remember)
        : await api.register(username, password, displayName, remember);
      setToken(res.token);
      window.dispatchEvent(new Event('cg_auth'));
      window.location.hash = '#/dashboard';
      toast.success(mode === 'login' ? '登录成功' : '注册成功');
    } catch (err: any) {
      toast.error(err?.message ?? '操作失败');
    } finally {
      setLoading(false);
    }
  };

  const oauth = async (provider: 'github' | 'gitlab') => {
    try {
      // 记住偏好：OAuth 回调后按此签发长有效期 token
      localStorage.setItem('cg_remember', String(remember));
      const res = provider === 'github'
        ? await api.githubAuthorize(remember)
        : await api.gitlabAuthorize(remember);
      window.location.href = res.url;
    } catch (err: any) {
      toast.error(err?.message ?? 'OAuth 配置不可用，请管理员在「设置 → 第三方登录」中配置 Client ID / Secret');
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center px-4">
      <div className="w-full max-w-sm">
        <div className="mb-8 flex flex-col items-center gap-3">
          <div className="flex h-20 w-20 items-center justify-center rounded-xl border-chunky border-ink bg-primary shadow-chunky">
            <Shield className="h-11 w-11 text-white" strokeWidth={2.5} />
          </div>
          <div className="text-center">
            <h1 className="text-3xl font-black text-ink">CodeGuard</h1>
            <p className="mt-1 text-sm font-semibold text-ink-muted">SAST + SCA + Code Review Agent 代码安全分析平台</p>
          </div>
        </div>

        <div className="rounded-lg border-chunky border-ink bg-white p-6 shadow-chunky-lg">
          <div className="mb-4 grid grid-cols-2 gap-2">
            <Button type="button" variant={mode === 'login' ? 'default' : 'outline'} onClick={() => setMode('login')}>
              <KeyRound className="h-4 w-4" /> 账号登录
            </Button>
            <Button type="button" variant={mode === 'register' ? 'default' : 'outline'} onClick={() => setMode('register')}>
              注册账号
            </Button>
          </div>

          <form onSubmit={submit} className="space-y-3">
            <div>
              <label className="mb-1 block text-xs font-bold text-ink-muted">用户名</label>
              <Input value={username} onChange={(e) => setUsername(e.target.value)} placeholder="请输入用户名" required autoFocus />
            </div>
            {mode === 'register' && (
              <div>
                <label className="mb-1 block text-xs font-bold text-ink-muted">显示名称（可选）</label>
                <Input value={displayName} onChange={(e) => setDisplayName(e.target.value)} placeholder="显示名称" />
              </div>
            )}
            <div>
              <label className="mb-1 block text-xs font-bold text-ink-muted">密码</label>
              <Input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="密码" required />
            </div>

            {/* 记住我 */}
            <button
              type="button"
              onClick={() => setRemember(!remember)}
              className="flex w-full items-center gap-2 rounded-md border-chunky border-ink/20 bg-paper px-3 py-2 text-left transition-colors hover:bg-paper-alt"
            >
              <span
                className={cn(
                  'flex h-5 w-5 items-center justify-center rounded border-chunky border-ink text-[11px] font-black text-white',
                  remember ? 'bg-success' : 'bg-white text-transparent',
                )}
              >
                ✓
              </span>
              <span className="text-xs font-bold text-ink">记住我（30 天内免登录，跨浏览器会话）</span>
            </button>

            <Button type="submit" className="w-full" disabled={loading}>
              {loading && <Loader2 className="h-4 w-4 animate-spin" />}
              {mode === 'login' ? '登 录' : '注 册'}
            </Button>
          </form>

          <div className="my-4 flex items-center gap-3 text-xs font-bold text-ink-subtle">
            <div className="h-0.5 flex-1 bg-ink/10" />
            或使用第三方登录
            <div className="h-0.5 flex-1 bg-ink/10" />
          </div>

          <div className="grid grid-cols-2 gap-2">
            <Button type="button" variant="outline" onClick={() => oauth('github')}>
              <Github className="h-4 w-4" /> GitHub
            </Button>
            <Button type="button" variant="outline" onClick={() => oauth('gitlab')}>
              <Gitlab className="h-4 w-4" /> GitLab
            </Button>
          </div>
          <p className="mt-2 text-center text-[11px] font-semibold text-ink-subtle">
            勾选「记住我」后 GitHub / GitLab 授权同样生效
          </p>
          <p className="mt-1 text-center text-[11px] font-semibold text-ink-subtle">
            默认账号 admin / admin123（首次启动自动创建，请尽快修改）
          </p>
        </div>
      </div>
    </div>
  );
}

import { useEffect, useState } from 'react';
import { Database, RefreshCw, CalendarClock, ShieldCheck, Loader2, Globe } from 'lucide-react';
import { api, VulnDbStatus } from '@/lib/api';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { formatTime } from '@/lib/utils';
import { toast } from 'sonner';

const ECOSYSTEMS = [
  { name: 'npm', color: '#CB3837' },
  { name: 'Maven', color: '#C71A36' },
  { name: 'PyPI', color: '#3775A9' },
  { name: 'Go', color: '#00ADD8' },
  { name: 'RubyGems', color: '#E9573F' },
  { name: 'Packagist', color: '#30A9DE' },
];

export default function VulnDbPage() {
  const [status, setStatus] = useState<VulnDbStatus | null>(null);
  const [updating, setUpdating] = useState(false);

  const load = () => {
    api.vulndbStatus().then(setStatus).catch(() => {});
  };

  useEffect(() => {
    load();
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
  }, []);

  const update = async () => {
    setUpdating(true);
    try {
      const res = await api.vulndbUpdate();
      toast.success(res.message);
      // 轮询直到更新完成
      const poll = setInterval(() => {
        api.vulndbStatus().then((s) => {
          setStatus(s);
          if (!s.updating) {
            clearInterval(poll);
            setUpdating(false);
            toast.success('漏洞库更新完成');
          }
        }).catch(() => {});
      }, 3000);
    } catch (e: any) {
      toast.error(e?.message ?? '更新失败');
      setUpdating(false);
    }
  };

  const updatingNow = updating || status?.updating;

  return (
    <div className="space-y-5">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-black text-ink">漏洞库</h1>
          <p className="text-sm font-semibold text-ink-muted">本地离线 CVE 库 + OSV.dev 在线查询，支持定时更新</p>
        </div>
        <Button onClick={update} disabled={updatingNow}>
          {updatingNow ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
          {updatingNow ? '更新中...' : '立即更新'}
        </Button>
      </div>

      <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2"><Database className="h-4 w-4" /> 本地离线库</CardTitle>
            <CardDescription>重点生态包的已知漏洞（启动即加载）</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="text-4xl font-black text-ink">{status?.count ?? '-'}</div>
            <div className="mt-1 text-sm font-bold text-ink-muted">条漏洞记录</div>
            <div className="mt-3 flex items-center gap-2">
              {status?.osvEnabled ? <Badge variant="success">OSV 在线查询已启用</Badge> : <Badge variant="danger">OSV 已关闭</Badge>}
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2"><CalendarClock className="h-4 w-4" /> 更新计划</CardTitle>
            <CardDescription>自动定时从 OSV.dev 同步</CardDescription>
          </CardHeader>
          <CardContent className="space-y-2 text-sm">
            <div className="flex justify-between">
              <span className="font-bold text-ink-muted">上次更新</span>
              <span className="font-black text-ink">{status?.lastUpdatedAt ? formatTime(status.lastUpdatedAt) : '尚未更新（使用内置库）'}</span>
            </div>
            <div className="flex justify-between">
              <span className="font-bold text-ink-muted">库版本</span>
              <span className="font-mono font-bold text-ink">{status?.version ?? 'builtin'}</span>
            </div>
            <div className="flex justify-between">
              <span className="font-bold text-ink-muted">下次定时</span>
              <span className="font-black text-ink">{status?.nextScheduledUpdate ?? '每天 03:30'}</span>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2"><Globe className="h-4 w-4" /> 覆盖生态</CardTitle>
            <CardDescription>离线库 + OSV 在线全覆盖</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="flex flex-wrap gap-2">
              {ECOSYSTEMS.map((e) => (
                <span
                  key={e.name}
                  className="inline-flex items-center rounded-full border-chunky border-ink px-3 py-1 text-xs font-black text-white shadow-chunky-sm"
                  style={{ background: e.color }}
                >
                  {e.name}
                </span>
              ))}
            </div>
            <p className="mt-3 text-xs font-semibold leading-relaxed text-ink-muted">
              离线库覆盖 60+ 重点包（lodash / log4j / spring / fastjson / django 等）；
              在线模式对任何已解析版本的依赖调用 OSV API 精确匹配，并缓存 7 天。
            </p>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2"><ShieldCheck className="h-4 w-4" /> 工作原理</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2 text-sm leading-relaxed text-ink-muted">
          <p>
            1. 扫描时先匹配<b className="text-ink">本地离线库</b>（毫秒级），再对未命中依赖发起
            <b className="text-ink"> OSV.dev 并行查询</b>（带并发限流与请求合并，结果缓存 7 天）。
          </p>
          <p>
            2. 每日 03:30 自动从 OSV 拉取重点包最新漏洞并<b className="text-ink">热更新本地库</b>，
            也可点击「立即更新」手动触发，完成后无需重启即生效。
          </p>
          <p>
            3. 未锁定版本的依赖（如 Maven 未声明 version）会给出 INFO 提示并跳过比对，避免误报。
          </p>
        </CardContent>
      </Card>
    </div>
  );
}

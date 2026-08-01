import { useEffect, useState } from 'react';
import { FolderGit2, ScanSearch, Bug, AlertTriangle, Gauge, Database } from 'lucide-react';
import { api, DashboardStats, TrendPoint, ScanRecord } from '@/lib/api';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { SEVERITY_META, ENGINE_LABEL, formatTime, timeAgo } from '@/lib/utils';
import { TrendChart } from '@/features/dashboard/TrendChart';

const SEVS = ['critical', 'high', 'medium', 'low'] as const;

export default function DashboardPage() {
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [trend, setTrend] = useState<TrendPoint[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api.dashboardStats().then(setStats).catch(() => {});
    api.dashboardTrend(14).then(setTrend).catch(() => {});
    const timer = setInterval(() => {
      api.dashboardStats().then(setStats).catch(() => {});
    }, 10000);
    return () => clearInterval(timer);
  }, []);

  if (!stats) {
    return <div className="py-20 text-center text-sm font-semibold text-ink-muted">加载中...</div>;
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-black text-ink">安全总览</h1>
        <p className="text-sm font-semibold text-ink-muted">各工程扫描漏洞汇总与趋势</p>
      </div>

      {/* 统计卡片 */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4 xl:grid-cols-6">
        <StatCard icon={<FolderGit2 className="h-5 w-5" />} label="工程总数" value={stats.totalProjects} sub={`已扫描 ${stats.scannedProjects}`} />
        <StatCard icon={<ScanSearch className="h-5 w-5" />} label="扫描次数" value={stats.totalScans} sub={stats.runningScans > 0 ? `运行中 ${stats.runningScans}` : '无进行中的扫描'} highlight={stats.runningScans > 0} />
        <StatCard icon={<Bug className="h-5 w-5" />} label="漏洞总数" value={stats.findings} sub="最近一次扫描汇总" />
        {SEVS.map((sev) => (
          <StatCard
            key={sev}
            icon={<AlertTriangle className="h-5 w-5" />}
            label={SEVERITY_META[sev.toUpperCase()].label + '漏洞'}
            value={stats[sev]}
            color={SEVERITY_META[sev.toUpperCase()].color}
          />
        ))}
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        {/* 趋势图 */}
        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle className="flex items-center gap-2"><Gauge className="h-4 w-4" /> 近 14 天漏洞趋势</CardTitle>
            <CardDescription>按扫描完成日期汇总</CardDescription>
          </CardHeader>
          <CardContent>
            <TrendChart data={trend} />
          </CardContent>
        </Card>

        {/* 引擎分布 */}
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2"><Database className="h-4 w-4" /> 扫描引擎分布</CardTitle>
            <CardDescription>SCA / SAST / Review Agent</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            {Object.entries(stats.byEngine ?? {}).length === 0 && (
              <p className="text-sm font-semibold text-ink-subtle">暂无扫描数据，请先在「项目」中添加工程并扫描。</p>
            )}
            {Object.entries(stats.byEngine ?? {}).map(([engine, count]) => {
              const total = Object.values(stats.byEngine ?? {}).reduce((a, b) => a + b, 0) || 1;
              const pct = Math.round((count / total) * 100);
              return (
                <div key={engine}>
                  <div className="mb-1 flex items-center justify-between text-sm">
                    <span className="font-bold text-ink">{ENGINE_LABEL[engine] ?? engine}</span>
                    <span className="font-black text-ink">{count}</span>
                  </div>
                  <div className="h-4 w-full overflow-hidden rounded-sm border-chunky border-ink bg-paper">
                    <div className="h-full bg-primary transition-all" style={{ width: `${pct}%` }} />
                  </div>
                </div>
              );
            })}
            <div className="rounded-md border-2 border-ink/10 bg-paper p-3 text-xs font-semibold text-ink-muted">
              引擎说明：<span className="text-ink">SCA</span> 依赖与 CVE 比对 · <span className="text-ink">SAST</span> 静态代码规则 ·{' '}
              <span className="text-ink">Agent</span> AI 修复方案审查
            </div>
          </CardContent>
        </Card>
      </div>

      {/* 最近扫描 */}
      <Card>
        <CardHeader className="flex-row items-center justify-between">
          <div>
            <CardTitle>最近扫描</CardTitle>
            <CardDescription>最新 10 次扫描结果</CardDescription>
          </div>
          <Button variant="outline" size="sm" onClick={() => (window.location.hash = '#/scans')}>
            查看全部
          </Button>
        </CardHeader>
        <CardContent>
          {stats.recentScans.length === 0 ? (
            <p className="py-8 text-center text-sm font-semibold text-ink-subtle">还没有扫描记录</p>
          ) : (
            <div className="divide-y-2 divide-ink/10">
              {stats.recentScans.map((s) => <RecentScanRow key={s.id} scan={s} />)}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function StatCard({ icon, label, value, sub, color, highlight }: {
  icon: React.ReactNode; label: string; value: number; sub?: string; color?: string; highlight?: boolean;
}) {
  return (
    <Card className={highlight ? 'bg-gold/40' : ''}>
      <CardContent className="p-4">
        <div className="flex items-center justify-between">
          <span className={`flex h-9 w-9 items-center justify-center rounded-md border-chunky border-ink bg-white shadow-chunky-sm ${color ?? 'text-primary'}`}>
            {icon}
          </span>
          <span className="text-3xl font-black text-ink">{value}</span>
        </div>
        <div className="mt-2 text-sm font-bold text-ink">{label}</div>
        {sub && <div className="text-xs font-semibold text-ink-muted">{sub}</div>}
      </CardContent>
    </Card>
  );
}

function RecentScanRow({ scan }: { scan: ScanRecord }) {
  const summary = scan.summary ?? {};
  const statusBadge =
    scan.status === 'COMPLETED' ? <Badge variant="success">完成</Badge>
      : scan.status === 'RUNNING' ? <Badge variant="warning">扫描中</Badge>
        : scan.status === 'FAILED' ? <Badge variant="danger">失败</Badge>
          : scan.status === 'STOPPED' ? <Badge variant="info">已停止</Badge>
            : <Badge>{scan.status}</Badge>;
  return (
    <a href={`#/scans/${scan.id}`} className="flex items-center justify-between gap-3 py-2.5 transition-colors hover:bg-paper">
      <div className="min-w-0">
        <div className="flex items-center gap-2">
          <span className="truncate text-sm font-bold text-ink">{scan.projectName}</span>
          {statusBadge}
          {scan.trigger === 'SCHEDULED' && <Badge variant="outline">定时</Badge>}
        </div>
        <div className="mt-0.5 text-xs font-semibold text-ink-muted">
          {formatTime(scan.startedAt)} · 耗时 {(scan.durationMs ?? 0) / 1000}s
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-1.5">
        {(['critical', 'high', 'medium', 'low'] as const).map((k) => (
          <span key={k} className="flex items-center gap-1 text-xs font-black">
            <span className="h-2.5 w-2.5 rounded-sm" style={{ background: SEVERITY_META[k.toUpperCase()].bar }} />
            {summary[k] ?? 0}
          </span>
        ))}
      </div>
    </a>
  );
}

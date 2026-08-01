import { useEffect, useState } from 'react';
import { ScanRecord, api } from '@/lib/api';
import { Card, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { SEVERITY_META, formatTime } from '@/lib/utils';

export default function ScansPage() {
  const [scans, setScans] = useState<ScanRecord[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api.listScans().then(setScans).catch(() => {}).finally(() => setLoading(false));
    const t = setInterval(() => api.listScans().then(setScans).catch(() => {}), 8000);
    return () => clearInterval(t);
  }, []);

  if (loading) {
    return <div className="py-20 text-center text-sm font-semibold text-ink-muted">加载中...</div>;
  }

  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-2xl font-black text-ink">扫描记录</h1>
        <p className="text-sm font-semibold text-ink-muted">全部扫描任务，点击查看实时进度与漏洞详情</p>
      </div>
      {scans.length === 0 ? (
        <Card>
          <CardContent className="py-16 text-center text-sm font-semibold text-ink-subtle">暂无扫描记录</CardContent>
        </Card>
      ) : (
        <Card>
          <CardContent className="p-0">
            <div className="divide-y-2 divide-ink/10">
              {scans.map((s) => (
                <a key={s.id} href={`#/scans/${s.id}`} className="flex items-center gap-4 px-4 py-3 transition-colors hover:bg-paper">
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="truncate text-sm font-black text-ink">{s.projectName}</span>
                      <StatusBadge status={s.status} />
                      {s.trigger === 'SCHEDULED' && <Badge variant="outline">定时</Badge>}
                      <Badge variant="outline">{s.scope}</Badge>
                    </div>
                    <div className="mt-0.5 text-xs font-semibold text-ink-muted">
                      {formatTime(s.startedAt)}
                      {s.durationMs != null && ` · 耗时 ${(s.durationMs / 1000).toFixed(1)}s`}
                      {s.message && ` · ${s.message}`}
                    </div>
                  </div>
                  <div className="flex shrink-0 items-center gap-3">
                    {(['critical', 'high', 'medium', 'low'] as const).map((k) => (
                      <span key={k} className="flex items-center gap-1 text-xs font-black text-ink">
                        <span className="h-2.5 w-2.5 rounded-sm" style={{ background: SEVERITY_META[k.toUpperCase()].bar }} />
                        {s.summary?.[k] ?? 0}
                      </span>
                    ))}
                  </div>
                </a>
              ))}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  const map: Record<string, { label: string; variant: 'success' | 'warning' | 'danger' | 'info' }> = {
    COMPLETED: { label: '已完成', variant: 'success' },
    RUNNING: { label: '扫描中', variant: 'warning' },
    FAILED: { label: '失败', variant: 'danger' },
    STOPPED: { label: '已停止', variant: 'info' },
  };
  const m = map[status] ?? { label: status, variant: 'info' as const };
  return <Badge variant={m.variant}>{m.label}</Badge>;
}

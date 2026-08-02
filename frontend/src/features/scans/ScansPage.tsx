import { useEffect, useMemo, useState } from 'react';
import { ChevronDown, ChevronRight, Search, FolderGit2, Github, Gitlab, FolderOpen } from 'lucide-react';
import { ScanRecord, Project, api } from '@/lib/api';
import { Card, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { SEVERITY_META, formatTime, cn } from '@/lib/utils';

export default function ScansPage() {
  const [projects, setProjects] = useState<Project[]>([]);
  const [scans, setScans] = useState<ScanRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [nameFilter, setNameFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());

  useEffect(() => {
    const load = () => {
      Promise.all([api.listProjects(), api.listScans()])
        .then(([p, s]) => {
          setProjects(p);
          setScans(s);
        })
        .catch(() => {})
        .finally(() => setLoading(false));
    };
    load();
    const t = setInterval(load, 8000);
    return () => clearInterval(t);
  }, []);

  const projById = useMemo(() => new Map(projects.map((p) => [p.id, p])), [projects]);

  const filtered = useMemo(() => {
    return scans.filter((s) => {
      const p = projById.get(s.projectId);
      const name = p?.alias || p?.name || s.projectName || '';
      if (nameFilter && !name.toLowerCase().includes(nameFilter.trim().toLowerCase())) return false;
      if (statusFilter && s.status !== statusFilter) return false;
      return true;
    });
  }, [scans, projById, nameFilter, statusFilter]);

  /** 分组：同一仓库地址/本地目录一组 */
  const groups = useMemo(() => {
    const map = new Map<string, { project: Project | null; records: ScanRecord[] }>();
    for (const s of filtered) {
      const p = projById.get(s.projectId) ?? null;
      const key = p ? (p.repoUrl || p.localPath || p.name) : s.projectName;
      if (!map.has(key)) {
        map.set(key, { project: p, records: [] });
      }
      map.get(key)!.records.push(s);
    }
    const result: { key: string; project: Project | null; records: ScanRecord[] }[] = [];
    for (const [key, g] of map) {
      g.records.sort((a, b) => (b.startedAt ?? '').localeCompare(a.startedAt ?? ''));
      result.push({ key, project: g.project, records: g.records });
    }
    // 组按最近一次扫描时间倒序
    result.sort((a, b) => (b.records[0]?.startedAt ?? '').localeCompare(a.records[0]?.startedAt ?? ''));
    return result;
  }, [filtered, projById]);

  const toggle = (key: string) => {
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  if (loading) {
    return <div className="py-20 text-center text-sm font-semibold text-ink-muted">加载中...</div>;
  }

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-black text-ink">扫描记录</h1>
          <p className="text-sm font-semibold text-ink-muted">
            按仓库地址 / 本地目录分组，点击记录查看实时进度与漏洞详情
          </p>
        </div>
        {/* 搜索区 */}
        <div className="flex items-center gap-2">
          <div className="relative">
            <Search className="absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-subtle" />
            <Input
              value={nameFilter}
              onChange={(e) => setNameFilter(e.target.value)}
              placeholder="搜索项目名称..."
              className="w-48 pl-8"
            />
          </div>
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="h-9 rounded-md border-chunky border-ink bg-white px-2 text-sm font-bold text-ink focus:border-primary focus:outline-none"
          >
            <option value="">全部状态</option>
            <option value="COMPLETED">已完成</option>
            <option value="RUNNING">扫描中</option>
            <option value="FAILED">失败</option>
            <option value="STOPPED">已停止</option>
          </select>
        </div>
      </div>

      {groups.length === 0 ? (
        <Card>
          <CardContent className="py-16 text-center text-sm font-semibold text-ink-subtle">
            {scans.length === 0 ? '暂无扫描记录' : '没有匹配的扫描记录'}
          </CardContent>
        </Card>
      ) : (
        <div className="space-y-4">
          {groups.map((g) => {
            const p = g.project;
            const sourceIcon =
              p?.source === 'GITHUB' ? <Github className="h-4 w-4" />
                : p?.source === 'GITLAB' ? <Gitlab className="h-4 w-4" />
                  : <FolderOpen className="h-4 w-4" />;
            const title = p?.alias || p?.name || g.key;
            const address = p?.repoUrl || p?.localPath || g.key;
            const isCollapsed = collapsed.has(g.key);
            return (
              <Card key={g.key}>
                {/* 组头 */}
                <button
                  type="button"
                  onClick={() => toggle(g.key)}
                  className="flex w-full items-center gap-3 border-b-2 border-ink/10 px-4 py-3 text-left transition-colors hover:bg-paper"
                >
                  <span className="text-primary">{isCollapsed ? <ChevronRight className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}</span>
                  <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md border-chunky border-ink bg-white shadow-chunky-sm text-primary">
                    {sourceIcon}
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-base font-black text-ink">{title}</span>
                      {p && <Badge variant="outline">{p.source}</Badge>}
                      <Badge variant="outline">{g.records.length} 次扫描</Badge>
                    </div>
                    <div className="mt-0.5 truncate font-mono text-xs font-semibold text-ink-muted">{address}</div>
                  </div>
                </button>
                {/* 组内记录（时间倒序） */}
                {!isCollapsed && (
                  <div className="divide-y-2 divide-ink/10">
                    {g.records.map((s) => (
                      <a
                        key={s.id}
                        href={`#/scans/${s.id}`}
                        className="flex items-center gap-4 px-4 py-2.5 transition-colors hover:bg-paper"
                      >
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-2">
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
                )}
              </Card>
            );
          })}
        </div>
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

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Plus, RefreshCw, ScanSearch, Pencil, Trash2, FolderGit2, Github, Gitlab, FolderOpen, Clock, Mail, Search, Tags, History } from 'lucide-react';
import GroupManageDialog from '@/features/groups/GroupManageDialog';
import { api, Project, ProjectGroup } from '@/lib/api';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { SEVERITY_META, timeAgo, formatTime } from '@/lib/utils';
import { toast } from 'sonner';
import ProjectFormDialog from '@/features/projects/ProjectFormDialog';
import {
  AlertDialog, AlertDialogTrigger, AlertDialogContent, AlertDialogHeader, AlertDialogTitle,
  AlertDialogDescription, AlertDialogFooter, AlertDialogAction, AlertDialogCancel,
} from '@/components/ui/alert-dialog';

const SOURCE_META: Record<string, { label: string; icon: React.ReactNode }> = {
  GITHUB: { label: 'GitHub', icon: <Github className="h-4 w-4" /> },
  GITLAB: { label: 'GitLab', icon: <Gitlab className="h-4 w-4" /> },
  LOCAL: { label: '本地目录', icon: <FolderOpen className="h-4 w-4" /> },
};

export default function ProjectsPage() {
  const [projects, setProjects] = useState<Project[]>([]);
  const [groups, setGroups] = useState<ProjectGroup[]>([]);
  const [loading, setLoading] = useState(true);
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<Project | null>(null);
  const [deleting, setDeleting] = useState<Project | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [nameFilter, setNameFilter] = useState('');
  const [groupManageOpen, setGroupManageOpen] = useState(false);

  const load = useCallback(() => {
    Promise.all([api.listProjects(), api.listGroups()])
      .then(([p, g]) => {
        setProjects(p);
        setGroups(g);
      })
      .catch((e) => toast.error(e.message))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
    const timer = setInterval(load, 8000);
    return () => clearInterval(timer);
  }, [load]);

  const scan = async (p: Project) => {
    try {
      const record = await api.startScan(p.id, 'ALL');
      toast.success(`已启动扫描：${p.name}`);
      window.location.hash = `#/scans/${record.id}`;
    } catch (e: any) {
      toast.error(e?.message ?? '启动扫描失败');
    }
  };

  const sync = async (p: Project) => {
    setBusyId(p.id);
    try {
      const res = await api.syncProject(p.id);
      toast.success(`${p.name}: ${res.message}`);
      load();
    } catch (e: any) {
      toast.error(e?.message ?? '同步失败');
    } finally {
      setBusyId(null);
    }
  };

  const remove = async () => {
    if (!deleting) return;
    try {
      await api.deleteProject(deleting.id);
      toast.success('项目已删除');
      load();
    } catch (e: any) {
      toast.error(e?.message ?? '删除失败');
    }
    setDeleting(null);
  };

  const filteredProjects = projects.filter((p) => {
    const q = nameFilter.trim().toLowerCase();
    if (!q) return true;
    return (p.name ?? '').toLowerCase().includes(q) || (p.alias ?? '').toLowerCase().includes(q);
  });

  /** 按分组聚合（未分组放最后） */
  const grouped = useMemo(() => {
    const map = new Map<string, Project[]>();
    for (const p of filteredProjects) {
      const g = (p.group ?? '').trim() || '未分组';
      if (!map.has(g)) map.set(g, []);
      map.get(g)!.push(p);
    }
    return [...map.entries()].sort((a, b) => {
      if (a[0] === '未分组') return 1;
      if (b[0] === '未分组') return -1;
      return a[0].localeCompare(b[0], 'zh-CN');
    });
  }, [filteredProjects]);

  const openCreate = () => {
    setEditing(null);
    setFormOpen(true);
  };
  const openEdit = (p: Project) => {
    setEditing(p);
    setFormOpen(true);
  };

  if (loading) {
    return <div className="py-20 text-center text-sm font-semibold text-ink-muted">加载中...</div>;
  }

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-black text-ink">工程项目</h1>
          <p className="text-sm font-semibold text-ink-muted">
            共 {filteredProjects.length} / {projects.length} 个工程 · 支持 GitHub / GitLab / 本地目录
          </p>
        </div>
        <div className="flex items-center gap-2">
          <div className="relative">
            <Search className="absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-subtle" />
            <Input
              value={nameFilter}
              onChange={(e) => setNameFilter(e.target.value)}
              placeholder="过滤项目名称..."
              className="w-52 pl-8"
            />
          </div>
          <Button variant="outline" onClick={() => setGroupManageOpen(true)}>
            <Tags className="h-4 w-4" /> 管理分组
          </Button>
          <Button onClick={openCreate}>
            <Plus className="h-4 w-4" /> 添加项目
          </Button>
        </div>
      </div>

      {filteredProjects.length === 0 ? (
        <Card>
          <CardContent className="flex flex-col items-center gap-3 py-16 text-center">
            <FolderGit2 className="h-12 w-12 text-ink-subtle" />
            <div className="text-lg font-black text-ink">还没有项目</div>
            <p className="max-w-md text-sm font-semibold text-ink-muted">
              添加 GitHub / GitLab 仓库（自动拉取代码）或本地源码目录，即可启动
              SAST 静态分析、SCA 依赖漏洞扫描与 AI 代码审查。
            </p>
            <Button onClick={openCreate} className="mt-2">
              <Plus className="h-4 w-4" /> 添加第一个项目
            </Button>
          </CardContent>
        </Card>
      ) : (
        <div className="space-y-4">
          {grouped.map(([groupName, groupProjects]) => (
            <section key={groupName}>
              <div className="mb-3 flex items-center gap-2">
                <FolderGit2 className="h-4 w-4 text-primary" />
                <h2 className="text-base font-black text-ink">{groupName}</h2>
                <span className="rounded-full border-chunky border-ink bg-white px-2 py-0.5 text-xs font-black text-ink-muted shadow-chunky-sm">
                  {groupProjects.length} 个工程
                </span>
              </div>
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
                {groupProjects.map((p) => (
                  <ProjectCard
                    key={p.id}
                    project={p}
                    busy={busyId === p.id}
                    onScan={() => scan(p)}
                    onSync={() => sync(p)}
                    onEdit={() => openEdit(p)}
                    onDelete={() => setDeleting(p)}
                  />
                ))}
              </div>
            </section>
          ))}
        </div>
      )}

      <ProjectFormDialog open={formOpen} onOpenChange={setFormOpen} project={editing} onSaved={load} />

      <GroupManageDialog open={groupManageOpen} onOpenChange={setGroupManageOpen} onChanged={load} />

      <AlertDialog open={!!deleting} onOpenChange={(v) => !v && setDeleting(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除项目「{deleting?.name}」？</AlertDialogTitle>
            <AlertDialogDescription>
              将删除项目配置、工作区克隆代码与历史扫描记录（含漏洞数据）。此操作不可恢复。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel asChild>
              <Button variant="outline">取消</Button>
            </AlertDialogCancel>
            <AlertDialogAction asChild>
              <Button variant="danger" onClick={remove}>确认删除</Button>
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

/** 间隔格式化：>=60 分钟显示为小时，否则显示分钟 */
function formatInterval(minutes: number): string {
  const m = Math.max(1, Math.round(minutes || 0));
  if (m >= 60) {
    const h = m / 60;
    return (Number.isInteger(h) ? h : h.toFixed(1)) + ' 小时';
  }
  return m + ' 分钟';
}

const TAG_COLORS = ['#CB3837', '#FF8A00', '#3775A9', '#00ADD8', '#D63384', '#7C3AED', '#18A96B', '#E9573F'];

function tagColor(tag: string): string {
  let h = 0;
  for (let i = 0; i < tag.length; i++) h = (h * 31 + tag.charCodeAt(i)) >>> 0;
  return TAG_COLORS[h % TAG_COLORS.length];
}

function ProjectCard({ project, busy, onScan, onSync, onEdit, onDelete }: {
  project: Project; busy: boolean; onScan: () => void; onSync: () => void; onEdit: () => void; onDelete: () => void;
}) {
  const stats = project.lastScanStats ?? {};
  const src = SOURCE_META[project.source] ?? SOURCE_META.LOCAL;
  const running = project.lastScanStatus === 'RUNNING';
  const syncBadge =
    project.syncStatus === 'READY' || project.syncStatus === 'PENDING' && project.source === 'LOCAL' ? (
      <Badge variant="success">代码就绪</Badge>
    ) : project.syncStatus === 'ERROR' ? (
      <Badge variant="danger">同步失败</Badge>
    ) : project.syncStatus === 'SYNCING' ? (
      <Badge variant="warning">同步中</Badge>
    ) : (
      <Badge variant="info">{project.syncStatus ?? '未同步'}</Badge>
    );

  return (
    <Card className="flex flex-col">
      <CardContent className="flex flex-1 flex-col gap-2.5 p-4">
        {/* 标题行：别名 + 分组 + 来源 */}
        <div className="flex items-center gap-1.5">
          <span className="shrink-0 text-primary">{src.icon}</span>
          <span className="truncate text-base font-black text-ink">{project.alias || project.name}</span>
          <Badge variant="outline" className="shrink-0">{src.label}</Badge>
        </div>

        {/* 标签 */}
        {project.tags && project.tags.length > 0 && (
          <div className="flex flex-wrap gap-1">
            {project.tags.map((t) => (
              <span
                key={t}
                className="inline-flex items-center rounded-full border-chunky border-ink px-2 py-0.5 text-[11px] font-black text-white"
                style={{ background: tagColor(t) }}
              >
                {t}
              </span>
            ))}
          </div>
        )}

        {/* 副标题：名称 / 描述 */}
        {(project.alias && project.alias !== project.name || project.description) && (
          <p className="line-clamp-1 break-all text-xs font-semibold text-ink-muted">
            {project.alias && project.alias !== project.name && <span>名称：{project.name}</span>}
            {project.description && (
              <span>{project.alias && project.alias !== project.name ? ' · ' : ''}{project.description}</span>
            )}
          </p>
        )}

        {/* 状态徽章行 */}
        <div className="flex flex-wrap items-center gap-1.5">
          {syncBadge}
          {running ? <Badge variant="warning">扫描中</Badge> : project.lastScanStatus === 'COMPLETED' ? (
            <Badge variant="success">已扫描</Badge>
          ) : project.lastScanStatus === 'FAILED' ? <Badge variant="danger">扫描失败</Badge> : null}
          {project.autoScanEnabled && (
            <Badge variant="outline"><ScanSearch className="h-3 w-3" /> {formatInterval(project.scanIntervalMinutes ?? 180)}自动扫描</Badge>
          )}
          {project.autoSyncEnabled && (
            <Badge variant="outline"><RefreshCw className="h-3 w-3" /> {formatInterval(project.syncIntervalMinutes ?? 60)}同步</Badge>
          )}
          {project.scheduleEnabled && (
            <Badge variant="outline"><Clock className="h-3 w-3" /> {project.scheduleCron}</Badge>
          )}
          {project.emailNotify && (
            <Badge variant="outline"><Mail className="h-3 w-3" /> 邮件报告</Badge>
          )}
        </div>

        {/* 最近扫描统计（固定高度，避免卡片高度抖动） */}
        <div className="min-h-[58px] rounded-md border-2 border-ink/10 bg-paper p-2.5">
          {project.lastScanAt ? (
            <>
              <div className="flex items-center justify-between text-[11px] font-bold text-ink-muted">
                <span>最近扫描 {timeAgo(project.lastScanAt)}</span>
                <span className="ml-2 shrink-0">{formatTime(project.lastScanAt)}</span>
              </div>
              <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1">
                {(['critical', 'high', 'medium', 'low'] as const).map((k) => (
                  <span key={k} className="flex items-center gap-1 text-sm font-black text-ink">
                    <span className="h-3 w-3 shrink-0 rounded-sm border border-ink" style={{ background: SEVERITY_META[k.toUpperCase()].bar }} />
                    {(stats[k] as number) ?? 0}
                  </span>
                ))}
                <span className="ml-auto text-xs font-bold text-ink-muted">合计 {(stats.total as number) ?? 0}</span>
              </div>
            </>
          ) : (
            <div className="flex h-[46px] items-center justify-center text-xs font-semibold text-ink-subtle">尚未扫描</div>
          )}
        </div>

        {/* 操作按钮 */}
        <div className="mt-auto flex items-center gap-1.5 pt-0.5">
          <Button size="sm" variant="default" onClick={onScan} disabled={running || busy}>
            <ScanSearch className="h-3.5 w-3.5" /> {running ? '扫描中' : '扫描'}
          </Button>
          <Button size="sm" variant="outline" onClick={onSync} disabled={busy}>
            <RefreshCw className={`h-3.5 w-3.5 ${busy ? 'animate-spin' : ''}`} /> 同步
          </Button>
          <Button size="sm" variant="outline" onClick={() => (window.location.hash = `#/scans?project=${project.id}`)} title="查看扫描记录">
            <History className="h-3.5 w-3.5" /> 记录
          </Button>
          <div className="ml-auto flex gap-1">
            <Button size="icon" variant="ghost" onClick={onEdit} title="编辑">
              <Pencil className="h-4 w-4" />
            </Button>
            <Button size="icon" variant="ghost" onClick={onDelete} title="删除" className="hover:bg-error hover:text-white">
              <Trash2 className="h-4 w-4" />
            </Button>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}

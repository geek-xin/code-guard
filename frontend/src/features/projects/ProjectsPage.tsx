import { useCallback, useEffect, useState } from 'react';
import { Plus, RefreshCw, ScanSearch, Pencil, Trash2, FolderGit2, Github, Gitlab, FolderOpen, Clock, Mail } from 'lucide-react';
import { api, Project } from '@/lib/api';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
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
  const [loading, setLoading] = useState(true);
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<Project | null>(null);
  const [deleting, setDeleting] = useState<Project | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const load = useCallback(() => {
    api.listProjects().then(setProjects).catch((e) => toast.error(e.message)).finally(() => setLoading(false));
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
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-black text-ink">工程项目</h1>
          <p className="text-sm font-semibold text-ink-muted">共 {projects.length} 个工程 · 支持 GitHub / GitLab / 本地目录</p>
        </div>
        <Button onClick={openCreate}>
          <Plus className="h-4 w-4" /> 添加项目
        </Button>
      </div>

      {projects.length === 0 ? (
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
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
          {projects.map((p) => (
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
      )}

      <ProjectFormDialog open={formOpen} onOpenChange={setFormOpen} project={editing} onSaved={load} />

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
    <Card className="flex flex-col transition-transform hover:-translate-y-0.5">
      <CardContent className="flex flex-1 flex-col gap-3 p-4">
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <span className="text-primary">{src.icon}</span>
              <span className="truncate text-base font-black text-ink">{project.name}</span>
            </div>
            {project.description && <p className="mt-0.5 line-clamp-1 text-xs font-semibold text-ink-muted">{project.description}</p>}
          </div>
          <Badge variant="outline">{src.label}</Badge>
        </div>

        <div className="flex flex-wrap items-center gap-1.5">
          {syncBadge}
          {running ? <Badge variant="warning">扫描中</Badge> : project.lastScanStatus === 'COMPLETED' ? (
            <Badge variant="success">已扫描</Badge>
          ) : project.lastScanStatus === 'FAILED' ? <Badge variant="danger">扫描失败</Badge> : null}
          {project.scheduleEnabled && (
            <Badge variant="outline"><Clock className="h-3 w-3" /> {project.scheduleCron}</Badge>
          )}
          {project.emailNotify && (
            <Badge variant="outline"><Mail className="h-3 w-3" /> 邮件报告</Badge>
          )}
        </div>

        {/* 最近扫描统计 */}
        <div className="rounded-md border-2 border-ink/10 bg-paper p-2.5">
          {project.lastScanAt ? (
            <>
              <div className="flex items-center justify-between text-[11px] font-bold text-ink-muted">
                <span>最近扫描 {timeAgo(project.lastScanAt)}</span>
                <span>{formatTime(project.lastScanAt)}</span>
              </div>
              <div className="mt-1.5 flex items-center gap-3">
                {(['critical', 'high', 'medium', 'low'] as const).map((k) => (
                  <span key={k} className="flex items-center gap-1 text-sm font-black text-ink">
                    <span className="h-3 w-3 rounded-sm border border-ink" style={{ background: SEVERITY_META[k.toUpperCase()].bar }} />
                    {(stats[k] as number) ?? 0}
                  </span>
                ))}
                <span className="ml-auto text-xs font-bold text-ink-muted">合计 {(stats.total as number) ?? 0}</span>
              </div>
            </>
          ) : (
            <div className="py-1 text-center text-xs font-semibold text-ink-subtle">尚未扫描</div>
          )}
        </div>

        <div className="mt-auto flex items-center gap-1.5 pt-1">
          <Button size="sm" variant="default" onClick={onScan} disabled={running || busy}>
            <ScanSearch className="h-3.5 w-3.5" /> {running ? '扫描中' : '扫描'}
          </Button>
          <Button size="sm" variant="outline" onClick={onSync} disabled={busy}>
            <RefreshCw className={`h-3.5 w-3.5 ${busy ? 'animate-spin' : ''}`} /> 同步
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

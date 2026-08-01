import { useEffect, useState } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Project, api } from '@/lib/api';
import { toast } from 'sonner';
import { Loader2 } from 'lucide-react';
import { cn } from '@/lib/utils';

const SOURCES = [
  { key: 'GITHUB', label: 'GitHub 仓库', desc: '通过 HTTPS 克隆' },
  { key: 'GITLAB', label: 'GitLab 仓库', desc: '通过 HTTPS 克隆' },
  { key: 'LOCAL', label: '本地目录', desc: '直接扫描本机目录' },
];

export default function ProjectFormDialog({
  open,
  onOpenChange,
  project,
  onSaved,
}: {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  project?: Project | null;
  onSaved?: () => void;
}) {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [source, setSource] = useState('GITHUB');
  const [repoUrl, setRepoUrl] = useState('');
  const [branch, setBranch] = useState('main');
  const [localPath, setLocalPath] = useState('');
  const [token, setToken] = useState('');
  const [scheduleCron, setScheduleCron] = useState('');
  const [scheduleEnabled, setScheduleEnabled] = useState(false);
  const [autoScan, setAutoScan] = useState(true);
  const [scanInterval, setScanInterval] = useState(180);
  const [autoSync, setAutoSync] = useState(true);
  const [syncInterval, setSyncInterval] = useState(60);
  const [emailNotify, setEmailNotify] = useState(false);
  const [emails, setEmails] = useState('');
  const [enabled, setEnabled] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (open) {
      setName(project?.name ?? '');
      setDescription(project?.description ?? '');
      setSource(project?.source ?? 'GITHUB');
      setRepoUrl(project?.repoUrl ?? '');
      setBranch(project?.branch ?? 'main');
      setLocalPath(project?.localPath ?? '');
      setToken('');
      setScheduleCron(project?.scheduleCron ?? '');
      setScheduleEnabled(project?.scheduleEnabled ?? false);
      setAutoScan(project?.autoScanEnabled ?? true);
      setScanInterval(project?.scanIntervalMinutes ?? 180);
      setAutoSync(project?.autoSyncEnabled ?? true);
      setSyncInterval(project?.syncIntervalMinutes ?? 60);
      setEmailNotify(project?.emailNotify ?? false);
      setEmails(project?.emails?.join(', ') ?? '');
      setEnabled(project?.enabled ?? true);
    }
  }, [open, project]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    try {
      const data: Record<string, unknown> = {
        name,
        description,
        source,
        branch: source === 'LOCAL' ? undefined : branch,
        localPath: source === 'LOCAL' ? localPath : undefined,
        repoUrl: source === 'LOCAL' ? undefined : repoUrl,
        scheduleCron: scheduleEnabled ? scheduleCron : undefined,
        scheduleEnabled,
        autoScanEnabled: autoScan,
        scanIntervalMinutes: scanInterval,
        autoSyncEnabled: autoSync,
        syncIntervalMinutes: syncInterval,
        emailNotify,
        emails: emailNotify ? emails.split(/[,，;\n]/).map((e) => e.trim()).filter(Boolean) : undefined,
        enabled,
      };
      if (token.trim()) data.token = token.trim();
      if (project) {
        await api.updateProject(project.id, data);
        toast.success('项目已更新');
      } else {
        await api.createProject(data);
        toast.success('项目已添加');
      }
      onOpenChange(false);
      onSaved?.();
    } catch (err: any) {
      toast.error(err?.message ?? '保存失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle>{project ? '编辑项目' : '添加项目'}</DialogTitle>
          <DialogDescription>
            支持 GitHub / GitLab 仓库克隆或本地目录扫描；可配置定时扫描（cron）。
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} className="space-y-4">
          <div className="grid grid-cols-3 gap-2">
            {SOURCES.map((s) => (
              <button
                type="button"
                key={s.key}
                onClick={() => setSource(s.key)}
                className={cn(
                  'rounded-md border-chunky p-3 text-left transition-all',
                  source === s.key ? 'border-ink bg-paper-alt shadow-chunky-sm' : 'border-ink/20 bg-paper',
                )}
              >
                <div className="text-sm font-black text-ink">{s.label}</div>
                <div className="mt-0.5 text-[11px] font-semibold text-ink-muted">{s.desc}</div>
              </button>
            ))}
          </div>

          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div className="sm:col-span-2">
              <label className="mb-1 block text-xs font-bold text-ink-muted">项目名称 *</label>
              <Input value={name} onChange={(e) => setName(e.target.value)} required placeholder="例如：web-sim" />
            </div>
            <div className="sm:col-span-2">
              <label className="mb-1 block text-xs font-bold text-ink-muted">描述</label>
              <Textarea value={description} onChange={(e) => setDescription(e.target.value)} placeholder="项目用途说明（可选）" rows={2} />
            </div>
            {source !== 'LOCAL' && (
              <>
                <div className="sm:col-span-2">
                  <label className="mb-1 block text-xs font-bold text-ink-muted">仓库地址 *</label>
                  <Input value={repoUrl} onChange={(e) => setRepoUrl(e.target.value)} required={source !== 'LOCAL'}
                    placeholder="https://github.com/org/repo.git" />
                </div>
                <div>
                  <label className="mb-1 block text-xs font-bold text-ink-muted">分支</label>
                  <Input value={branch} onChange={(e) => setBranch(e.target.value)} placeholder="main" />
                </div>
                <div>
                  <label className="mb-1 block text-xs font-bold text-ink-muted">访问令牌（私有仓库）</label>
                  <Input type="password" value={token} onChange={(e) => setToken(e.target.value)}
                    placeholder={project?.tokenConfigured ? '已配置，留空保持不变' : 'GitHub/GitLab PAT'} />
                </div>
              </>
            )}
            {source === 'LOCAL' && (
              <div className="sm:col-span-2">
                <label className="mb-1 block text-xs font-bold text-ink-muted">本地源码目录 *</label>
                <Input value={localPath} onChange={(e) => setLocalPath(e.target.value)} required={source === 'LOCAL'}
                  placeholder="/Users/you/projects/my-app" />
              </div>
            )}
          </div>

          <div className="rounded-md border-2 border-ink/10 bg-paper p-3">
            <div className="flex items-center justify-between">
              <label className="text-xs font-bold text-ink">定时同步代码</label>
              <button
                type="button"
                onClick={() => setAutoSync(!autoSync)}
                className={cn(
                  'relative h-6 w-11 rounded-full border-chunky border-ink transition-colors',
                  autoSync ? 'bg-secondary' : 'bg-paper',
                )}
              >
                <span className={cn('absolute top-0.5 h-4 w-4 rounded-full border border-ink bg-white transition-all', autoSync ? 'left-6' : 'left-0.5')} />
              </button>
            </div>
            {autoSync && (
              <div className="mt-2 flex items-center gap-2">
                <span className="text-xs font-bold text-ink-muted">间隔（分钟）</span>
                <Input type="number" min={5} value={syncInterval} onChange={(e) => setSyncInterval(Number(e.target.value))} className="w-32" />
                <span className="text-[11px] font-semibold text-ink-muted">默认 60 分钟自动拉取最新代码</span>
              </div>
            )}
          </div>

          <div className="rounded-md border-2 border-ink/10 bg-paper p-3">
            <div className="flex items-center justify-between">
              <label className="text-xs font-bold text-ink">漏洞自动扫描</label>
              <button
                type="button"
                onClick={() => setAutoScan(!autoScan)}
                className={cn(
                  'relative h-6 w-11 rounded-full border-chunky border-ink transition-colors',
                  autoScan ? 'bg-secondary' : 'bg-paper',
                )}
              >
                <span className={cn('absolute top-0.5 h-4 w-4 rounded-full border border-ink bg-white transition-all', autoScan ? 'left-6' : 'left-0.5')} />
              </button>
            </div>
            {autoScan && (
              <div className="mt-2 flex items-center gap-2">
                <span className="text-xs font-bold text-ink-muted">间隔（分钟）</span>
                <Input type="number" min={5} value={scanInterval} onChange={(e) => setScanInterval(Number(e.target.value))} className="w-28" />
                <span className="text-[11px] font-semibold text-ink-muted">默认 180 分钟（3 小时）自动扫描一次</span>
              </div>
            )}
            <div className="mt-2 border-t-2 border-ink/10 pt-2">
              <div className="flex items-center justify-between">
                <label className="text-xs font-bold text-ink-muted">高级：cron 定时扫描（填写后优先）</label>
                <button
                  type="button"
                  onClick={() => setScheduleEnabled(!scheduleEnabled)}
                  className={cn(
                    'relative h-6 w-11 rounded-full border-chunky border-ink transition-colors',
                    scheduleEnabled ? 'bg-secondary' : 'bg-paper',
                  )}
                >
                  <span className={cn('absolute top-0.5 h-4 w-4 rounded-full border border-ink bg-white transition-all', scheduleEnabled ? 'left-6' : 'left-0.5')} />
                </button>
              </div>
              {scheduleEnabled && (
                <div className="mt-2">
                  <Input value={scheduleCron} onChange={(e) => setScheduleCron(e.target.value)}
                    placeholder="cron 表达式，如 0 0 2 * * ? （每天 02:00）" />
                  <p className="mt-1 text-[11px] font-semibold text-ink-muted">
                    <code>0 0 2 * * ?</code> 每天 2 点 · <code>0 */30 * * * ?</code> 每 30 分钟
                  </p>
                </div>
              )}
            </div>
          </div>

          <div className="rounded-md border-2 border-ink/10 bg-paper p-3">
            <div className="flex items-center justify-between">
              <label className="text-xs font-bold text-ink">扫描后邮件推送报告（PDF）</label>
              <button
                type="button"
                onClick={() => setEmailNotify(!emailNotify)}
                className={cn(
                  'relative h-6 w-11 rounded-full border-chunky border-ink transition-colors',
                  emailNotify ? 'bg-secondary' : 'bg-paper',
                )}
              >
                <span className={cn('absolute top-0.5 h-4 w-4 rounded-full border border-ink bg-white transition-all', emailNotify ? 'left-6' : 'left-0.5')} />
              </button>
            </div>
            {emailNotify && (
              <div className="mt-2">
                <Input value={emails} onChange={(e) => setEmails(e.target.value)}
                  placeholder="接收邮箱，多个用逗号/分号分隔，如 a@example.com, b@example.com" />
                <p className="mt-1 text-[11px] font-semibold text-ink-muted">
                  扫描完成后自动将 PDF 报告同时发送到所有邮箱（需先在「设置」中配置 SMTP）
                </p>
              </div>
            )}
          </div>

          <DialogFooter>
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>取消</Button>
            <Button type="submit" disabled={saving}>
              {saving && <Loader2 className="h-4 w-4 animate-spin" />}
              {project ? '保存' : '添加'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

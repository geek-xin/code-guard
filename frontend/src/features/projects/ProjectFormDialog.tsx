import { useCallback, useEffect, useRef, useState } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Project, ProjectGroup, api } from '@/lib/api';
import GroupManageDialog from '@/features/groups/GroupManageDialog';
import { toast } from 'sonner';
import { Loader2 } from 'lucide-react';
import { cn } from '@/lib/utils';
import DirectoryPicker from '@/components/directory/DirectoryPicker';
import { FolderOpen, GitBranch, Tag } from 'lucide-react';

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
  const [alias, setAlias] = useState('');
  const aliasTouched = useRef(false);
  const [description, setDescription] = useState('');
  const [tags, setTags] = useState('');
  const [group, setGroup] = useState('');
  const [source, setSource] = useState('GITHUB');
  const [repoUrl, setRepoUrl] = useState('');
  const [branch, setBranch] = useState('main');
  const [branches, setBranches] = useState<string[]>([]);
  const [defaultBranch, setDefaultBranch] = useState('');
  const [branchLoading, setBranchLoading] = useState(false);
  const [branchError, setBranchError] = useState('');
  const [branchLoaded, setBranchLoaded] = useState(false);
  const [localPath, setLocalPath] = useState('');
  const [token, setToken] = useState('');
  const [scheduleCron, setScheduleCron] = useState('');
  const [scheduleEnabled, setScheduleEnabled] = useState(false);
  const [autoScan, setAutoScan] = useState(true);
  const [agentReview, setAgentReview] = useState(false);
  const [scanInterval, setScanInterval] = useState(180);
  const [autoSync, setAutoSync] = useState(true);
  const [syncInterval, setSyncInterval] = useState(60);
  const [emailNotify, setEmailNotify] = useState(false);
  const [emails, setEmails] = useState('');
  const [enabled, setEnabled] = useState(true);
  const [saving, setSaving] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [groups, setGroups] = useState<ProjectGroup[]>([]);
  const [groupManageOpen, setGroupManageOpen] = useState(false);

  const loadGroups = () => {
    api.listGroups().then(setGroups).catch(() => {});
  };

  useEffect(() => {
    if (open) loadGroups();
  }, [open]);

  /** 从本地目录或仓库地址自动识别项目名（如 web-sim、org/repo.git -> repo） */
  const detectName = (value: string): string | null => {
    const v = value.trim().replace(/\/+$/, '');
    if (!v) return null;
    const seg = v.split(/[/\\]/).filter(Boolean).pop() ?? '';
    const cleaned = seg.replace(/\.git$/, '').replace(/\.(svn|hg)$/, '');
    return cleaned || null;
  };

  useEffect(() => {
    if (open) {
      setName(project?.name ?? '');
      aliasTouched.current = false;
      setAlias(project?.alias ?? project?.name ?? '');
      setTags(project?.tags?.join(', ') ?? '');
      setGroup(project?.group ?? '');
      setDescription(project?.description ?? '');
      setSource(project?.source ?? 'GITHUB');
      setRepoUrl(project?.repoUrl ?? '');
      setBranch(project?.branch ?? 'main');
      setBranches([]);
      setDefaultBranch('');
      setBranchLoaded(false);
      setBranchError('');
      setLocalPath(project?.localPath ?? '');
      setToken('');
      setScheduleCron(project?.scheduleCron ?? '');
      setScheduleEnabled(project?.scheduleEnabled ?? false);
      setAutoScan(project?.autoScanEnabled ?? true);
      setAgentReview(project?.agentReviewEnabled ?? false);
      setScanInterval(project?.scanIntervalMinutes ?? 180);
      setAutoSync(project?.autoSyncEnabled ?? true);
      setSyncInterval(project?.syncIntervalMinutes ?? 60);
      setEmailNotify(project?.emailNotify ?? false);
      setEmails(project?.emails?.join(', ') ?? '');
      setEnabled(project?.enabled ?? true);
    }
  }, [open, project]);

  /** 仓库地址/来源变化后，清空已加载的分支（编辑已有项目时仅在需要时手动加载） */
  useEffect(() => {
    if (!open) return;
    setBranches([]);
    setDefaultBranch('');
    setBranchLoaded(false);
    setBranchError('');
  }, [open, source, repoUrl]);

  /** 加载远端分支（GitHub 公开仓库无需 Token；GitLab 必须提供 Token） */
  const loadBranches = useCallback(async () => {
    const url = repoUrl.trim();
    if (!url) return;
    setBranchLoading(true);
    setBranchError('');
    try {
      const res = await api.listBranches(source, url, token.trim() || undefined);
      setBranches(res.branches);
      setDefaultBranch(res.defaultBranch);
      setBranchLoaded(true);
      setBranch((prev) => (!prev || !res.branches.includes(prev))
        ? (res.defaultBranch || res.branches[0] || '')
        : prev);
    } catch (err: any) {
      setBranches([]);
      setBranchLoaded(true);
      setBranchError(err?.message ?? '获取分支失败');
    } finally {
      setBranchLoading(false);
    }
  }, [source, repoUrl, token]);

  /** 新建项目时，仓库地址/Token 停顿后自动加载分支 */
  useEffect(() => {
    if (!open || source === 'LOCAL' || project) return;
    const url = repoUrl.trim();
    if (!/^https?:\/\/|^git@/.test(url)) return;
    const t = setTimeout(loadBranches, 800);
    return () => clearTimeout(t);
  }, [open, source, repoUrl, token, project, loadBranches]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    try {
      const data: Record<string, unknown> = {
        name,
        alias: alias.trim() || undefined,
        tags: tags ? tags.split(/[,，;\n]/).map((t) => t.trim()).filter(Boolean) : undefined,
        group: group.trim(),
        description,
        source,
        branch: source === 'LOCAL' ? undefined : branch,
        localPath: source === 'LOCAL' ? localPath : undefined,
        repoUrl: source === 'LOCAL' ? undefined : repoUrl,
        scheduleCron: scheduleEnabled ? scheduleCron : undefined,
        scheduleEnabled,
        autoScanEnabled: autoScan,
        scanIntervalMinutes: scanInterval,
        agentReviewEnabled: agentReview,
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
            <div>
              <label className="mb-1 block text-xs font-bold text-ink-muted">项目名称 *</label>
              <Input value={name} disabled required
                className="cursor-not-allowed bg-paper text-ink-muted"
                placeholder="由目录/仓库地址自动识别" />
              <p className="mt-1 text-[11px] font-semibold text-ink-muted">
                自动识别且不可修改；展示名称请填写「别名」
              </p>
            </div>
            <div>
              <label className="mb-1 block text-xs font-bold text-ink-muted">别名 *（展示用）</label>
              <Input value={alias} onChange={(e) => { aliasTouched.current = true; setAlias(e.target.value); }}
                placeholder="例如：接口模拟器" required />
            </div>
            <div>
              <label className="mb-1 block text-xs font-bold text-ink-muted">分组</label>
              <div className="flex items-center gap-2">
                <select
                  value={group}
                  onChange={(e) => setGroup(e.target.value)}
                  className="h-9 w-full rounded-md border-chunky border-ink bg-white px-2 text-sm font-semibold text-ink focus:border-primary focus:outline-none"
                >
                  <option value="">未分组</option>
                  {groups.map((g) => (
                    <option key={g.id} value={g.name}>{g.name}</option>
                  ))}
                </select>
                <Button type="button" variant="outline" size="icon" onClick={() => setGroupManageOpen(true)} title="管理分组">
                  <Tag className="h-4 w-4" />
                </Button>
              </div>
            </div>
            <div>
              <label className="mb-1 block text-xs font-bold text-ink-muted">标签</label>
              <Input value={tags} onChange={(e) => setTags(e.target.value)} placeholder="多个用逗号分隔，如：核心, 高优先级" />
            </div>
            <div className="sm:col-span-2">
              <label className="mb-1 block text-xs font-bold text-ink-muted">描述</label>
              <Textarea value={description} onChange={(e) => setDescription(e.target.value)} placeholder="项目用途说明（可选）" rows={2} />
            </div>
            {source !== 'LOCAL' && (
              <>
                <div className="sm:col-span-2">
                  <label className="mb-1 block text-xs font-bold text-ink-muted">仓库地址 *</label>
                  <Input value={repoUrl} onChange={(e) => {
                    const v = e.target.value;
                    setRepoUrl(v);
                    const n = detectName(v);
                    if (n) {
                      setName(n);
                      if (!aliasTouched.current) setAlias(n);
                    }
                  }} required={source !== 'LOCAL'}
                    placeholder="https://github.com/org/repo.git" />
                </div>
                <div>
                  <label className="mb-1 block text-xs font-bold text-ink-muted">分支</label>
                  <div className="flex items-center gap-2">
                    <Input value={branch} onChange={(e) => setBranch(e.target.value)} placeholder="main" />
                    <Button type="button" variant="outline" size="sm"
                      onClick={loadBranches}
                      disabled={branchLoading || !repoUrl.trim()}>
                      {branchLoading ? <Loader2 className="h-4 w-4 animate-spin" /> : <GitBranch className="h-4 w-4" />}
                      {branchLoading ? '加载中' : '加载分支'}
                    </Button>
                  </div>
                  {branchLoaded && branches.length > 0 && (
                    <div className="mt-2 flex items-center gap-2">
                      <select
                        value={branches.includes(branch) ? branch : ''}
                        onChange={(e) => e.target.value && setBranch(e.target.value)}
                        className="h-8 w-full rounded-md border-chunky border-ink bg-white px-2 text-xs font-semibold text-ink focus:border-primary focus:outline-none"
                      >
                        <option value="">选择分支…</option>
                        {branches.map((b) => (
                          <option key={b} value={b}>{b}{b === defaultBranch ? '（默认）' : ''}</option>
                        ))}
                      </select>
                    </div>
                  )}
                  {branchError && (
                    <p className="mt-1 text-[11px] font-semibold text-red-600">{branchError}</p>
                  )}
                </div>
                <div>
                  <label className="mb-1 block text-xs font-bold text-ink-muted">访问令牌（私有仓库）</label>
                  <Input type="password" value={token} onChange={(e) => setToken(e.target.value)}
                    placeholder={project?.tokenConfigured ? '已配置，留空保持不变' : 'GitHub/GitLab PAT'} />
                  {source === 'GITLAB' && (
                    <p className="mt-1 text-[11px] font-semibold text-ink-muted">
                      GitLab 需填写访问令牌（PAT）才能拉取分支与代码
                    </p>
                  )}
                </div>
              </>
            )}
            {source === 'LOCAL' && (
              <div className="sm:col-span-2">
                <label className="mb-1 block text-xs font-bold text-ink-muted">本地源码目录 *</label>
                <div className="flex items-center gap-2">
                  <Input value={localPath} onChange={(e) => {
                    const v = e.target.value;
                    setLocalPath(v);
                    const n = detectName(v);
                    if (n) setName(n);
                  }} required={source === 'LOCAL'}
                    placeholder="/Users/you/projects/my-app" />
                  <Button type="button" variant="outline" onClick={() => setPickerOpen(true)} title="选择目录">
                    <FolderOpen className="h-4 w-4" /> 浏览
                  </Button>
                </div>
              </div>
            )}
          </div>

          <div className="rounded-md border-2 border-ink/10 bg-paper p-3">
            <div className="flex items-center justify-between">
              <label className="text-xs font-bold text-ink">AI 代码审查</label>
              <button
                type="button"
                onClick={() => setAgentReview(!agentReview)}
                className={cn(
                  'relative h-6 w-11 rounded-full border-chunky border-ink transition-colors',
                  agentReview ? 'bg-secondary' : 'bg-paper',
                )}
              >
                <span className={cn('absolute top-0.5 h-4 w-4 rounded-full border border-ink bg-white transition-all', agentReview ? 'left-6' : 'left-0.5')} />
              </button>
            </div>
            <p className="mt-1 text-[11px] font-semibold text-ink-muted">
              默认关闭；开启后每次扫描自动执行 AI 审查（需在「设置」中配置 API Key）
            </p>
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

          <GroupManageDialog
            open={groupManageOpen}
            onOpenChange={setGroupManageOpen}
            onChanged={loadGroups}
          />

          <DirectoryPicker
            open={pickerOpen}
            onOpenChange={setPickerOpen}
            initialPath={localPath}
            onSelect={(p) => {
              setLocalPath(p);
              const n = detectName(p);
              if (n) setName(n);
            }}
          />

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

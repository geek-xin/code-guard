import { useEffect, useState } from 'react';
import { Plus, Trash2, Pencil, Check, X } from 'lucide-react';
import { api, ProjectGroup } from '@/lib/api';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { toast } from 'sonner';

/**
 * 分组管理：独立维护分组（新增 / 重命名 / 删除），项目表单通过下拉选择分组。
 */
export default function GroupManageDialog({
  open,
  onOpenChange,
  onChanged,
}: {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  onChanged?: () => void;
}) {
  const [groups, setGroups] = useState<ProjectGroup[]>([]);
  const [newName, setNewName] = useState('');
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editName, setEditName] = useState('');

  const load = () => {
    api.listGroups().then(setGroups).catch(() => {});
  };

  useEffect(() => {
    if (open) load();
  }, [open]);

  const create = async () => {
    if (!newName.trim()) return;
    try {
      await api.createGroup(newName.trim());
      setNewName('');
      load();
      onChanged?.();
      toast.success('分组已创建');
    } catch (e: any) {
      toast.error(e?.message ?? '创建失败');
    }
  };

  const rename = async (id: string) => {
    if (!editName.trim()) return;
    try {
      await api.renameGroup(id, editName.trim());
      setEditingId(null);
      load();
      onChanged?.();
      toast.success('分组已重命名');
    } catch (e: any) {
      toast.error(e?.message ?? '重命名失败');
    }
  };

  const remove = async (g: ProjectGroup) => {
    if (!window.confirm(`删除分组「${g.name}」？其下项目将变为未分组。`)) return;
    try {
      await api.deleteGroup(g.id);
      load();
      onChanged?.();
      toast.success('分组已删除');
    } catch (e: any) {
      toast.error(e?.message ?? '删除失败');
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>分组管理</DialogTitle>
          <DialogDescription>分组独立维护，项目表单通过下拉选择；删除分组后相关项目变为未分组</DialogDescription>
        </DialogHeader>

        {/* 新增 */}
        <div className="flex items-center gap-2">
          <Input value={newName} onChange={(e) => setNewName(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && create()} placeholder="新分组名称" />
          <Button variant="outline" size="icon" onClick={create} disabled={!newName.trim()}>
            <Plus className="h-4 w-4" />
          </Button>
        </div>

        {/* 列表 */}
        <div className="max-h-72 space-y-1.5 overflow-y-auto">
          {groups.length === 0 && (
            <div className="py-8 text-center text-sm font-semibold text-ink-subtle">暂无分组，先创建一个</div>
          )}
          {groups.map((g) => (
            <div key={g.id} className="flex items-center gap-2 rounded-md border-chunky border-ink/15 bg-paper px-3 py-2">
              <span className="h-3.5 w-3.5 shrink-0 rounded-full border border-ink" style={{ background: g.color }} />
              {editingId === g.id ? (
                <>
                  <Input value={editName} onChange={(e) => setEditName(e.target.value)}
                    onKeyDown={(e) => e.key === 'Enter' && rename(g.id)} className="h-8 flex-1" autoFocus />
                  <Button variant="outline" size="icon" onClick={() => rename(g.id)} className="h-8 w-8"><Check className="h-3.5 w-3.5" /></Button>
                  <Button variant="ghost" size="icon" onClick={() => setEditingId(null)} className="h-8 w-8"><X className="h-3.5 w-3.5" /></Button>
                </>
              ) : (
                <>
                  <span className="flex-1 text-sm font-bold text-ink">{g.name}</span>
                  <Button variant="ghost" size="icon" onClick={() => { setEditingId(g.id); setEditName(g.name); }} className="h-8 w-8" title="重命名">
                    <Pencil className="h-3.5 w-3.5" />
                  </Button>
                  <Button variant="ghost" size="icon" onClick={() => remove(g)} className="h-8 w-8 hover:bg-error hover:text-white" title="删除">
                    <Trash2 className="h-3.5 w-3.5" />
                  </Button>
                </>
              )}
            </div>
          ))}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>关闭</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

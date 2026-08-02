import { useState } from 'react';
import { Github, Loader2, MessageSquarePlus } from 'lucide-react';
import { api } from '@/lib/api';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { toast } from 'sonner';

/**
 * 问题反馈：一键提交 Issue 到 CodeGuard 平台仓库（GitHub）。
 */
export default function FeedbackDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (v: boolean) => void }) {
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<{ htmlUrl: string; number: number } | null>(null);

  const submit = async () => {
    if (!title.trim() || !body.trim()) {
      toast.error('请填写标题与内容');
      return;
    }
    setSubmitting(true);
    try {
      const res = await api.feedback(title.trim(), body.trim());
      setResult({ htmlUrl: res.htmlUrl, number: res.number });
      toast.success(`已提交 GitHub Issue #${res.number}`);
    } catch (e: any) {
      toast.error(e?.message ?? '提交失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(v) => {
        onOpenChange(v);
        if (!v) {
          setResult(null);
          setTitle('');
          setBody('');
        }
      }}
    >
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <MessageSquarePlus className="h-5 w-5 text-primary" /> 问题反馈（提交 GitHub）
          </DialogTitle>
          <DialogDescription>
            发现平台自身的问题或改进建议？一键提交 Issue 到 CodeGuard 仓库
          </DialogDescription>
        </DialogHeader>
        {result ? (
          <div className="space-y-3">
            <div className="rounded-md border-chunky border-ink bg-secondary/30 p-3 text-sm">
              ✅ 已提交 Issue <b>#{result.number}</b>
            </div>
            <a href={result.htmlUrl} target="_blank" rel="noreferrer"
              className="block rounded-md border-chunky border-ink bg-white p-3 text-center text-sm font-black text-primary shadow-chunky-sm hover:bg-paper-alt">
              打开 Issue 查看
            </a>
            <Button variant="ghost" size="sm" onClick={() => { setResult(null); setTitle(''); setBody(''); }}>
              继续反馈
            </Button>
          </div>
        ) : (
          <div className="space-y-3">
            <div>
              <label className="mb-1 block text-xs font-bold text-ink-muted">标题</label>
              <Input value={title} onChange={(e) => setTitle(e.target.value)}
                placeholder="问题简述，如：扫描记录页打开速度慢" />
            </div>
            <div>
              <label className="mb-1 block text-xs font-bold text-ink-muted">内容</label>
              <Textarea value={body} onChange={(e) => setBody(e.target.value)} rows={5}
                placeholder="详细描述问题现象、复现步骤、期望行为（支持 Markdown）" />
            </div>
            <div className="flex items-center gap-2">
              <Button onClick={submit} disabled={submitting}>
                {submitting ? <Loader2 className="h-4 w-4 animate-spin" /> : <Github className="h-4 w-4" />}
                {submitting ? '提交中...' : '提交 GitHub Issue'}
              </Button>
              <span className="text-[11px] font-semibold text-ink-subtle">使用当前 GitHub 登录账号创建</span>
            </div>
          </div>
        )}
        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>关闭</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

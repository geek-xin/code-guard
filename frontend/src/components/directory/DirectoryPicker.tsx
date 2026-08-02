import { useEffect, useState } from 'react';
import { FolderOpen, ArrowUp, CornerDownLeft, Loader2, HardDrive } from 'lucide-react';
import { api, BrowseResult } from '@/lib/api';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { toast } from 'sonner';
import { cn } from '@/lib/utils';

/**
 * 本地目录选择器：通过后端浏览服务器文件系统，
 * 适配 Windows（C:\...）/ Linux（/home/...）/ macOS（/Users/...）。
 */
export default function DirectoryPicker({
  open,
  onOpenChange,
  initialPath,
  onSelect,
}: {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  initialPath?: string;
  onSelect: (path: string) => void;
}) {
  const [result, setResult] = useState<BrowseResult | null>(null);
  const [pathInput, setPathInput] = useState('');
  const [loading, setLoading] = useState(false);

  const browse = async (path: string) => {
    setLoading(true);
    try {
      const res = await api.browseDirectory(path);
      setResult(res);
      setPathInput(res.current);
    } catch (e: any) {
      toast.error(e?.message ?? '无法浏览目录');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (open) {
      browse(initialPath ?? '');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const enter = (path: string) => {
    setPathInput(path);
    browse(path);
  };

  const goParent = () => {
    if (result?.parent) enter(result.parent);
  };

  const jump = () => {
    if (pathInput.trim()) browse(pathInput.trim());
  };

  const choose = () => {
    if (result?.current) {
      onSelect(result.current);
      onOpenChange(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2"><FolderOpen className="h-5 w-5 text-primary" /> 选择本地目录</DialogTitle>
          <DialogDescription>浏览服务器文件系统，选择源码目录（支持 Windows / Linux / macOS）</DialogDescription>
        </DialogHeader>

        {/* 路径栏 */}
        <div className="flex items-center gap-2">
          <div className="relative flex-1">
            <HardDrive className="absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-subtle" />
            <Input
              value={pathInput}
              onChange={(e) => setPathInput(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && jump()}
              placeholder="输入目录路径后回车"
              className="pl-8 font-mono"
            />
          </div>
          <Button variant="outline" size="icon" onClick={goParent} disabled={!result?.parent || loading} title="上级目录">
            <ArrowUp className="h-4 w-4" />
          </Button>
          <Button variant="outline" size="icon" onClick={jump} disabled={loading} title="跳转">
            <CornerDownLeft className="h-4 w-4" />
          </Button>
        </div>

        {/* 当前路径 */}
        <div className="truncate rounded-md border-2 border-ink/10 bg-paper px-3 py-2 font-mono text-xs font-semibold text-ink-muted">
          {result?.current ?? '...'}
        </div>

        {/* 目录列表 */}
        <div className="max-h-72 overflow-y-auto rounded-md border-chunky border-ink bg-white">
          {loading ? (
            <div className="flex items-center justify-center gap-2 py-10 text-sm font-bold text-ink-muted">
              <Loader2 className="h-4 w-4 animate-spin" /> 读取目录...
            </div>
          ) : result && result.dirs.length === 0 ? (
            <div className="py-10 text-center text-sm font-semibold text-ink-subtle">当前目录下没有子文件夹</div>
          ) : (
            <div className="divide-y divide-ink/10">
              {result?.dirs.map((d) => (
                <button
                  key={d.path}
                  type="button"
                  onClick={() => enter(d.path)}
                  className="flex w-full items-center gap-2.5 px-3 py-2.5 text-left text-sm font-semibold text-ink transition-colors hover:bg-paper-alt"
                >
                  <FolderOpen className="h-4 w-4 shrink-0 text-primary" />
                  <span className="truncate">{d.name}</span>
                  <span className="ml-auto truncate pl-3 font-mono text-[11px] text-ink-subtle">{d.path}</span>
                </button>
              ))}
            </div>
          )}
        </div>

        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>取消</Button>
          <Button onClick={choose} disabled={!result || loading}>
            <CornerDownLeft className="h-4 w-4" /> 选择当前目录
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

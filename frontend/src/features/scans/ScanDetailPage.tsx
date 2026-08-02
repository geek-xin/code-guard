import { useCallback, useEffect, useRef, useState } from 'react';
import { ArrowLeft, Square, Loader2, FileSearch, Sparkles, Database, ShieldCheck, GitBranch, Radar, FileText, Download } from 'lucide-react';
import { api, ScanFinding, ScanRecord, token, AgentReviewStatus } from '@/lib/api';
import MarkdownView from '@/components/markdown/MarkdownView';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs';
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '@/components/ui/table';
import { SEVERITY_META, ENGINE_LABEL, STAGE_LABEL, formatTime, cn } from '@/lib/utils';
import { toast } from 'sonner';

const STAGES = ['CLONE', 'DETECT', 'SCA', 'SAST', 'AGENT'];
const STAGE_ICON: Record<string, React.ReactNode> = {
  CLONE: <GitBranch className="h-4 w-4" />,
  DETECT: <Radar className="h-4 w-4" />,
  SCA: <Database className="h-4 w-4" />,
  SAST: <ShieldCheck className="h-4 w-4" />,
  AGENT: <Sparkles className="h-4 w-4" />,
};

interface LiveFinding extends ScanFinding {}

export default function ScanDetailPage({ scanId }: { scanId: string }) {
  const [scan, setScan] = useState<ScanRecord | null>(null);
  const [findings, setFindings] = useState<LiveFinding[]>([]);
  const [liveCount, setLiveCount] = useState<Record<string, number>>({});
  const [severityFilter, setSeverityFilter] = useState('');
  const [engineFilter, setEngineFilter] = useState('');
  const [selected, setSelected] = useState<ScanFinding | null>(null);
  const [agentReview, setAgentReview] = useState('');
  const [loading, setLoading] = useState(true);
  const [reportOpen, setReportOpen] = useState(false);
  const [agentLoading, setAgentLoading] = useState(false);
  const [agentStatus, setAgentStatus] = useState<AgentReviewStatus['status']>('IDLE');
  const [agentThinking, setAgentThinking] = useState('');
  const agentAbort = useRef<AbortController | null>(null);
  const mounted = useRef(true);

  const loadStatic = useCallback(async () => {
    try {
      const s = await api.getScan(scanId);
      setScan(s);
      if (s.agentReview) setAgentReview(s.agentReview);
      if (s.status !== 'RUNNING') {
        const fs = await api.getFindings(scanId, { limit: 500 });
        setFindings(fs);
        const counts: Record<string, number> = {};
        for (const f of fs) counts[f.severity] = (counts[f.severity] ?? 0) + 1;
        setLiveCount(counts);
      }
    } catch (e: any) {
      toast.error(e?.message ?? '加载失败');
    } finally {
      setLoading(false);
    }
  }, [scanId]);

  useEffect(() => {
    mounted.current = true;
    loadStatic();
    // SSE 实时流（fetch 方式，可携带 Authorization）
    const ctrl = new AbortController();
    const t = token();
    let buffer = '';
    async function stream() {
      try {
        const resp = await fetch(`/api/scans/${scanId}/events`, {
          headers: t ? { Authorization: `Bearer ${t}` } : {},
          signal: ctrl.signal,
        });
        if (!resp.ok || !resp.body) return;
        const reader = resp.body.getReader();
        const decoder = new TextDecoder();
        while (mounted.current) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          let idx;
          while ((idx = buffer.indexOf('\n\n')) >= 0) {
            const chunk = buffer.slice(0, idx);
            buffer = buffer.slice(idx + 2);
            const dataLine = chunk.split('\n').find((l) => l.startsWith('data:'));
            if (!dataLine) continue;
            try {
              const evt = JSON.parse(dataLine.slice(5).trim());
              handleEvent(evt);
            } catch {
              /* ignore */
            }
          }
        }
      } catch {
        /* aborted or connection closed */
      }
    }
    stream();
    return () => {
      mounted.current = false;
      ctrl.abort();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scanId]);

  function handleEvent(evt: { type: string; data?: any }) {
    if (evt.type === 'finding' && evt.data) {
      const f = evt.data as ScanFinding;
      setFindings((prev) => [f, ...prev].filter((x, i, arr) => arr.findIndex((y) => y.id === x.id) === i).slice(0, 600));
      setLiveCount((prev) => ({ ...prev, [f.severity]: (prev[f.severity] ?? 0) + 1 }));
    } else if (evt.type === 'agent-review' && evt.data?.content) {
      setAgentReview(evt.data.content);
    } else if (evt.type === 'stage' && evt.data) {
      setScan((prev) => {
        if (!prev) return prev;
        const stages = { ...(prev.stages ?? {}) };
        const st = stages[evt.data.stage] ?? { status: evt.data.status };
        stages[evt.data.stage] = { ...st, status: evt.data.status, message: evt.data.message };
        return { ...prev, stages };
      });
    } else if (evt.type === 'progress' && evt.data) {
      setScan((prev) => {
        if (!prev) return prev;
        const stages = { ...(prev.stages ?? {}) };
        stages[evt.data.stage] = {
          status: 'RUNNING',
          current: evt.data.current,
          total: evt.data.total,
          message: evt.data.message,
        };
        return { ...prev, stages };
      });
    } else if (evt.type === 'done' && evt.data) {
      setScan((prev) => ({ ...(prev as ScanRecord), status: evt.data.status, summary: evt.data.summary ?? (prev?.summary ?? {}) }));
      loadStatic();
    } else if (evt.type === 'error' && evt.data) {
      toast.error(evt.data.message ?? '扫描出错');
      loadStatic();
    }
  }

  async function connectAgentEvents() {
    agentAbort.current?.abort();
    const ctrl = new AbortController();
    agentAbort.current = ctrl;
    const t = token();
    let buffer = '';
    try {
      const resp = await fetch(`/api/scans/${scanId}/agent-review/events`, {
        headers: t ? { Authorization: `Bearer ${t}` } : {},
        signal: ctrl.signal,
      });
      if (!resp.ok || !resp.body) return;
      const reader = resp.body.getReader();
      const decoder = new TextDecoder();
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let idx;
        while ((idx = buffer.indexOf('\n\n')) >= 0) {
          const chunk = buffer.slice(0, idx);
          buffer = buffer.slice(idx + 2);
          const dataLine = chunk.split('\n').find((l) => l.startsWith('data:'));
          if (!dataLine) continue;
          try {
            const evt = JSON.parse(dataLine.slice(5).trim());
            handleAgentEvent(evt);
          } catch { /* ignore */ }
        }
      }
    } catch { /* aborted */ }
  }

  function handleAgentEvent(evt: { type: string; data?: any }) {
    if (evt.type === 'thinking' && evt.data?.delta) {
      setAgentThinking((prev) => (prev + evt.data.delta).slice(-20000));
    } else if (evt.type === 'status' && evt.data?.status) {
      setAgentStatus(evt.data.status);
    } else if (evt.type === 'replay' && evt.data?.thinking) {
      setAgentThinking(evt.data.thinking.slice(-20000));
    } else if (evt.type === 'done' && evt.data?.status === 'COMPLETED') {
      setAgentStatus('COMPLETED');
      if (evt.data.content) setAgentReview(evt.data.content);
      toast.success('AI 审查意见已生成');
    } else if (evt.type === 'done' && evt.data?.status === 'FAILED') {
      setAgentStatus('FAILED');
      toast.error('AI 审查失败');
    } else if (evt.type === 'error' && evt.data?.message) {
      setAgentStatus('FAILED');
      toast.error(evt.data.message);
    } else if (evt.type === 'cancelled') {
      setAgentStatus('CANCELLED');
      toast.info('AI 审查已停止');
    }
  }

  async function runAgentReview() {
    setAgentLoading(true);
    setAgentThinking('');
    try {
      const res = await api.startAgentReview(scanId);
      setAgentStatus(res.status);
      if (res.status === 'COMPLETED' && res.content) {
        setAgentReview(res.content);
        toast.success('AI 审查意见已生成');
      } else if (res.status === 'RUNNING') {
        connectAgentEvents();
      }
    } catch (e: any) {
      setAgentStatus('FAILED');
      toast.error(e?.message ?? 'AI 审查失败');
    } finally {
      setAgentLoading(false);
    }
  }

  // 进入页面时检查是否已有审查任务在运行（跨会话可见）
  useEffect(() => {
    api.agentReviewStatus(scanId).then((s) => {
      if (s.status === 'RUNNING') {
        setAgentStatus('RUNNING');
        setAgentThinking(s.thinking ?? '');
        connectAgentEvents();
      } else if (s.status === 'COMPLETED' && s.content) {
        setAgentStatus('COMPLETED');
        setAgentReview(s.content);
      }
    }).catch(() => {});
    return () => agentAbort.current?.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scanId]);

  async function downloadReport(format: string) {
    const t = token();
    try {
      const resp = await fetch(`/api/scans/${scanId}/report?format=${format}`, {
        headers: t ? { Authorization: `Bearer ${t}` } : {},
      });
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const blob = await resp.blob();
      const url = URL.createObjectURL(blob);
      if (format === 'html') {
        window.open(url, '_blank');
      } else {
        const a = document.createElement('a');
        a.href = url;
        a.download = `codeguard-report-${scan?.projectName ?? 'scan'}.${format === 'word' ? 'docx' : format === 'excel' ? 'xlsx' : format}`;
        document.body.appendChild(a);
        a.click();
        a.remove();
      }
      setTimeout(() => URL.revokeObjectURL(url), 5000);
      toast.success('报告已开始下载');
    } catch (e: any) {
      toast.error('下载失败：' + (e?.message ?? '未知错误'));
    }
  }

  const stop = async () => {
    try {
      await api.stopScan(scanId);
      toast.success('已发送停止指令');
    } catch (e: any) {
      toast.error(e?.message ?? '停止失败');
    }
  };

  const summary = scan?.summary ?? {};
  const running = scan?.status === 'RUNNING';
  const filtered = findings.filter(
    (f) => (!severityFilter || f.severity === severityFilter) && (!engineFilter || f.engine === engineFilter),
  );

  if (loading && !scan) {
    return <div className="py-20 text-center text-sm font-semibold text-ink-muted">加载中...</div>;
  }
  if (!scan) {
    return (
      <div className="py-20 text-center">
        <p className="text-sm font-bold text-ink-muted">扫描记录不存在</p>
        <Button variant="outline" className="mt-4" onClick={() => (window.location.hash = '#/scans')}>返回扫描记录</Button>
      </div>
    );
  }

  return (
    <div className="space-y-5">
      {/* 头部（滚动时固定） */}
      <div className="sticky top-0 z-30 -mx-2 flex items-center justify-between gap-3 rounded-md bg-paper/95 px-2 py-2 backdrop-blur-sm">
        <div className="flex items-center gap-3">
          <Button variant="outline" size="icon" onClick={() => window.history.back()}>
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <div>
            <h1 className="flex items-center gap-2 text-2xl font-black text-ink">
              {scan.projectName}
              <StatusBadge status={scan.status} />
            </h1>
            <p className="text-sm font-semibold text-ink-muted">
              {scan.trigger === 'SCHEDULED' ? '定时扫描' : '手动扫描'} · {scan.scope} · {formatTime(scan.startedAt)}
              {scan.durationMs != null && ` · 耗时 ${(scan.durationMs / 1000).toFixed(1)}s`}
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {!running && scan.status === 'COMPLETED' && (
            <Button variant="outline" onClick={() => setReportOpen(true)}>
              <FileText className="h-4 w-4" /> 生成报告
            </Button>
          )}
          {running && (
            <Button variant="danger" onClick={stop}>
              <Square className="h-4 w-4" /> 停止扫描
            </Button>
          )}
        </div>
      </div>

      {/* 阶段进度 */}
      <Card>
        <CardHeader>
          <CardTitle>扫描进度</CardTitle>
          <CardDescription>{scan.message ?? '正在执行扫描流水线'}</CardDescription>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-5">
            {STAGES.map((s) => {
              const st = scan.stages?.[s] ?? { status: 'PENDING' };
              const status = st.status ?? 'PENDING';
              const pct = st.total ? Math.round(((st.current ?? 0) / st.total) * 100) : status === 'COMPLETED' ? 100 : 0;
              return (
                <div
                  key={s}
                  className={cn(
                    'rounded-md border-chunky p-3 transition-all',
                    status === 'RUNNING' && 'border-ink bg-paper-alt shadow-chunky-sm',
                    status === 'COMPLETED' && 'border-ink bg-secondary/40',
                    status === 'FAILED' && 'border-ink bg-error/10',
                    status === 'PENDING' && 'border-ink/20 bg-paper',
                  )}
                >
                  <div className="flex items-center gap-2">
                    <span className={cn('flex h-7 w-7 items-center justify-center rounded-md border-chunky border-ink bg-white',
                      status === 'RUNNING' && 'text-primary', status === 'COMPLETED' && 'text-success')}>
                      {status === 'RUNNING' ? <Loader2 className="h-4 w-4 animate-spin" /> : STAGE_ICON[s]}
                    </span>
                    <span className="text-sm font-black text-ink">{STAGE_LABEL[s]}</span>
                  </div>
                  <div className="mt-2 h-3 w-full overflow-hidden rounded-sm border border-ink bg-white">
                    <div
                      className={cn('h-full transition-all', status === 'FAILED' ? 'bg-error' : 'bg-primary')}
                      style={{ width: `${status === 'PENDING' ? 0 : Math.max(pct, status === 'RUNNING' ? 8 : 0)}%` }}
                    />
                  </div>
                  <div className="mt-1.5 truncate text-[11px] font-bold text-ink-muted">
                    {status === 'PENDING' ? '等待中' : status === 'RUNNING' ? (st.message ?? `进行中 ${st.current ?? 0}/${st.total ?? '-'}`) : status === 'COMPLETED' ? '完成' : '失败'}
                  </div>
                </div>
              );
            })}
          </div>

        </CardContent>
      </Card>

      {/* 实时统计（页面级 sticky，滚动漏洞列表时固定显示） */}
      <div className="sticky top-[62px] z-20 mb-4 grid grid-cols-2 gap-2 rounded-md border-chunky border-ink bg-white p-2 shadow-chunky-sm sm:grid-cols-5">
        {(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'] as const).map((sev) => (
          <div key={sev} className="flex items-center gap-2 rounded-md border-2 border-ink/10 bg-paper px-2.5 py-1.5">
            <span className="h-4 w-4 shrink-0 rounded-sm border border-ink" style={{ background: SEVERITY_META[sev].bar }} />
            <span className="text-xs font-bold text-ink-muted">{SEVERITY_META[sev].label}</span>
            <span className="ml-auto text-lg font-black text-ink">{liveCount[sev] ?? summary[sev.toLowerCase()] ?? 0}</span>
          </div>
        ))}
      </div>

      {/* 漏洞列表 + Agent 审查 */}
      <Tabs defaultValue="findings">
        <TabsList>
          <TabsTrigger value="findings">
            <FileSearch className="mr-1 h-4 w-4" /> 漏洞列表（{filtered.length}）
          </TabsTrigger>
          <TabsTrigger value="agent">
            <Sparkles className="mr-1 h-4 w-4" /> AI 审查意见
            {agentReview && <Badge variant="success" className="ml-1">已生成</Badge>}
          </TabsTrigger>
        </TabsList>

        <TabsContent value="findings">
          <Card>
            <CardContent className="p-4">
              <div className="mb-3 flex flex-wrap items-center gap-2">
                <select
                  value={severityFilter}
                  onChange={(e) => setSeverityFilter(e.target.value)}
                  className="h-8 rounded-md border-chunky border-ink bg-white px-2 text-xs font-bold text-ink focus:border-primary focus:outline-none"
                >
                  <option value="">全部等级</option>
                  {['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'].map((s) => (
                    <option key={s} value={s}>{SEVERITY_META[s].label}</option>
                  ))}
                </select>
                <select
                  value={engineFilter}
                  onChange={(e) => setEngineFilter(e.target.value)}
                  className="h-8 rounded-md border-chunky border-ink bg-white px-2 text-xs font-bold text-ink focus:border-primary focus:outline-none"
                >
                  <option value="">全部引擎</option>
                  {['SCA', 'SAST', 'AGENT'].map((e) => (
                    <option key={e} value={e}>{ENGINE_LABEL[e]}</option>
                  ))}
                </select>
                {running && <span className="ml-auto flex items-center gap-1.5 text-xs font-bold text-primary"><Loader2 className="h-3.5 w-3.5 animate-spin" /> 实时更新中</span>}
              </div>
              {filtered.length === 0 ? (
                <div className="py-12 text-center text-sm font-semibold text-ink-subtle">
                  {running ? '扫描进行中，暂无发现...' : '未发现漏洞'}
                </div>
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead className="w-20">等级</TableHead>
                      <TableHead>漏洞 / 描述</TableHead>
                      <TableHead className="w-28">引擎</TableHead>
                      <TableHead className="w-44">位置</TableHead>
                      <TableHead className="w-24">编号</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {filtered.slice(0, 200).map((f) => (
                      <TableRow key={f.id} className="cursor-pointer" onClick={() => setSelected(f)}>
                        <TableCell>
                          <Badge variant={SEVERITY_META[f.severity ?? 'INFO']?.badge ?? 'info'}>
                            {SEVERITY_META[f.severity ?? 'INFO']?.label ?? f.severity}
                          </Badge>
                        </TableCell>
                        <TableCell>
                          <div className="font-bold text-ink">{f.title}</div>
                          {f.dependencyName && (
                            <div className="text-xs font-semibold text-ink-muted">
                              {f.dependencyName} {f.dependencyVersion}
                              {f.fixedVersion && <span className="text-success"> → {f.fixedVersion}</span>}
                            </div>
                          )}
                        </TableCell>
                        <TableCell><Badge variant="outline">{ENGINE_LABEL[f.engine] ?? f.engine}</Badge></TableCell>
                        <TableCell className="max-w-[180px]">
                          <div className="truncate text-xs font-semibold text-ink-muted">{f.file}</div>
                          {f.line != null && <div className="text-xs font-bold text-primary">第 {f.line} 行</div>}
                        </TableCell>
                        <TableCell>
                          {f.vulnId && <span className="font-mono text-xs font-bold text-ink">{f.vulnId}</span>}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
              {filtered.length > 200 && (
                <p className="mt-2 text-center text-xs font-semibold text-ink-subtle">仅显示前 200 条，请使用筛选缩小范围</p>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="agent">
          <Card>
            <CardContent className="p-5">
              {agentStatus === 'RUNNING' ? (
                <div>
                  <div className="mb-3 flex items-center gap-2">
                    <Loader2 className="h-4 w-4 animate-spin text-primary" />
                    <span className="text-sm font-black text-ink">AI 审查进行中...</span>
                    <Badge variant="warning">实时思考</Badge>
                  </div>
                  {/* 实时思考过程（终端风格，自动滚动） */}
                  <div className="overflow-y-auto rounded-md border-chunky border-ink bg-ink p-3" style={{ maxHeight: 420 }}>
                    <pre className="whitespace-pre-wrap font-mono text-xs leading-relaxed text-secondary">
                      {agentThinking || '正在连接...'}
                    </pre>
                  </div>
                </div>
              ) : agentStatus === 'CANCELLED' ? (
                <div className="py-12 text-center">
                  <Square className="mx-auto mb-2 h-10 w-10 text-ink-subtle" />
                  <p className="text-sm font-bold text-ink-muted">AI 审查已停止</p>
                  <div className="mt-3 flex items-center justify-center gap-2">
                    <Button size="sm" onClick={runAgentReview} disabled={agentLoading}>
                      {agentLoading ? <Loader2 className="mr-1 h-4 w-4 animate-spin" /> : <Sparkles className="mr-1 h-4 w-4" />}
                      重新生成
                    </Button>
                  </div>
                </div>
              ) : agentStatus === 'FAILED' ? (
                <div className="py-12 text-center">
                  <Sparkles className="mx-auto mb-2 h-10 w-10 text-error" />
                  <p className="text-sm font-bold text-ink-muted">AI 审查失败，请检查配置后重试</p>
                  <div className="mt-3 flex items-center justify-center gap-2">
                    <Button size="sm" onClick={runAgentReview} disabled={agentLoading}>
                      {agentLoading ? <Loader2 className="mr-1 h-4 w-4 animate-spin" /> : <Sparkles className="mr-1 h-4 w-4" />}
                      重新生成
                    </Button>
                    <Button variant="outline" size="sm" onClick={() => (window.location.hash = '#/settings')}>
                      前往设置
                    </Button>
                  </div>
                </div>
              ) : agentReview ? (
                <MarkdownView content={agentReview} />
              ) : (
                <div className="py-12 text-center">
                  <Sparkles className="mx-auto mb-2 h-10 w-10 text-ink-subtle" />
                  <p className="text-sm font-bold text-ink-muted">本次扫描未生成 AI 审查意见</p>
                  <p className="mt-1 text-xs font-semibold text-ink-subtle">
                    在「设置」中配置 OpenAI 兼容接口的 API Key 后自动启用；已配置时可直接对本次扫描生成审查
                  </p>
                  <div className="mt-3 flex items-center justify-center gap-2">
                    {!running && scan.status === 'COMPLETED' && (
                      <Button size="sm" onClick={runAgentReview} disabled={agentLoading}>
                        {agentLoading ? <Loader2 className="mr-1 h-4 w-4 animate-spin" /> : <Sparkles className="mr-1 h-4 w-4" />}
                        {agentLoading ? '启动中...' : '立即生成审查意见'}
                      </Button>
                    )}
                    <Button variant="outline" size="sm" onClick={() => (window.location.hash = '#/settings')}>
                      <Sparkles className="mr-1 h-4 w-4" /> 前往设置
                    </Button>
                  </div>
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>

      {/* 漏洞详情 */}
      <Dialog open={!!selected} onOpenChange={(v) => !v && setSelected(null)}>
        <DialogContent className="max-w-3xl overflow-x-hidden">
          {selected && (
            <>
              <DialogHeader>
                <DialogTitle className="flex items-start gap-2 pr-6">
                  <Badge variant={SEVERITY_META[selected.severity ?? 'INFO']?.badge ?? 'info'} className="mt-0.5 shrink-0">
                    {SEVERITY_META[selected.severity ?? 'INFO']?.label}
                  </Badge>
                  <span className="min-w-0 flex-1 break-words leading-snug">{selected.title}</span>
                </DialogTitle>
                <DialogDescription className="break-all">
                  {ENGINE_LABEL[selected.engine] ?? selected.engine} · {selected.category}
                  {selected.vulnId && ` · ${selected.vulnId}`}
                  {selected.cwe && ` · ${selected.cwe}`}
                </DialogDescription>
              </DialogHeader>
              <div className="min-w-0 space-y-3 text-sm">
                {selected.dependencyName && (
                  <div className="break-all rounded-md border-2 border-ink/10 bg-paper p-3">
                    <span className="font-bold text-ink">依赖：</span>
                    <span className="font-mono text-ink">{selected.dependencyName}@{selected.dependencyVersion}</span>
                    {selected.fixedVersion && (
                      <span className="ml-2 rounded border border-ink bg-secondary px-1.5 py-0.5 text-xs font-black text-ink">
                        修复版本 {selected.fixedVersion}
                      </span>
                    )}
                  </div>
                )}
                {selected.file && (
                  <div className="break-all rounded-md border-2 border-ink/10 bg-paper p-3">
                    <span className="font-bold text-ink">位置：</span>
                    <span className="font-mono text-ink">{selected.file}</span>
                    {selected.line != null && <span className="ml-1 font-bold text-primary">第 {selected.line} 行</span>}
                  </div>
                )}
                {selected.codeSnippet && (
                  <div className="overflow-x-auto rounded-md border-chunky border-ink bg-ink p-3">
                    <pre className="text-xs leading-relaxed text-paper">{selected.codeSnippet}</pre>
                  </div>
                )}
                {selected.description && (
                  <div>
                    <div className="mb-1 font-black text-ink">问题描述</div>
                    <p className="break-words whitespace-pre-wrap rounded-md border-2 border-ink/10 bg-paper p-3 leading-relaxed text-ink">
                      {selected.description}
                    </p>
                  </div>
                )}
                {selected.solution && (
                  <div>
                    <div className="mb-1 font-black text-success">解决方案</div>
                    <div className="break-words rounded-md border-2 border-ink/10 bg-secondary/20 p-3 leading-relaxed text-ink">
                      {selected.solution}
                    </div>
                  </div>
                )}
                {selected.references && selected.references.length > 0 && (
                  <div>
                    <div className="mb-1 font-black text-ink">参考链接</div>
                    <div className="flex flex-wrap gap-2">
                      {selected.references.slice(0, 6).map((r, i) => (
                        <a key={i} href={r} target="_blank" rel="noreferrer"
                          className="break-all rounded border-chunky border-ink bg-white px-2 py-1 text-xs font-bold text-primary hover:bg-paper-alt">
                          {r.replace(/^https?:\/\//, '').slice(0, 40)}
                        </a>
                      ))}
                    </div>
                  </div>
                )}
                {selected.confidence != null && (
                  <div className="text-xs font-semibold text-ink-subtle">置信度：{selected.confidence}%</div>
                )}
              </div>
            </>
          )}
        </DialogContent>
      </Dialog>

      {/* 报告导出 */}
      <Dialog open={reportOpen} onOpenChange={setReportOpen}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>生成扫描报告</DialogTitle>
            <DialogDescription>
              {scan.projectName} · {formatTime(scan.startedAt)} · 共 {(summary.total as number) ?? 0} 个发现
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <div className="grid grid-cols-2 gap-2">
              {[
                { fmt: 'pdf', icon: <FileText className="h-5 w-5 text-error" />, name: 'PDF 报告', sub: '.pdf · 正式版式' },
                { fmt: 'word', icon: <Download className="h-5 w-5 text-primary" />, name: 'Word 报告', sub: '.docx · 可编辑' },
                { fmt: 'excel', icon: <Download className="h-5 w-5 text-success" />, name: 'Excel 报告', sub: '.xlsx · 明细表格' },
                { fmt: 'html', icon: <FileText className="h-5 w-5 text-accent" />, name: 'HTML 报告', sub: '新窗口 · 可打印' },
              ].map((item) => (
                <button
                  key={item.fmt}
                  type="button"
                  onClick={() => downloadReport(item.fmt)}
                  className="flex items-center gap-3 rounded-md border-chunky border-ink bg-white p-3 text-left shadow-chunky-sm transition-all hover:bg-paper-alt active:translate-x-[2px] active:translate-y-[2px] active:shadow-none"
                >
                  {item.icon}
                  <div className="flex-1">
                    <div className="text-sm font-black text-ink">{item.name}</div>
                    <div className="text-xs font-semibold text-ink-muted">{item.sub}</div>
                  </div>
                </button>
              ))}
            </div>
            <div className="grid grid-cols-2 gap-2">
              {[
                { fmt: 'markdown', icon: <Download className="h-5 w-5 text-secondary" />, name: 'Markdown 报告', sub: '.md · 接入 Wiki' },
                { fmt: 'json', icon: <Download className="h-5 w-5 text-gold" />, name: 'JSON 原始数据', sub: '结构化 · 二次分析' },
              ].map((item) => (
                <button
                  key={item.fmt}
                  type="button"
                  onClick={() => downloadReport(item.fmt)}
                  className="flex items-center gap-3 rounded-md border-chunky border-ink bg-white p-3 text-left shadow-chunky-sm transition-all hover:bg-paper-alt active:translate-x-[2px] active:translate-y-[2px] active:shadow-none"
                >
                  {item.icon}
                  <div className="flex-1">
                    <div className="text-sm font-black text-ink">{item.name}</div>
                    <div className="text-xs font-semibold text-ink-muted">{item.sub}</div>
                  </div>
                </button>
              ))}
            </div>
          </div>
        </DialogContent>
      </Dialog>
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

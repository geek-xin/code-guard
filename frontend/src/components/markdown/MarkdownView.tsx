import { Fragment, ReactNode } from 'react';
import { cn } from '@/lib/utils';

/**
 * 轻量 Markdown 渲染：标题 / 加粗 / 行内代码 / 代码块 / 列表 / 段落。
 * 用于 AI 审查意见等富文本展示，无第三方依赖。
 */
export default function MarkdownView({ content, className }: { content: string; className?: string }) {
  const blocks = splitBlocks(content);
  return (
    <div className={cn('space-y-3 text-sm leading-relaxed text-ink', className)}>
      {blocks.map((block, i) => (
        <Fragment key={i}>{renderBlock(block, i)}</Fragment>
      ))}
    </div>
  );
}

function splitBlocks(content: string): string[] {
  // 按代码块分割，保留标记
  const parts: string[] = [];
  let current = '';
  let inCode = false;
  const lines = content.split('\n');
  for (const line of lines) {
    if (line.trim().startsWith('```')) {
      if (current.trim() || inCode) {
        parts.push(current);
        current = '';
      }
      parts.push(line);
      inCode = !inCode;
      continue;
    }
    current += line + '\n';
  }
  if (current.trim()) parts.push(current);
  return parts;
}

function renderBlock(block: string, key: number): ReactNode {
  const trimmed = block.trim();
  if (trimmed.startsWith('```')) {
    const code = block.replace(/^```[^\n]*\n?/, '').replace(/```\s*$/, '').trimEnd();
    return (
      <pre className="overflow-x-auto rounded-md border-chunky border-ink bg-ink p-3 font-mono text-xs leading-relaxed text-paper">
        {code}
      </pre>
    );
  }
  return (
    <div className="space-y-2">
      {trimmed.split('\n').map((line, i) => {
        if (!line.trim()) return <div key={i} className="h-2" />;
        return renderLine(line, key + '-' + i);
      })}
    </div>
  );
}

function renderLine(line: string, key: string): ReactNode {
  const t = line.trim();
  // 标题
  const heading = t.match(/^(#{1,4})\s+(.*)$/);
  if (heading) {
    const level = heading[1].length;
    const cls = level === 1 ? 'text-xl font-black text-ink'
      : level === 2 ? 'text-lg font-black text-ink border-b-2 border-ink/10 pb-1'
        : level === 3 ? 'text-base font-black text-ink'
          : 'text-sm font-bold text-ink';
    return <div key={key} className={cls}>{inline(heading[2])}</div>;
  }
  // 列表
  const ul = t.match(/^[-*•]\s+(.*)$/);
  if (ul) {
    return (
      <div key={key} className="flex gap-2 pl-1">
        <span className="mt-0.5 h-1.5 w-1.5 shrink-0 rounded-full bg-primary" />
        <span className="min-w-0">{inline(ul[1])}</span>
      </div>
    );
  }
  const ol = t.match(/^\d+[.)]\s+(.*)$/);
  if (ol) {
    return (
      <div key={key} className="flex gap-2 pl-1">
        <span className="w-5 shrink-0 text-right font-black text-primary">{t.match(/^\d+/)?.[0]}.</span>
        <span className="min-w-0">{inline(ol[1])}</span>
      </div>
    );
  }
  // 引用
  if (t.startsWith('>')) {
    return (
      <div key={key} className="rounded-r-md border-l-4 border-primary bg-paper-alt px-3 py-1.5 text-ink-muted">
        {inline(t.replace(/^>\s?/, ''))}
      </div>
    );
  }
  // 分隔线
  if (/^[-*_]{3,}$/.test(t)) {
    return <hr key={key} className="border-ink/10" />;
  }
  // 普通段落（支持行内加粗/代码/斜体）
  return <p key={key} className="leading-relaxed">{inline(line)}</p>;
}

/** 行内样式：代码、加粗、链接 */
function inline(text: string): ReactNode {
  const parts: ReactNode[] = [];
  const regex = /(`[^`]+`|\*\*[^*]+\*\*|\[[^\]]+\]\([^)]+\))/g;
  let last = 0;
  let m: RegExpExecArray | null;
  let idx = 0;
  while ((m = regex.exec(text)) !== null) {
    if (m.index > last) parts.push(text.slice(last, m.index));
    const token = m[0];
    if (token.startsWith('`')) {
      parts.push(
        <code key={idx++} className="rounded border border-ink/15 bg-paper px-1 py-0.5 font-mono text-[0.85em] text-primary">
          {token.slice(1, -1)}
        </code>,
      );
    } else if (token.startsWith('**')) {
      parts.push(<strong key={idx++} className="font-black text-ink">{token.slice(2, -2)}</strong>);
    } else if (token.startsWith('[')) {
      const link = token.match(/\[([^\]]+)\]\(([^)]+)\)/);
      if (link) {
        parts.push(
          <a key={idx++} href={link[2]} target="_blank" rel="noreferrer"
            className="break-all font-semibold text-primary underline decoration-1 underline-offset-2 hover:text-primary-hover">
            {link[1]}
          </a>,
        );
      }
    }
    last = m.index + token.length;
  }
  if (last < text.length) parts.push(text.slice(last));
  return <>{parts}</>;
}

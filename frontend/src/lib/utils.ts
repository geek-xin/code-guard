import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function formatTime(iso?: string | null): string {
  if (!iso) return '-';
  try {
    const d = new Date(iso);
    return d.toLocaleString('zh-CN', { hour12: false });
  } catch {
    return iso;
  }
}

export function timeAgo(iso?: string | null): string {
  if (!iso) return '-';
  const t = new Date(iso).getTime();
  const diff = Date.now() - t;
  const m = Math.floor(diff / 60000);
  if (m < 1) return '刚刚';
  if (m < 60) return `${m} 分钟前`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h} 小时前`;
  const d = Math.floor(h / 24);
  return `${d} 天前`;
}

export const SEVERITY_META: Record<string, { label: string; badge: 'critical' | 'high' | 'medium' | 'low' | 'info'; color: string; bar: string }> = {
  CRITICAL: { label: '严重', badge: 'critical', color: 'text-error', bar: '#E23B2E' },
  HIGH: { label: '高危', badge: 'high', color: 'text-primary', bar: '#F45113' },
  MEDIUM: { label: '中危', badge: 'medium', color: 'text-ink', bar: '#F6C445' },
  LOW: { label: '低危', badge: 'low', color: 'text-ink', bar: '#7BC4E8' },
  INFO: { label: '提示', badge: 'info', color: 'text-ink-muted', bar: '#B9B2A9' },
};

export const ENGINE_LABEL: Record<string, string> = {
  SCA: '依赖漏洞 (SCA)',
  SAST: '静态分析 (SAST)',
  AGENT: 'Review Agent',
};

export const STAGE_LABEL: Record<string, string> = {
  CLONE: '拉取代码',
  DETECT: '环境探测',
  SCA: '依赖扫描',
  SAST: '静态分析',
  AGENT: 'AI 审查',
};

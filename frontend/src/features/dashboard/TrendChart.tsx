import { TrendPoint } from '@/lib/api';
import { SEVERITY_META } from '@/lib/utils';

const SEVS = ['critical', 'high', 'medium', 'low'] as const;

export function TrendChart({ data }: { data: TrendPoint[] }) {
  if (!data.length) {
    return <div className="py-16 text-center text-sm font-semibold text-ink-subtle">暂无趋势数据</div>;
  }
  const rawMax = Math.max(1, ...data.map((d) => d.total));
  // Y 轴刻度圆整到整齐步长
  const yStep = rawMax <= 50 ? 10 : rawMax <= 200 ? 50 : rawMax <= 500 ? 100 : 200;
  const max = Math.ceil(rawMax / yStep) * yStep;
  const W = 720;
  const H = 220;
  const padL = 34;
  const padB = 26;
  const padT = 14;
  const innerW = W - padL - 10;
  const innerH = H - padT - padB;
  // 柱状条在宽度内均匀分布（即使只有 2 个点也不会偏两端）
  const n = data.length;
  const cell = innerW / n;
  const barW = Math.max(14, Math.min(36, cell * 0.62));

  return (
    <div className="w-full">
      <svg viewBox={`0 0 ${W} ${H}`} className="block w-full" preserveAspectRatio="xMidYMid meet">
        {/* 网格线 */}
        {[0, 0.25, 0.5, 0.75, 1].map((r) => {
          const y = padT + innerH - innerH * r;
          return (
            <g key={r}>
              <line x1={padL} y1={y} x2={W - 10} y2={y} stroke="#161616" strokeOpacity={0.08} strokeDasharray="4 4" />
              <text x={padL - 6} y={y + 3} textAnchor="end" fontSize={10} fill="#9B948C" fontWeight={600}>
                {Math.round(max * r)}
              </text>
            </g>
          );
        })}
        {/* 堆叠柱状图 */}
        {data.map((d, i) => {
          const x = padL + i * cell + (cell - barW) / 2;
          let acc = 0;
          return (
            <g key={d.date}>
              {SEVS.map((sev) => {
                const v = d[sev] ?? 0;
                const h = (v / max) * innerH;
                const y = padT + innerH - acc * (innerH / max) - h;
                acc += v;
                return (
                  <rect key={sev} x={x} y={y} width={barW} height={Math.max(0, h - 1)} rx={2}
                    fill={SEVERITY_META[sev.toUpperCase()].bar} stroke="#161616" strokeWidth={1} />
                );
              })}
              <text x={x + barW / 2} y={H - 8} textAnchor="middle" fontSize={10} fill="#6F6A64" fontWeight={600}>
                {d.date.slice(5)}
              </text>
            </g>
          );
        })}
      </svg>
      <div className="mt-2 flex flex-wrap items-center gap-4 text-xs font-bold text-ink-muted">
        {SEVS.map((s) => (
          <span key={s} className="flex items-center gap-1.5">
            <span className="h-3 w-3 rounded-sm border border-ink" style={{ background: SEVERITY_META[s.toUpperCase()].bar }} />
            {SEVERITY_META[s.toUpperCase()].label}
          </span>
        ))}
        <span className="ml-auto text-ink-subtle">按扫描完成日期汇总 · 点击卡片内图表无交互</span>
      </div>
    </div>
  );
}

import React from 'react'

/**
 * 轻量 SVG 堆叠柱状图（无第三方依赖）。
 * props:
 *   data=[{ label, counts: { OVERLOAD, TIMEOUT, OTHER }, total }]
 *   height
 * 类别色固定（色板已通过色盲/色差校验；柱顶直标总数 + 页内明细表兜底对比度）
 */
const CATS = [
  { key: 'OVERLOAD', name: '电机过载', color: '#fa8c16' },
  { key: 'TIMEOUT', name: '通讯超时', color: '#1677ff' },
  { key: 'OTHER', name: '执行失败', color: '#722ed1' }
]

export default function BarChart({ data, height = 300 }) {
  const width = 900
  const pad = { l: 44, r: 16, t: 28, b: 36 }
  const max = Math.max(1, ...data.map(d => d.total || 0))
  // Y 轴取整刻度
  const tickMax = Math.ceil(max / 2) * 2
  const ticks = Array.from({ length: 5 }, (_, i) => Math.round(tickMax * i / 4))

  const innerW = width - pad.l - pad.r
  const innerH = height - pad.t - pad.b
  const slot = innerW / Math.max(1, data.length)
  const barW = Math.min(72, slot * 0.52)
  const y = (v) => pad.t + innerH - (v / tickMax) * innerH

  return (
    <svg viewBox={`0 0 ${width} ${height}`} width="100%" height={height} style={{ display: 'block' }}>
      {/* recessive 网格 */}
      {ticks.map((v, i) => (
        <g key={i}>
          <line x1={pad.l} x2={width - pad.r} y1={y(v)} y2={y(v)} stroke="#f0f0f0" />
          <text x={pad.l - 8} y={y(v) + 4} textAnchor="end" fontSize="11" fill="#999">{v}</text>
        </g>
      ))}
      <line x1={pad.l} x2={width - pad.r} y1={pad.t + innerH} y2={pad.t + innerH} stroke="#d9d9d9" />

      {data.map((d, i) => {
        const cx = pad.l + slot * i + slot / 2
        let acc = 0
        return (
          <g key={d.label}>
            {/* 堆叠段，段间 2px 表面留白 */}
            {CATS.map((c, ci) => {
              const v = d.counts?.[c.key] || 0
              if (!v) return null
              const h = (v / tickMax) * innerH
              const yy = y(acc + v) + (acc > 0 ? 2 : 0)
              acc += v
              const r = ci === 0 ? 3 : 0
              return (
                <rect key={c.key} x={cx - barW / 2} y={yy} width={barW} height={Math.max(0, h - (acc > v ? 2 : 0))}
                      fill={c.color} rx={r} ry={r}>
                  <title>{`${d.label} · ${c.name} ${v} 次`}</title>
                </rect>
              )
            })}
            {/* 柱顶直标总数 */}
            {d.total > 0 && (
              <text x={cx} y={y(d.total) - 8} textAnchor="middle" fontSize="13" fontWeight={600} fill="#595959">
                {d.total}
              </text>
            )}
            <text x={cx} y={height - 12} textAnchor="middle" fontSize="12" fill="#595959">{d.label}</text>
          </g>
        )
      })}

      {data.every(d => !d.total) && (
        <text x={width / 2} y={height / 2} textAnchor="middle" fontSize="13" fill="#999">近 30 天暂无故障</text>
      )}
    </svg>
  )
}

export function FaultLegend() {
  return (
    <div style={{ display: 'flex', gap: 18, justifyContent: 'center', marginBottom: 4, fontSize: 12, color: '#595959' }}>
      {CATS.map(c => (
        <span key={c.key} style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          <i style={{ width: 10, height: 10, borderRadius: 2, background: c.color, display: 'inline-block' }} />
          {c.name}
        </span>
      ))}
    </div>
  )
}

export { CATS as FAULT_CATS }

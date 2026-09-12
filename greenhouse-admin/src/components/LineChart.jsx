import React from 'react'

const PALETTE = ['#2e7d32', '#1677ff', '#fa8c16', '#722ed1']

/**
 * 轻量 SVG 折线图，无第三方图表依赖。
 * props: series=[{name, unit, color?, points:[{t:epochMs, v:number}]}], height
 */
export default function LineChart({ series, height = 320, thresholds = [] }) {
  const width = 900
  const pad = { l: 56, r: 16, t: 16, b: 28 }
  const all = series.flatMap(s => s.points.map(p => p.v))
  const allT = series.flatMap(s => s.points.map(p => p.t))
  if (all.length < 2) {
    return <div style={{ height, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#999' }}>
      暂无足够数据
    </div>
  }
  let min = Math.min(...all)
  let max = Math.max(...all)
  if (min === max) { min -= 1; max += 1 }
  const padV = (max - min) * 0.1
  min -= padV; max += padV
  const t0 = Math.min(...allT); const t1 = Math.max(...allT)

  const x = (t) => pad.l + ((t - t0) / Math.max(1, t1 - t0)) * (width - pad.l - pad.r)
  const y = (v) => pad.t + (1 - (v - min) / (max - min)) * (height - pad.t - pad.b)

  const gridCount = 4
  const ticks = Array.from({ length: gridCount + 1 }, (_, i) => min + (max - min) * i / gridCount)

  const fmtTime = (ms) => {
    const d = new Date(ms)
    return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`
  }

  return (
    <svg viewBox={`0 0 ${width} ${height}`} width="100%" height={height} style={{ display: 'block' }}>
      {ticks.map((v, i) => (
        <g key={i}>
          <line x1={pad.l} x2={width - pad.r} y1={y(v)} y2={y(v)} stroke="#f0f0f0" />
          <text x={pad.l - 8} y={y(v) + 4} textAnchor="end" fontSize="11" fill="#999">{v.toFixed(1)}</text>
        </g>
      ))}
      {thresholds.map((th, i) => (
        <g key={`th${i}`}>
          <line x1={pad.l} x2={width - pad.r} y1={y(th.value)} y2={y(th.value)}
                stroke={th.color || '#ff4d4f'} strokeDasharray="6 4" strokeWidth="1.2" />
          <text x={width - pad.r - 4} y={y(th.value) - 4} textAnchor="end" fontSize="10"
                fill={th.color || '#ff4d4f'}>{th.label}</text>
        </g>
      ))}
      {series.map((s, si) => {
        const color = s.color || PALETTE[si % PALETTE.length]
        const d = s.points.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(p.t)},${y(p.v)}`).join('')
        const last = s.points[s.points.length - 1]
        return (
          <g key={s.name}>
            <path d={d} fill="none" stroke={color} strokeWidth="2" />
            <circle cx={x(last.t)} cy={y(last.v)} r="3.5" fill={color} />
          </g>
        )
      })}
      <text x={pad.l} y={height - 8} fontSize="11" fill="#999">{fmtTime(t0)}</text>
      <text x={width - pad.r} y={height - 8} textAnchor="end" fontSize="11" fill="#999">{fmtTime(t1)}</text>
    </svg>
  )
}

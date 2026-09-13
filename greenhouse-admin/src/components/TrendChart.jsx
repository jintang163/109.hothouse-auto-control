import React, { useState } from 'react'

/**
 * 轻量 SVG 按天趋势线（无第三方依赖）。
 * props: points=[{date:'MM-dd'|'yyyy-MM-dd', value:number}], height, color
 * 单系列无需 legend（标题已表明含义）；hover 显示十字线与数值。
 */
export default function TrendChart({ points, height = 280, color = '#1677ff' }) {
  const width = 900
  const pad = { l: 44, r: 16, t: 20, b: 32 }
  const [hover, setHover] = useState(null)

  if (!points || points.length < 2) {
    return <div style={{ height, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#999' }}>
      暂无足够数据
    </div>
  }
  const values = points.map(p => p.value)
  let max = Math.max(1, ...values)
  max = Math.ceil(max / 2) * 2
  const innerW = width - pad.l - pad.r
  const innerH = height - pad.t - pad.b
  const n = points.length
  const x = (i) => pad.l + (n === 1 ? innerW / 2 : (i / (n - 1)) * innerW)
  const y = (v) => pad.t + innerH - (v / max) * innerH

  const ticks = Array.from({ length: 5 }, (_, i) => Math.round(max * i / 4))
  const dPath = points.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(i)},${y(p.value)}`).join('')
  const area = `${dPath} L${x(n - 1)},${y(0)} L${x(0)},${y(0)} Z`
  const labelIdx = new Set([0, Math.floor((n - 1) / 2), n - 1])
  const onMove = (e) => {
    const rect = e.currentTarget.getBoundingClientRect()
    const px = (e.clientX - rect.left) / rect.width * width
    const i = Math.round(((px - pad.l) / innerW) * (n - 1))
    setHover(Math.max(0, Math.min(n - 1, i)))
  }

  return (
    <svg viewBox={`0 0 ${width} ${height}`} width="100%" height={height} style={{ display: 'block' }}
         onMouseMove={onMove} onMouseLeave={() => setHover(null)}>
      {ticks.map((v, i) => (
        <g key={i}>
          <line x1={pad.l} x2={width - pad.r} y1={y(v)} y2={y(v)} stroke="#f0f0f0" />
          <text x={pad.l - 8} y={y(v) + 4} textAnchor="end" fontSize="11" fill="#999">{v}</text>
        </g>
      ))}
      <path d={area} fill={color} opacity={0.08} />
      <path d={dPath} fill="none" stroke={color} strokeWidth={2} />
      {points.map((p, i) => labelIdx.has(i) && (
        <text key={i} x={x(i)} y={height - 10} textAnchor="middle" fontSize="11" fill="#999">
          {String(p.date).substring(5)}
        </text>
      ))}
      {hover != null && (
        <g>
          <line x1={x(hover)} x2={x(hover)} y1={pad.t} y2={pad.t + innerH} stroke="#bfbfbf" strokeDasharray="4 3" />
          <circle cx={x(hover)} cy={y(points[hover].value)} r={4} fill="#fff" stroke={color} strokeWidth={2} />
          <g transform={`translate(${Math.min(x(hover) + 8, width - 120)},${Math.max(y(points[hover].value) - 26, pad.t)})`}>
            <rect width="112" height="30" rx="4" fill="rgba(0,0,0,.72)" />
            <text x="8" y="13" fontSize="10" fill="#fff">{points[hover].date}</text>
            <text x="8" y="25" fontSize="11" fill="#fff" fontWeight="600">故障 {points[hover].value} 次</text>
          </g>
        </g>
      )}
    </svg>
  )
}

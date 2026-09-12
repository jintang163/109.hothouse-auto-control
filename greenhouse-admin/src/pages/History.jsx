import React, { useEffect, useState } from 'react'
import { Card, Select, Radio, Button, Space, message } from 'antd'
import LineChart from '../components/LineChart.jsx'
import { api } from '../api'

const METRICS = [
  { key: 'temperature', name: '温度 (℃)', color: '#f5222d' },
  { key: 'humidity', name: '湿度 (%)', color: '#1677ff' },
  { key: 'light', name: '光照 (lux)', color: '#faad14' },
  { key: 'co2', name: 'CO₂ (ppm)', color: '#722ed1' }
]
const RANGES = [
  { label: '近30分钟', ms: 30 * 60_000 },
  { label: '近1小时', ms: 3600_000 },
  { label: '近6小时', ms: 6 * 3600_000 }
]

/** 历史曲线：多指标叠加查看（各指标量纲不同时分开展示更清晰，默认温度单曲线） */
export default function History({ greenhouseId }) {
  const [metric, setMetric] = useState('temperature')
  const [rangeMs, setRangeMs] = useState(RANGES[0].ms)
  const [points, setPoints] = useState([])
  const [strategy, setStrategy] = useState(null)
  const [loading, setLoading] = useState(false)

  const load = async () => {
    setLoading(true)
    try {
      const to = Date.now()
      const rows = await api.history(greenhouseId, metric, to - rangeMs, to)
      setPoints(rows.map(r => ({ t: new Date(r.recordedAt.replace(' ', 'T')).getTime(), v: r.value })))
      const s = await api.strategy(greenhouseId)
      setStrategy(s)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [greenhouseId, metric, rangeMs])

  const thresholds = []
  if (metric === 'temperature' && strategy) {
    thresholds.push({ value: strategy.tempHigh, label: `降温线 ${strategy.tempHigh}℃`, color: '#fa8c16' })
    thresholds.push({ value: strategy.tempCritical, label: `告警线 ${strategy.tempCritical}℃`, color: '#ff4d4f' })
    thresholds.push({ value: strategy.tempRecover, label: `恢复点 ${strategy.tempRecover}℃`, color: '#52c41a' })
  }

  const meta = METRICS.find(m => m.key === metric)

  return (
    <Card title="环境历史曲线" style={{ margin: 16 }}
          extra={
            <Space wrap>
              <Select value={metric} onChange={setMetric} style={{ width: 140 }}
                options={METRICS.map(m => ({ value: m.key, label: m.name }))} />
              <Radio.Group value={rangeMs} onChange={e => setRangeMs(e.target.value)} optionType="button"
                options={RANGES.map(r => ({ value: r.ms, label: r.label }))} />
              <Button onClick={load}>刷新</Button>
            </Space>
          }>
      <LineChart loading={loading} series={[{ name: meta.name, color: meta.color, points }]} thresholds={thresholds} />
      <div style={{ color: '#999', marginTop: 8 }}>
        共 {points.length} 个数据点{points.length > 0 ?
          `，最新 ${points[points.length - 1].v.toFixed(1)} ${meta.name.split(' ')[1]}` : ''}
      </div>
    </Card>
  )
}

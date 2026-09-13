import React, { useEffect, useMemo, useRef, useState } from 'react'
import {
  Card, Col, Row, Statistic, Tag, Button, Space, Select, Modal, message, Badge, Tooltip, Alert
} from 'antd'
import {
  ThunderboltFilled, CloudServerOutlined, FieldTimeOutlined, ReloadOutlined, ToolOutlined
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { api, openRealtimeSocket } from '../api'

const METRIC_META = {
  temperature: { name: '温度', unit: '℃', color: '#f5222d', icon: '🌡' },
  humidity: { name: '湿度', unit: '%', color: '#1677ff', icon: '💧' },
  light: { name: '光照', unit: 'lux', color: '#faad14', icon: '☀' },
  co2: { name: 'CO₂', unit: 'ppm', color: '#722ed1', icon: '🫧' }
}

const DEVICE_META = {
  FAN: { name: '风机', on: 'ON', onText: '运行', offText: '停止', icon: '🌀' },
  WET_CURTAIN: { name: '湿帘', on: 'OPEN', onText: '开启', offText: '关闭', icon: '🚿' },
  SHADE_NET: { name: '遮阳网', on: 'OPEN', onText: '展开', offText: '收拢', icon: '⛱' }
}

const MODE_TAG = { AUTO: 'green', MANUAL: 'orange', SCHEDULE: 'blue' }
const MODE_LABEL = { AUTO: '自动', MANUAL: '手动', SCHEDULE: '定时' }

/** 实时监控大屏 */
export default function Dashboard({ greenhouseId }) {
  const navigate = useNavigate()
  const [overview, setOverview] = useState(null)
  const [latest, setLatest] = useState({})   // metric -> {value,time}
  const [devices, setDevices] = useState([])
  const [reminders, setReminders] = useState([])
  const [wsOnline, setWsOnline] = useState(false)
  const ghRef = useRef(greenhouseId)
  ghRef.current = greenhouseId

  const loadReminders = async (ghId) => {
    try { setReminders(await api.maintenanceReminders(ghId)) } catch { /* 运维模块不可用时静默 */ }
  }

  const refresh = async () => {
    const o = await api.overview(greenhouseId)
    setOverview(o)
    setLatest(o.latest || {})
    setDevices(o.devices || [])
  }

  useEffect(() => { refresh() }, [greenhouseId])
  useEffect(() => { loadReminders(greenhouseId) }, [greenhouseId])

  // 全局实时事件：按当前大棚过滤
  useEffect(() => {
    const close = openRealtimeSocket((event, data) => {
      if (event === 'sensor') {
        if (data.greenhouseId !== ghRef.current) return
        const t = data.time
        setLatest(prev => {
          const next = { ...prev }
          Object.entries(data.values || {}).forEach(([k, v]) => next[k] = { value: v, time: t })
          return next
        })
      } else if (event === 'device') {
        if (data.greenhouseId !== ghRef.current) return
        setDevices(prev => prev.map(d => d.sn === data.sn ? { ...d, ...data } : d))
      } else if (event === 'command') {
        if (data.greenhouseId !== ghRef.current) return
        message.info({
          content: `指令 ${data.deviceSn} ${data.action}：${statusLabel(data.status)}`
            + (data.retryCount ? `（重试 ${data.retryCount}）` : ''),
          duration: 2
        })
      } else if (event === 'alarm') {
        if (data.greenhouseId && data.greenhouseId !== ghRef.current) return
        message.warning({ content: `告警：${data.message}`, duration: 5 })
        setOverview(o => o ? { ...o, openAlarms: (o.openAlarms || 0) + 1 } : o)
      } else if (event === 'maintenance') {
        loadReminders()
      }
    }, setWsOnline)
    return close
  }, [])

  const strategy = overview?.strategy
  const gh = overview?.greenhouse
  const overdueList = reminders.filter(r => r.status === 'OVERDUE')
  const dueSoonList = reminders.filter(r => r.status === 'DUE_SOON')

  const switchMode = async (mode) => {
    await api.setMode(greenhouseId, mode)
    message.success(`已切换为${MODE_LABEL[mode]}模式`)
    refresh()
  }

  const manualControl = (device) => {
    const meta = DEVICE_META[device.type]
    const isOn = device.state === meta.on
    const action = isOn ? (device.type === 'FAN' ? 'OFF' : 'CLOSE') : (device.type === 'FAN' ? 'ON' : 'OPEN')
    Modal.confirm({
      title: `${isOn ? '关闭' : '开启'}${meta.name}？`,
      content: `设备：${device.name}（${device.sn}）。手动指令仍受安全互锁保护。`,
      okText: '确认下发',
      cancelText: '取消',
      onOk: async () => {
        try {
          await api.control({ deviceSn: device.sn, action, operator: 'admin' })
          message.success('指令已受理，等待设备回执')
        } catch (e) {
          message.error(e.message)   // 互锁拒绝等
        }
      }
    })
  }

  const thresholds = useMemo(() => {
    if (!strategy) return []
    return [
      { value: strategy.tempHigh, label: `降温线 ${strategy.tempHigh}℃`, color: '#fa8c16' },
      { value: strategy.tempCritical, label: `告警线 ${strategy.tempCritical}℃`, color: '#ff4d4f' }
    ]
  }, [strategy])

  if (!overview) return <Card loading />

  return (
    <div className="dashboard-bg" style={{ padding: 16 }}>
      <Alert
        className="alarm-banner"
        type={wsOnline ? 'success' : 'warning'}
        showIcon
        banner
        message={wsOnline ? '实时通道已连接，数据秒级刷新' : '实时通道未连接，正在重连…（当前显示可能滞后）'}
      />
      {overdueList.length > 0 ? (
        <Alert
          type="error" showIcon banner icon={<ToolOutlined />} style={{ cursor: 'pointer' }}
          onClick={() => navigate('/maintenance')}
          message={`${overdueList.length} 台设备已到保养期：${overdueList.map(r => r.deviceName).join('、')}，点击前往处理`}
        />
      ) : dueSoonList.length > 0 ? (
        <Alert
          type="warning" showIcon banner icon={<ToolOutlined />} style={{ cursor: 'pointer' }}
          onClick={() => navigate('/maintenance')}
          message={`${dueSoonList.length} 台设备临近保养：${dueSoonList.map(r => r.deviceName).join('、')}，点击安排保养`}
        />
      ) : null}
      <Row gutter={[12, 12]} align="middle" style={{ marginBottom: 12 }}>
        <Col flex="auto">
          <Space size="large" wrap>
            <h2 style={{ margin: 0 }}>{gh.name}
              <Tag color={MODE_TAG[gh.mode]} style={{ marginLeft: 12 }}>{MODE_LABEL[gh.mode]}</Tag>
              <Tag icon={<CloudServerOutlined />} color={devices.every(d => d.online) && devices.length ? 'green' : 'red'}>
                网关 {devices.some(d => d.online) ? '在线' : '离线'}
              </Tag>
            </h2>
            <span style={{ color: '#888' }}>{gh.location} · 作物：{gh.crop}</span>
          </Space>
        </Col>
        <Col>
          <Space>
            <span><span className={`ws-dot ${wsOnline ? 'ws-online' : 'ws-offline'}`} />实时</span>
            <Select
              value={gh.mode}
              onChange={switchMode}
              style={{ width: 110 }}
              options={[
                { value: 'AUTO', label: '自动模式' },
                { value: 'MANUAL', label: '手动模式' },
                { value: 'SCHEDULE', label: '定时模式' }
              ]}
            />
            <Tooltip title="刷新总览"><Button icon={<ReloadOutlined />} onClick={refresh} /></Tooltip>
            <Badge count={overview.openAlarms} size="small">
              <Button danger icon={<ThunderboltFilled />}>未处理告警</Button>
            </Badge>
          </Space>
        </Col>
      </Row>

      <Row gutter={[12, 12]}>
        {Object.entries(METRIC_META).map(([key, meta]) => {
          const v = latest[key]
          const alarm = key === 'temperature' && strategy && v && v.value >= strategy.tempCritical
          return (
            <Col xs={12} md={6} key={key}>
              <Card className="metric-card" style={alarm ? { borderColor: '#ff4d4f', boxShadow: '0 0 8px rgba(255,77,79,.3)' } : {}}>
                <div className="metric-name">{meta.icon} {meta.name}
                  {key === 'temperature' && strategy &&
                    <Tooltip title={`降温启动线 ${strategy.tempHigh}℃ / 恢复点 ${strategy.tempRecover}℃`}>
                      <FieldTimeOutlined style={{ marginLeft: 6, color: '#bbb' }} />
                    </Tooltip>}
                </div>
                <div className="metric-value" style={{ color: alarm ? '#ff4d4f' : meta.color }}>
                  {v ? v.value.toFixed(1) : '--'}
                  <span className="metric-unit">{meta.unit}</span>
                </div>
                <small style={{ color: '#aaa' }}>{v ? v.time?.substring(11, 19) : '暂无数据'}</small>
              </Card>
            </Col>
          )
        })}
      </Row>

      <Card title="执行器联动状态（点击卡片可手动控阀，自动模式下亦受互锁保护）"
            style={{ marginTop: 12 }} size="small">
        <Row gutter={[12, 12]}>
          {devices.filter(d => DEVICE_META[d.type]).map(d => {
            const meta = DEVICE_META[d.type]
            const isOn = d.state === meta.on
            return (
              <Col xs={24} sm={8} key={d.sn}>
                <Card className="device-card" size="small"
                      style={{ borderLeft: `4px solid ${isOn ? '#52c41a' : '#d9d9d9'}` }}>
                  <Row align="middle" gutter={8}>
                    <Col flex="auto">
                      <Space direction="middle" size={4}>
                        <span style={{ fontSize: 22 }}>{meta.icon}</span>
                        <div>
                          <div style={{ fontWeight: 600 }}>{d.name}</div>
                          <Space size={4}>
                            <Tag color={d.online ? 'green' : 'default'}>{d.online ? '在线' : '离线'}</Tag>
                            <span className="device-state-big" style={{ color: isOn ? '#52c41a' : '#999' }}>
                              {d.state ? (isOn ? meta.onText : meta.offText) : '未知'}
                            </span>
                          </Space>
                        </div>
                      </Space>
                    </Col>
                    <Col>
                      <Button type={isOn ? 'default' : 'primary'} danger={isOn}
                              disabled={!d.online}
                              onClick={() => manualControl(d)}>
                        {isOn ? '停止/关闭' : '启动/开启'}
                      </Button>
                    </Col>
                  </Row>
                </Card>
              </Col>
            )
          })}
        </Row>
      </Card>

      <Row gutter={[12, 12]} style={{ marginTop: 12 }}>
        <Col xs={24} md={12}>
          <Card title="策略快照" size="small">
            {strategy ? (
              <Space wrap>
                <Tag color="orange">降温 ≥ {strategy.tempHigh}℃，恢复 ≤ {strategy.tempRecover}℃</Tag>
                <Tag color="red">越限 ≥ {strategy.tempCritical}℃</Tag>
                <Tag color="blue">加湿 ≤ {strategy.humiLow}%，恢复 ≥ {strategy.humiRecover}%</Tag>
                <Tag color="gold">遮阳 ≥ {strategy.lightHigh}lux</Tag>
                <Tag>防抖 {strategy.debounceSec}s</Tag>
                <Tag>冷却 {strategy.cooldownSec}s</Tag>
                <Tag color={strategy.enabled ? 'green' : 'default'}>
                  策略{strategy.enabled ? '启用' : '停用'}
                </Tag>
              </Space>
            ) : <span style={{ color: '#999' }}>未配置策略</span>}
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card title="安全互锁" size="small">
            <Statistic
              value="湿帘未开 → 禁启风机；风机运行 → 禁关湿帘"
              valueStyle={{ fontSize: 15, color: '#cf1322' }}
            />
            <small style={{ color: '#999' }}>
              联动链按序执行：开时先湿帘后风机，停时先风机后湿帘；任一环节回执失败则中止后续动作并告警。
            </small>
          </Card>
        </Col>
      </Row>
    </div>
  )
}

function statusLabel(s) {
  return { PENDING: '待下发', SENT: '已下发待回执', ACKED: '已回执', QUEUED_OFFLINE: '离线缓存', FAILED: '失败' }[s] || s
}

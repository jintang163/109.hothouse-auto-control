import React, { useEffect, useState } from 'react'
import {
  Card, Tabs, Table, Tag, Button, Progress, Statistic, Row, Col, Modal, Form, Input,
  InputNumber, Space, Switch, message, Alert, List, Popconfirm, Tooltip
} from 'antd'
import { ReloadOutlined, ThunderboltFilled } from '@ant-design/icons'
import { api } from '../api'
import BarChart, { FaultLegend } from '../components/BarChart.jsx'
import TrendChart from '../components/TrendChart.jsx'

const STATUS_META = {
  OVERDUE: { color: 'red', text: '已到保养期' },
  DUE_SOON: { color: 'orange', text: '临近保养' },
  OK: { color: 'green', text: '正常' },
  NO_RULE: { color: 'default', text: '未配置规则' }
}

/** 设备运维：运行台账 / 保养周期 / 故障看板 / 备件建议 */
export default function Maintenance() {
  return (
    <Card
      style={{ margin: 16 }}
      title={<Space><ThunderboltFilled style={{ color: '#fa8c16' }} />设备运维</Space>}
    >
      <Tabs
        defaultActiveKey="ledger"
        items={[
          { key: 'ledger', label: '运行台账', children: <LedgerTab /> },
          { key: 'rules', label: '保养周期', children: <RulesTab /> },
          { key: 'faults', label: '故障看板', children: <FaultsTab /> },
          { key: 'spares', label: '备件建议', children: <SparesTab /> }
        ]}
      />
    </Card>
  )
}

// ==================== 运行台账 ====================

function LedgerTab() {
  const [rows, setRows] = useState([])
  const [loading, setLoading] = useState(false)
  const [running, setRunning] = useState(false)
  const [modalSn, setModalSn] = useState(null)
  const [form] = Form.useForm()

  const load = async () => {
    setLoading(true)
    try { setRows(await api.maintenanceLedger()) } finally { setLoading(false) }
  }
  useEffect(() => { load() }, [])

  // 保养登记/日结完成的实时推送
  useEffect(() => {
    let closed = false
    let timer
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    const connect = () => {
      const ws = new WebSocket(`${proto}://${location.host}/ws/realtime`)
      ws.onmessage = (e) => {
        try {
          const msg = JSON.parse(e.data)
          if (msg.event === 'maintenance') {
            load()
            if (msg.data?.type === 'REBUILT') message.success('运维统计已更新')
          }
        } catch { /* ignore */ }
      }
      ws.onclose = () => { if (!closed) timer = setTimeout(connect, 3000) }
    }
    connect()
    return () => { closed = true; clearTimeout(timer) }
  }, [])

  const runNow = async () => {
    setRunning(true)
    try {
      const r = await api.runMaintenance()
      message.success(`统计完成：${r.devices} 台设备，${r.dailyRows} 行日结`)
      await load()
    } finally { setRunning(false) }
  }

  const submitRecord = async () => {
    const v = await form.validateFields()
    await api.addMaintenanceRecord({ ...v, deviceSn: modalSn })
    message.success('保养已登记，周期重新起算')
    setModalSn(null)
    form.resetFields()
    load()
  }

  const columns = [
    { title: '设备', render: (_, r) => (
        <Space direction="vertical" size={0}>
          <b>{r.deviceName}</b><span style={{ color: '#999', fontSize: 12 }}>{r.deviceSn}</span>
        </Space>) },
    { title: '大棚', dataIndex: 'greenhouseName', width: 130 },
    { title: '类型', dataIndex: 'deviceTypeName', width: 90 },
    {
      title: '累计运行', dataIndex: 'totalRunHours', width: 120, sorter: (a, b) => a.totalRunHours - b.totalRunHours,
      render: (v, r) => (
        <Space size={4}>
          <b>{v.toFixed(1)}</b><span style={{ color: '#999' }}>h</span>
          {r.running && <Tag color="processing" style={{ marginInlineStart: 4 }}>运行中</Tag>}
        </Space>)
    },
    { title: '启停次数', dataIndex: 'startCount', width: 90, sorter: (a, b) => a.startCount - b.startCount },
    {
      title: '保养进度', width: 200,
      render: (_, r) => r.progress == null ? '-' : (
        <Tooltip title={r.status === 'OVERDUE'
          ? `已超期 ${Math.abs(r.remainingHours).toFixed(1)}h（周期 ${r.dueAtHours}h）`
          : `剩余 ${r.remainingHours?.toFixed(1)}h / 周期 ${r.dueAtHours}h`}>
          <Progress percent={Math.min(100, Math.round(r.progress * 100))} status={
            r.status === 'OVERDUE' ? 'exception' : r.status === 'DUE_SOON' ? 'active' : 'normal'} size="small" />
        </Tooltip>)
    },
    {
      title: '状态', dataIndex: 'status', width: 100,
      render: v => <Tag color={STATUS_META[v]?.color}>{STATUS_META[v]?.text || v}</Tag>
    },
    { title: '近30天故障', dataIndex: 'recentFaultCount30d', width: 100,
      render: v => v > 0 ? <Tag color={v >= 3 ? 'red' : 'orange'}>{v} 次</Tag> : <span style={{ color: '#999' }}>0</span> },
    { title: '上次保养', dataIndex: 'lastDoneAt', width: 160, render: v => v || '未登记' },
    {
      title: '操作', width: 110,
      render: (_, r) => <Button type="link" onClick={() => setModalSn(r.deviceSn)}>登记保养</Button>
    }
  ]

  return (
    <>
      <Space style={{ marginBottom: 12 }}>
        <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
        <Popconfirm title="立即全量重算台账与日结？（幂等，可重复执行）" onConfirm={runNow}>
          <Button type="primary" loading={running}>立即统计</Button>
        </Popconfirm>
        <span style={{ color: '#999' }}>台账每日 00:07 自动统计，运行中时长实时累计</span>
      </Space>
      <Table rowKey="deviceSn" size="small" loading={loading} columns={columns} dataSource={rows}
             pagination={false}
             rowClassName={r => r.status === 'OVERDUE' ? 'maint-overdue' : ''} />
      {rows.filter(r => r.suggestion).length > 0 && (
        <div style={{ marginTop: 12 }}>
          {rows.filter(r => r.suggestion).map(r => (
            <Alert key={r.deviceSn} style={{ marginBottom: 8 }} showIcon
                   type={r.recentFaultCount30d >= 3 ? 'error' : 'warning'}
                   message={`${r.deviceName}（${r.deviceSn}）`} description={r.suggestion} />
          ))}
        </div>
      )}
      <Modal title={`登记保养 - ${modalSn || ''}`} open={!!modalSn}
             onOk={submitRecord} onCancel={() => { setModalSn(null); form.resetFields() }}
             okText="提交" cancelText="取消" destroyOnHidden>
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item name="operator" label="保养人" rules={[{ required: true, message: '请填写保养人' }]}>
            <Input placeholder="如：张工" />
          </Form.Item>
          <Form.Item name="note" label="保养内容">
            <Input.TextArea rows={3} placeholder="如：加注润滑脂、检查皮带、更换轴承…" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}

// ==================== 保养周期 ====================

const TYPE_LABEL = { FAN: '风机', WET_CURTAIN: '湿帘水泵', SHADE_NET: '遮阳网电机' }
const TYPE_ORDER = ['FAN', 'WET_CURTAIN', 'SHADE_NET']

function RulesTab() {
  const [rules, setRules] = useState([])
  const [loading, setLoading] = useState(false)

  const load = async () => {
    const list = await api.maintenanceRules()
    const map = Object.fromEntries(list.map(r => [r.deviceType, r]))
    setRules(TYPE_ORDER.map(t => map[t] || { deviceType: t, runIntervalHours: 500, warnAheadHours: 50, enabled: true }))
  }
  useEffect(() => { load() }, [])

  const set = (type, patch) =>
    setRules(rs => rs.map(r => r.deviceType === type ? { ...r, ...patch } : r))

  const save = async () => {
    setLoading(true)
    try {
      await api.saveMaintenanceRules(rules)
      message.success('保养周期已保存')
    } finally { setLoading(false) }
  }

  const columns = [
    { title: '设备类型', dataIndex: 'deviceType', width: 160, render: v => TYPE_LABEL[v] },
    { title: '保养间隔（运行小时）', width: 220,
      render: (_, r) => <InputNumber min={1} value={r.runIntervalHours}
                                     onChange={v => set(r.deviceType, { runIntervalHours: v })} /> },
    { title: '提前提醒（小时）', width: 200,
      render: (_, r) => <InputNumber min={1} max={r.runIntervalHours - 1} value={r.warnAheadHours}
                                     onChange={v => set(r.deviceType, { warnAheadHours: v })} /> },
    { title: '启用', render: (_, r) =>
        <Switch checked={r.enabled} onChange={v => set(r.deviceType, { enabled: v })} /> }
  ]

  return (
    <>
      <Space style={{ marginBottom: 12 }}>
        <Button type="primary" loading={loading} onClick={save}>保存配置</Button>
        <span style={{ color: '#999' }}>按设备类型配置，如风机每 500 小时润滑一次，提前 50 小时提醒</span>
      </Space>
      <Table rowKey="deviceType" size="small" columns={columns} dataSource={rules} pagination={false} />
    </>
  )
}

// ==================== 故障看板 ====================

function FaultsTab() {
  const [stats, setStats] = useState(null)
  const [loading, setLoading] = useState(true)

  const load = async () => {
    setLoading(true)
    try { setStats(await api.faultStats(30)) } finally { setLoading(false) }
  }
  useEffect(() => { load() }, [])

  if (loading || !stats) return <div style={{ padding: 40, textAlign: 'center', color: '#999' }}>加载中…</div>

  const typeData = stats.byType.map(r => ({ label: r.deviceTypeName, total: r.total, counts: r.byCategory }))
  const ghData = stats.byGreenhouse.map(r => ({ label: r.greenhouseName, total: r.total, counts: r.byCategory }))
  const trend = stats.trend.map(t => ({ date: t.date, value: t.total }))

  const detailCols = [
    { title: '维度', dataIndex: 'name' },
    { title: '电机过载', dataIndex: 'overload', render: v => v || 0 },
    { title: '通讯超时', dataIndex: 'timeout', render: v => v || 0 },
    { title: '其他', dataIndex: 'other', render: v => v || 0 },
    { title: '合计', dataIndex: 'total', render: v => <b>{v}</b> }
  ]
  const detailRows = stats.byType.map(r => ({
    key: 't' + r.deviceType, name: r.deviceTypeName,
    overload: r.byCategory.OVERLOAD, timeout: r.byCategory.TIMEOUT, other: r.byCategory.OTHER, total: r.total
  })).concat(stats.byGreenhouse.map(r => ({
    key: 'g' + r.greenhouseId, name: '【大棚】' + r.greenhouseName,
    overload: r.byCategory.OVERLOAD, timeout: r.byCategory.TIMEOUT, other: r.byCategory.OTHER, total: r.total
  })))

  return (
    <>
      <Row gutter={16} style={{ marginBottom: 8 }}>
        <Col span={8}><Card><Statistic title="近30天故障总数" value={stats.totalFaults} suffix="次"
                                       valueStyle={{ color: stats.totalFaults ? '#fa541c' : '#52c41a' }} /></Card></Col>
        <Col span={8}><Card><Statistic title="备件/检修建议" value={stats.suggestions.length} suffix="台"
                                       valueStyle={{ color: stats.suggestions.length ? '#cf1322' : '#52c41a' }} /></Card></Col>
        <Col span={8}><Card><Statistic title="网关离线事件（参考）" value={stats.offlineCount} suffix="次" /></Card></Col>
      </Row>
      <Card size="small" type="inner" title="按设备类型" style={{ marginTop: 12 }}>
        <FaultLegend />
        <BarChart data={typeData} />
      </Card>
      {ghData.length > 0 && (
        <Card size="small" type="inner" title="按大棚区域" style={{ marginTop: 12 }}>
          <BarChart data={ghData} />
        </Card>
      )}
      <Card size="small" type="inner" title="近30天故障趋势" style={{ marginTop: 12 }}>
        <TrendChart points={trend} />
      </Card>
      <Card size="small" type="inner" title="明细" style={{ marginTop: 12 }}>
        <Table rowKey="key" size="small" columns={detailCols} dataSource={detailRows} pagination={false} />
      </Card>
    </>
  )
}

// ==================== 备件建议 ====================

function SparesTab() {
  const [stats, setStats] = useState(null)
  const [loading, setLoading] = useState(true)

  const load = async () => {
    setLoading(true)
    try { setStats(await api.faultStats(30)) } finally { setLoading(false) }
  }
  useEffect(() => { load() }, [])

  if (loading || !stats) return <div style={{ padding: 40, textAlign: 'center', color: '#999' }}>加载中…</div>

  return (
    <>
      <Alert style={{ marginBottom: 12 }} showIcon type="info"
             message="近 30 天同一设备故障 ≥ 3 次时，自动建议检修或更换备件" />
      <List
        locale={{ emptyText: '近期暂无高频故障设备，备件状态良好' }}
        dataSource={stats.suggestions}
        renderItem={s => (
          <List.Item>
            <Alert style={{ width: '100%' }} showIcon type="error"
                   message={<Space><b>{s.deviceName}</b>
                     <Tag>{s.deviceSn}</Tag><span style={{ color: '#999' }}>{s.greenhouseName}</span></Space>}
                   description={
                     <Space direction="vertical" size={4}>
                       <span>{s.message}</span>
                       <span style={{ color: '#999', fontSize: 12 }}>
                         电机过载 {s.categories.OVERLOAD} 次 · 通讯超时 {s.categories.TIMEOUT} 次
                         · 其他 {s.categories.OTHER} 次 · 最近故障 {s.lastFaultAt}
                       </span>
                     </Space>} />
          </List.Item>
        )}
      />
    </>
  )
}

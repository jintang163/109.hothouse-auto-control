import React, { useEffect, useState } from 'react'
import { Card, Table, Tag, Button, Segmented, Space, Popconfirm, message, Badge } from 'antd'
import { api } from '../api'

const LEVEL_TAG = { INFO: 'blue', WARN: 'orange', CRITICAL: 'red' }
const TYPE_LABEL = {
  THRESHOLD: '阈值越限', DEVICE_OFFLINE: '设备离线',
  COMMAND_FAILED: '指令失败', INTERLOCK_BLOCKED: '互锁拦截'
}

/** 告警中心：实时新增 + 人工处理闭环 */
export default function Alarms() {
  const [filter, setFilter] = useState('OPEN')
  const [rows, setRows] = useState([])
  const [loading, setLoading] = useState(false)

  const load = async () => {
    setLoading(true)
    try {
      setRows(await api.alarms(filter === 'ALL' ? undefined : filter))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [filter])

  // 告警实时推送：OPEN 视图下即时插入
  useEffect(() => {
    let closed = false
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    let timer
    const connect = () => {
      const ws = new WebSocket(`${proto}://${location.host}/ws/realtime`)
      ws.onmessage = (e) => {
        const msg = JSON.parse(e.data)
        if (msg.event === 'alarm' && filter === 'OPEN') {
          setRows(prev => [msg.data, ...prev].slice(0, 200))
          message.warning(msg.data.message)
        }
        if (msg.event === 'alarm' && filter !== 'OPEN') load()
      }
      ws.onclose = () => { if (!closed) timer = setTimeout(connect, 3000) }
    }
    connect()
    return () => { closed = true; clearTimeout(timer) }
  }, [filter])

  const handle = async (id) => {
    await api.handleAlarm(id, 'admin')
    message.success('告警已处理')
    load()
  }

  const columns = [
    { title: '时间', dataIndex: 'createdAt', width: 170 },
    { title: '级别', dataIndex: 'level', width: 90, render: v => <Tag color={LEVEL_TAG[v]}>{v}</Tag> },
    { title: '类型', dataIndex: 'type', width: 100, render: v => TYPE_LABEL[v] || v },
    { title: '大棚', dataIndex: 'greenhouseId', width: 70 },
    { title: '设备', dataIndex: 'deviceSn', width: 130, render: v => v || '-' },
    { title: '告警内容', dataIndex: 'message' },
    {
      title: '状态', dataIndex: 'status', width: 110,
      render: (v, r) => v === 'OPEN'
        ? <Badge status="error" text="未处理" />
        : <Space direction="vertical" size={0}>
            <Badge status="success" text="已处理" />
            <small style={{ color: '#999' }}>{r.handledBy} · {r.handledAt?.substring(5, 16)}</small>
          </Space>
    },
    {
      title: '操作', width: 100,
      render: (_, r) => r.status === 'OPEN'
        ? <Popconfirm title="确认该告警已处置？" onConfirm={() => handle(r.id)}>
            <Button type="link">处理</Button>
          </Popconfirm>
        : null
    }
  ]

  return (
    <Card title="告警中心" style={{ margin: 16 }}
          extra={<Segmented options={['OPEN', 'HANDLED', 'ALL']} value={filter} onChange={setFilter} />}>
      <Table rowKey="id" columns={columns} dataSource={rows} loading={loading}
             size="small" pagination={{ pageSize: 15 }} />
    </Card>
  )
}

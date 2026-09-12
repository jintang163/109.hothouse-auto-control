import React, { useEffect, useState } from 'react'
import { Card, Tabs, Table, Tag } from 'antd'
import { api } from '../api'

const SRC_TAG = { AUTO_RULE: 'green', MANUAL: 'orange', SCHEDULE: 'blue', OFFLINE_RETRY: 'purple' }
const SRC_LABEL = { AUTO_RULE: '自动联动', MANUAL: '手动', SCHEDULE: '定时', OFFLINE_RETRY: '离线补发' }
const CMD_TAG = { PENDING: 'default', SENT: 'processing', ACKED: 'success', QUEUED_OFFLINE: 'warning', FAILED: 'error' }

/** 追溯：操作日志 / 控制指令全生命周期 / 巡检记录 */
export default function Trace({ greenhouseId }) {
  const [logs, setLogs] = useState([])
  const [commands, setCommands] = useState([])
  const [inspections, setInspections] = useState([])

  const load = async () => {
    const [l, c, i] = await Promise.all([
      api.logs(greenhouseId), api.commands(greenhouseId), api.inspections(greenhouseId)
    ])
    setLogs(l); setCommands(c); setInspections(i)
  }
  useEffect(() => { load() }, [greenhouseId])

  const logCols = [
    { title: '时间', dataIndex: 'createdAt', width: 170 },
    { title: '动作', dataIndex: 'action', width: 100 },
    { title: '设备', dataIndex: 'deviceSn', width: 120, render: v => v || '-' },
    { title: '来源', dataIndex: 'source', width: 100, render: v => <Tag color={SRC_TAG[v]}>{SRC_LABEL[v] || v}</Tag> },
    { title: '操作人', dataIndex: 'operatorName', width: 100 },
    { title: '详情', dataIndex: 'detail' }
  ]

  const cmdCols = [
    { title: '时间', dataIndex: 'createdAt', width: 170 },
    { title: '设备', dataIndex: 'deviceSn', width: 110 },
    { title: '动作', dataIndex: 'action', width: 80 },
    { title: '来源', dataIndex: 'source', width: 100, render: v => <Tag color={SRC_TAG[v]}>{SRC_LABEL[v] || v}</Tag> },
    {
      title: '状态', dataIndex: 'status', width: 110,
      render: v => <Tag color={CMD_TAG[v]}>{
        { PENDING: '待下发', SENT: '待回执', ACKED: '已回执', QUEUED_OFFLINE: '离线缓存', FAILED: '失败' }[v] || v}</Tag>
    },
    { title: '重试', width: 70, render: (_, r) => `${r.retryCount}/${r.maxRetry}` },
    { title: '下发', dataIndex: 'sentAt', width: 170, render: v => v || '-' },
    { title: '回执', dataIndex: 'ackedAt', width: 170, render: v => v || '-' },
    { title: '错误', dataIndex: 'errorMsg', render: v => v || '-' }
  ]

  const inspCols = [
    { title: '时间', dataIndex: 'createdAt', width: 170 },
    { title: '巡检人', dataIndex: 'inspector', width: 110 },
    { title: '结论', dataIndex: 'result', width: 90,
      render: v => <Tag color={v === '异常' ? 'red' : 'green'}>{v}</Tag> },
    { title: '内容', dataIndex: 'content' }
  ]

  return (
    <Card style={{ margin: 16 }}>
      <Tabs items={[
        { key: 'logs', label: `操作日志 (${logs.length})`, children:
          <Table rowKey="id" size="small" columns={logCols} dataSource={logs} pagination={{ pageSize: 15 }} /> },
        { key: 'cmds', label: `控制指令 (${commands.length})`, children:
          <Table rowKey="commandId" size="small" columns={cmdCols} dataSource={commands} pagination={{ pageSize: 15 }}
                 rowClassName={r => r.status === 'FAILED' ? 'ant-table-row' : ''} /> },
        { key: 'insp', label: `巡检记录 (${inspections.length})`, children:
          <Table rowKey="id" size="small" columns={inspCols} dataSource={inspections} pagination={{ pageSize: 15 }} /> }
      ]} />
    </Card>
  )
}

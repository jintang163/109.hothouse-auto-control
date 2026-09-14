import React, { useEffect, useRef, useState } from 'react'
import {
  Card, Table, Tag, Button, Space, Select, Modal, Form, Input, InputNumber, Drawer,
  Descriptions, Timeline, message, Badge, Statistic, Row, Col
} from 'antd'
import { ThunderboltOutlined, CheckOutlined, PlusOutlined } from '@ant-design/icons'
import { api, openRealtimeSocket } from '../api'
import {
  TASK_TYPES, TASK_STATUS, TRIGGER_TYPES, EXEC_MODES,
  typeLabel, statusMeta, triggerMeta, execMeta
} from '../farm-constants'

const { TextArea } = Input

/** 农事任务看板：偏差/周期/病虫害任务闭环；一键 Netty 联动或人工反馈 */
export default function FarmTasks({ greenhouseId }) {
  const [tasks, setTasks] = useState([])
  const [statusFilter, setStatusFilter] = useState('ALL')
  const [feedbackOf, setFeedbackOf] = useState(null)
  const [creating, setCreating] = useState(false)
  const [detail, setDetail] = useState(null)
  const closeWsRef = useRef(null)

  const load = async () => {
    const all = await api.farmTasks(greenhouseId)
    setTasks(all)
  }
  useEffect(() => {
    load()
    closeWsRef.current = openRealtimeSocket((event) => {
      if (event === 'farmtask') load()
    })
    return () => closeWsRef.current?.()
  }, [greenhouseId])

  const runDevices = async (t) => {
    const hide = message.loading('正在经 Netty 下发联动指令…', 0)
    try {
      await api.runDevices(t.id, 'admin')
      message.success('联动链已启动，等待设备回执（可实时看状态变化）')
      setTimeout(load, 1200)
    } catch (e) {
      message.error(e.message)
    } finally {
      hide()
    }
  }

  const cancel = async (t) => {
    await api.cancelFarmTask(t.id, 'admin')
    message.success('任务已取消')
    load()
  }

  const filtered = tasks.filter(t => statusFilter === 'ALL' || t.status === statusFilter)
  const counts = TASK_STATUS.reduce((m, s) => {
    m[s.value] = tasks.filter(t => t.status === s.value).length
    return m
  }, {})

  const columns = [
    { title: '#', dataIndex: 'id', width: 56 },
    {
      title: '类型', dataIndex: 'type', width: 120,
      render: v => <Tag>{typeLabel(v)}</Tag>
    },
    { title: '任务', dataIndex: 'title',
      render: (v, r) => <a onClick={() => setDetail(r)}>{v}</a> },
    {
      title: '触发', dataIndex: 'triggerType', width: 92,
      render: v => <Tag color={triggerMeta(v).color}>{triggerMeta(v).label}</Tag>
    },
    {
      title: '执行', dataIndex: 'execMode', width: 92,
      render: v => <Tag color={execMeta(v).color}>{execMeta(v).label}</Tag>
    },
    {
      title: '状态', dataIndex: 'status', width: 92,
      render: v => <Badge status={badgeStatus(v)} text={statusMeta(v).label} />
    },
    { title: '生成时间', dataIndex: 'generatedAt', width: 170 },
    { title: '执行人', dataIndex: 'operator', width: 90, render: v => v || '-' },
    {
      title: '操作', width: 230, fixed: 'right',
      render: (_, r) => (
        <Space size={4}>
          <Button size="small" type="primary" ghost icon={<ThunderboltOutlined />}
                  disabled={!r.deviceActionsJson || !(r.status === 'PENDING' || r.status === 'FAILED')}
                  onClick={() => runDevices(r)}>设备联动</Button>
          <Button size="small" type="primary" icon={<CheckOutlined />}
                  disabled={r.status === 'DONE' || r.status === 'CANCELLED'}
                  onClick={() => setFeedbackOf(r)}>人工反馈</Button>
          <Button size="small" danger disabled={r.status === 'DONE' || r.status === 'CANCELLED'}
                  onClick={() => cancel(r)}>取消</Button>
        </Space>
      )
    }
  ]

  return (
    <div style={{ padding: 16 }}>
      <Row gutter={12} style={{ marginBottom: 12 }}>
        <Col span={4}><Card size="small"><Statistic title="待执行" value={(counts.PENDING || 0) + (counts.EXECUTING || 0)} valueStyle={{ color: '#d48806' }} /></Card></Col>
        <Col span={4}><Card size="small"><Statistic title="已完成" value={counts.DONE || 0} valueStyle={{ color: '#3f8600' }} /></Card></Col>
        <Col span={4}><Card size="small"><Statistic title="联动失败" value={counts.FAILED || 0} valueStyle={{ color: '#cf1322' }} /></Card></Col>
        <Col span={12} style={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 8 }}>
          <Select value={statusFilter} style={{ width: 130 }} onChange={setStatusFilter}
                  options={[{ value: 'ALL', label: '全部状态' }, ...TASK_STATUS.map(s => ({ value: s.value, label: s.label }))]} />
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>新建任务</Button>
        </Col>
      </Row>

      <Card title="农事任务（自动生成 / 病虫害推送 / 人工创建）" size="small">
        <Table rowKey="id" size="small" columns={columns} dataSource={filtered}
               scroll={{ x: 1100 }} pagination={{ pageSize: 12 }} />
      </Card>

      <FeedbackModal task={feedbackOf} onClose={() => setFeedbackOf(null)} onDone={() => { setFeedbackOf(null); load() }} />
      <CreateTaskModal open={creating} greenhouseId={greenhouseId}
                      onClose={() => setCreating(false)} onDone={() => { setCreating(false); load() }} />
      <TaskDrawer task={detail} onClose={() => setDetail(null)} />
    </div>
  )
}

function badgeStatus(s) {
  return { PENDING: 'warning', EXECUTING: 'processing', DONE: 'success', FAILED: 'error', CANCELLED: 'default' }[s]
}

// ---------------- 人工执行反馈 ----------------

function FeedbackModal({ task, onClose, onDone }) {
  const [form] = Form.useForm()
  useEffect(() => {
    form.resetFields()
    if (task) {
      form.setFieldsValue({ operator: '现场人员', materialUsed: null, materialUnit: '', durationMinutes: null, feedback: '' })
    }
  }, [task])
  if (!task) return null
  const submit = async () => {
    const v = await form.validateFields()
    await api.completeManual(task.id, v)
    message.success('已回填反馈，任务闭环')
    onDone()
  }
  return (
    <Modal title={`人工执行反馈：#${task.id} ${task.title}`} open={!!task} onCancel={onClose}
           onOk={submit} okText="确认完成">
      <p style={{ color: '#888' }}>{task.instruction}</p>
      <Form form={form} layout="vertical">
        <Form.Item name="operator" label="执行人" rules={[{ required: true }]}>
          <Input />
        </Form.Item>
        <Space>
          <Form.Item name="materialUsed" label="实际用量">
            <InputNumber min={0} step={0.1} style={{ width: 150 }} />
          </Form.Item>
          <Form.Item name="materialUnit" label="单位">
            <Input placeholder="L / kg / 袋" style={{ width: 120 }} />
          </Form.Item>
          <Form.Item name="durationMinutes" label="耗时（分钟）">
            <InputNumber min={0} style={{ width: 140 }} />
          </Form.Item>
        </Space>
        <Form.Item name="feedback" label="执行情况 / 备注">
          <TextArea rows={3} placeholder="如：滴灌 200L 均匀，土壤湿度恢复至 72%" />
        </Form.Item>
      </Form>
    </Modal>
  )
}

// ---------------- 人工创建任务 ----------------

function CreateTaskModal({ open, greenhouseId, onClose, onDone }) {
  const [form] = Form.useForm()
  const submit = async () => {
    const v = await form.validateFields()
    await api.createFarmTask({
      greenhouseId,
      type: v.type,
      title: v.title,
      instruction: v.instruction,
      execMode: v.deviceActionsJson ? 'DEVICE' : 'MANUAL',
      deviceActionsJson: v.deviceActionsJson || null,
      assignee: v.assignee
    }, 'admin')
    message.success('任务已创建')
    form.resetFields()
    onDone()
  }
  return (
    <Modal title="新建农事任务" open={open} onCancel={onClose} onOk={submit} okText="创建">
      <Form form={form} layout="vertical" initialValues={{ type: 'OTHER', assignee: '现场人员' }}>
        <Space>
          <Form.Item name="type" label="类型" rules={[{ required: true }]}>
            <Select style={{ width: 160 }} options={TASK_TYPES.map(t => ({ value: t.value, label: t.label }))} />
          </Form.Item>
          <Form.Item name="assignee" label="负责人">
            <Input style={{ width: 180 }} />
          </Form.Item>
        </Space>
        <Form.Item name="title" label="任务标题" rules={[{ required: true }]}>
          <Input placeholder="如：补施高钾肥 5kg" />
        </Form.Item>
        <Form.Item name="instruction" label="执行说明">
          <TextArea rows={2} />
        </Form.Item>
        <Form.Item name="deviceActionsJson" label="设备联动动作 JSON（留空则为纯人工任务）">
          <TextArea rows={2} placeholder='[{"deviceSn":"WC-001","action":"OPEN","holdMinutes":10,"thenAction":"CLOSE"}]' />
        </Form.Item>
      </Form>
    </Modal>
  )
}

// ---------------- 任务详情 / 闭环时间线 ----------------

function TaskDrawer({ task, onClose }) {
  if (!task) return null
  const snapshot = safeParse(task.triggerSnapshot, null)
  return (
    <Drawer title={`农事任务 #${task.id}`} width={520} open={!!task} onClose={onClose}>
      <Descriptions column={1} bordered size="small">
        <Descriptions.Item label="标题">{task.title}</Descriptions.Item>
        <Descriptions.Item label="类型">{typeLabel(task.type)}</Descriptions.Item>
        <Descriptions.Item label="状态"><Tag color={statusMeta(task.status).color}>{statusMeta(task.status).label}</Tag></Descriptions.Item>
        <Descriptions.Item label="触发方式">{triggerMeta(task.triggerType).label}</Descriptions.Item>
        <Descriptions.Item label="执行方式">{execMeta(task.execMode).label}</Descriptions.Item>
        <Descriptions.Item label="来源处方">{task.prescriptionId ? `#${task.prescriptionId}（v${task.prescriptionVersion}）` : '-'}</Descriptions.Item>
        <Descriptions.Item label="茬次">{task.batchNo || '-'}</Descriptions.Item>
        <Descriptions.Item label="执行说明">{task.instruction || '-'}</Descriptions.Item>
        <Descriptions.Item label="联动链">{task.chainId || '-'}</Descriptions.Item>
        <Descriptions.Item label="实际用量">{task.materialUsed != null ? `${task.materialUsed} ${task.materialUnit || ''}` : '-'}</Descriptions.Item>
        <Descriptions.Item label="实际耗时">{task.durationMinutes != null ? `${task.durationMinutes} 分钟` : '-'}</Descriptions.Item>
        <Descriptions.Item label="执行人">{task.operator || '-'}</Descriptions.Item>
        <Descriptions.Item label="反馈">{task.feedback || '-'}</Descriptions.Item>
      </Descriptions>

      {snapshot && <Card size="small" style={{ marginTop: 12 }} title="触发快照">
        <pre style={{ margin: 0, fontSize: 12 }}>{JSON.stringify(snapshot, null, 2)}</pre>
      </Card>}

      <Card size="small" style={{ marginTop: 12 }} title="闭环过程">
        <Timeline items={[
          { color: 'blue', children: `生成（${task.generatedAt}）` },
          task.startedAt ? { color: 'blue', children: `开始执行（${task.startedAt}）` } : null,
          task.finishedAt ? {
            color: task.status === 'DONE' ? 'green' : 'red',
            children: `${task.status === 'DONE' ? '完成' : task.status === 'CANCELLED' ? '取消' : '失败'}（${task.finishedAt}）`
          } : null
        ].filter(Boolean)} />
      </Card>
    </Drawer>
  )
}

function safeParse(s, d) {
  try { return JSON.parse(s) } catch { return d }
}

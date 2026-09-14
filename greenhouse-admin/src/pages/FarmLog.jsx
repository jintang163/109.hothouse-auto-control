import React, { useEffect, useState } from 'react'
import {
  Card, Table, Button, Tag, Space, Modal, Form, Input, InputNumber, DatePicker,
  Statistic, Row, Col, Collapse, List, Typography, message, Descriptions
} from 'antd'
import { PlusOutlined, BulbOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { api } from '../api'
import { TASK_TYPES, TASK_STATUS, typeLabel, statusMeta } from '../farm-constants'

const { Text } = Typography

/** 农事日志 + 产量/品质：按茬次关联任务闭环数据，给出处方优化线索 */
export default function FarmLog({ greenhouseId }) {
  const [yields, setYields] = useState([])
  const [analysis, setAnalysis] = useState([])
  const [tasks, setTasks] = useState([])
  const [adding, setAdding] = useState(false)
  const [batchTasks, setBatchTasks] = useState(null)

  const load = async () => {
    const [y, a, t] = await Promise.all([
      api.yields(greenhouseId),
      api.yieldAnalysis(greenhouseId),
      api.farmTasks(greenhouseId)
    ])
    setYields(y); setAnalysis(a); setTasks(t)
  }
  useEffect(() => { load() }, [greenhouseId])

  const yieldCols = [
    { title: '批次', dataIndex: 'batchNo', width: 150 },
    { title: '采收日期', dataIndex: 'harvestDate', width: 120 },
    { title: '产量(kg)', dataIndex: 'weightKg', width: 100, sorter: (a, b) => a.weightKg - b.weightKg },
    { title: '优质果率(%)', dataIndex: 'premiumRate', width: 110 },
    { title: '糖度(°Brix)', dataIndex: 'brix', width: 110 },
    { title: '记录人', dataIndex: 'recordedBy', width: 90 },
    { title: '备注', dataIndex: 'remark', render: v => v || '-' }
  ]

  return (
    <div style={{ padding: 16 }}>
      <Row gutter={12} style={{ marginBottom: 12 }}>
        <Col span={6}><Card size="small"><Statistic title="采收记录" value={yields.length} suffix="次" /></Card></Col>
        <Col span={6}><Card size="small"><Statistic title="累计产量"
          value={Math.round(yields.reduce((s, y) => s + y.weightKg, 0) * 10) / 10} suffix="kg" /></Card></Col>
        <Col span={6}><Card size="small"><Statistic title="农事任务总数" value={tasks.length} suffix="个" /></Card></Col>
        <Col span={6} style={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end' }}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setAdding(true)}>录入产量/品质</Button>
        </Col>
      </Row>

      <Card title="按茬次分析：产量品质 × 农事闭环（处方优化依据）" size="small" style={{ marginBottom: 12 }}>
        <Collapse items={analysis.map(b => ({
          key: b.batchNo,
          label: <Space>
            <b>{b.batchNo}</b>
            <Tag color="green">总产 {b.totalWeightKg}kg</Tag>
            <Tag>优质果率 {b.avgPremiumRate ?? '-'}%</Tag>
            <Tag>糖度 {b.avgBrix ?? '-'}°Brix</Tag>
            <Tag color={b.completionRate === 100 ? 'green' : 'orange'}>任务完成率 {b.completionRate ?? '-'}%</Tag>
          </Space>,
          children: <BatchDetail b={b} onShowTasks={() => setBatchTasks(b)} />
        }))} />
      </Card>

      <Card title="产量 / 品质记录" size="small">
        <Table rowKey="id" size="small" columns={yieldCols} dataSource={yields}
               pagination={{ pageSize: 8 }} />
      </Card>

      <YieldModal open={adding} greenhouseId={greenhouseId}
                  onClose={() => setAdding(false)} onDone={() => { setAdding(false); load() }} />
      <BatchTasksDrawer data={batchTasks} tasks={tasks} onClose={() => setBatchTasks(null)} />
    </div>
  )
}

function BatchDetail({ b, onShowTasks }) {
  return (
    <div>
      <Descriptions size="small" column={3} bordered>
        <Descriptions.Item label="采收次数">{b.harvestCount}</Descriptions.Item>
        <Descriptions.Item label="单次均产">{b.avgWeightKg}kg</Descriptions.Item>
        <Descriptions.Item label="采收区间">{b.firstHarvest} ~ {b.lastHarvest}</Descriptions.Item>
        <Descriptions.Item label="任务总数">{b.taskTotal}</Descriptions.Item>
        <Descriptions.Item label="已完成 / 失败 / 待办">
          <Tag color="green">{b.taskDone}</Tag> / <Tag color="red">{b.taskFailed}</Tag> / <Tag color="gold">{b.taskPending}</Tag>
        </Descriptions.Item>
        <Descriptions.Item label="设备联动任务 / 人工总耗时">
          {b.taskDeviceCount} 个 / {b.manualTotalMinutes} 分钟
        </Descriptions.Item>
      </Descriptions>
      <div style={{ marginTop: 8 }}>
        {Object.entries(b.tasksByType || {}).map(([k, v]) =>
          <Tag key={k} style={{ marginBottom: 4 }}>{typeLabel(k)} × {v}</Tag>)}
      </div>
      <Card size="small" type="inner" style={{ marginTop: 8 }}
            title={<span><BulbOutlined style={{ color: '#faad14' }} /> 处方优化线索</span>}>
        <List size="small" dataSource={b.insights} renderItem={t => <List.Item>{t}</List.Item>} />
      </Card>
      <Button size="small" style={{ marginTop: 8 }} onClick={onShowTasks}>查看本茬农事闭环日志（{b.taskTotal}）</Button>
    </div>
  )
}

// ---------------- 产量录入 ----------------

function YieldModal({ open, greenhouseId, onClose, onDone }) {
  const [form] = Form.useForm()
  useEffect(() => {
    if (open) {
      form.setFieldsValue({ harvestDate: dayjs(), batchNo: '2026秋茬-1号棚' })
    }
  }, [open])
  const submit = async () => {
    const v = await form.validateFields()
    await api.addYield({
      greenhouseId,
      batchNo: v.batchNo,
      harvestDate: v.harvestDate.format('YYYY-MM-DD'),
      weightKg: v.weightKg,
      premiumRate: v.premiumRate,
      brix: v.brix,
      remark: v.remark
    })
    message.success('产量/品质已录入')
    form.resetFields()
    onDone()
  }
  return (
    <Modal title="录入产量 / 品质" open={open} onCancel={onClose} onOk={submit} okText="保存">
      <Form form={form} layout="vertical">
        <Space>
          <Form.Item name="batchNo" label="茬次/批次" rules={[{ required: true }]}>
            <Input style={{ width: 200 }} />
          </Form.Item>
          <Form.Item name="harvestDate" label="采收日期" rules={[{ required: true }]}>
            <DatePicker style={{ width: 160 }} />
          </Form.Item>
        </Space>
        <Space>
          <Form.Item name="weightKg" label="产量(kg)" rules={[{ required: true }]}>
            <InputNumber min={0} step={0.1} style={{ width: 150 }} />
          </Form.Item>
          <Form.Item name="premiumRate" label="优质果率(%)">
            <InputNumber min={0} max={100} step={0.1} style={{ width: 150 }} />
          </Form.Item>
          <Form.Item name="brix" label="糖度(°Brix)">
            <InputNumber min={0} max={20} step={0.1} style={{ width: 150 }} />
          </Form.Item>
        </Space>
        <Form.Item name="remark" label="备注">
          <Input.TextArea rows={2} />
        </Form.Item>
      </Form>
    </Modal>
  )
}

// ---------------- 批次闭环日志 ----------------

function BatchTasksDrawer({ data, tasks, onClose }) {
  if (!data) return null
  const rows = tasks.filter(t => t.batchNo === data.batchNo)
  return (
    <Modal title={`农事闭环日志：${data.batchNo}`} open={!!data} onCancel={onClose} footer={null} width={900}>
      <Table rowKey="id" size="small" pagination={{ pageSize: 10 }} dataSource={rows}
        columns={[
          { title: '#', dataIndex: 'id', width: 50 },
          { title: '类型', dataIndex: 'type', width: 110, render: v => <Tag>{typeLabel(v)}</Tag> },
          { title: '任务', dataIndex: 'title' },
          { title: '状态', dataIndex: 'status', width: 90,
            render: v => <Tag color={statusMeta(v).color}>{statusMeta(v).label}</Tag> },
          { title: '用量', width: 100,
            render: (_, r) => r.materialUsed != null ? `${r.materialUsed}${r.materialUnit || ''}` : '-' },
          { title: '耗时(分)', dataIndex: 'durationMinutes', width: 90, render: v => v ?? '-' },
          { title: '执行人', dataIndex: 'operator', width: 90, render: v => v || '-' },
          { title: '完成时间', dataIndex: 'finishedAt', width: 170, render: v => v || '-' }
        ]} />
    </Modal>
  )
}

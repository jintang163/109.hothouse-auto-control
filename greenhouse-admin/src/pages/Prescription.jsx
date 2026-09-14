import React, { useEffect, useMemo, useState } from 'react'
import {
  Card, Table, Button, Tag, Space, Drawer, Input, InputNumber, Select, Popconfirm,
  message, Typography, Row, Col, Divider, Descriptions, Modal
} from 'antd'
import { PlusOutlined, CopyOutlined, CloudUploadOutlined } from '@ant-design/icons'
import { api } from '../api'
import {
  METRICS, TASK_TYPES, DEVICE_TYPES, RX_STATUS, metricLabel
} from '../farm-constants'

const { TextArea } = Input
const { Title, Text } = Typography

const blankEditor = () => ({
  id: null,
  variety: '',
  growthStage: '',
  name: '',
  remark: '',
  targets: [{ metric: 'temperature', low: 22, high: 28, tolerance: 1, unit: '℃' }],
  operations: []
})

/** 农事处方库：品种+生育期配置目标温湿度/水肥/农事操作，复制与版本控制 */
export default function Prescription({ greenhouseId }) {
  const [list, setList] = useState([])
  const [greenhouses, setGreenhouses] = useState([])
  const [editing, setEditing] = useState(null)
  const [versionOf, setVersionOf] = useState(null) // {variety, growthStage}
  const [profileGh, setProfileGh] = useState(null)
  const [saving, setSaving] = useState(false)

  const load = async () => {
    const [all, ghs] = await Promise.all([api.prescriptions(), api.listGreenhouses()])
    setList(all)
    setGreenhouses(ghs)
  }
  useEffect(() => { load() }, [])

  const gh = useMemo(
    () => greenhouses.find(g => g.id === greenhouseId) || null,
    [greenhouses, greenhouseId]
  )

  // ---------------- 编辑器 ----------------

  const openNew = () => {
    const e = blankEditor()
    if (gh) {
      e.variety = gh.variety || ''
      e.growthStage = gh.growthStage || ''
      e.name = `${gh.crop || ''}${gh.growthStage || ''}处方`
    }
    setEditing(e)
  }

  const openEdit = (rx) => {
    if (rx.status !== 'DRAFT') {
      message.warning('已发布/归档处方只读，请「复制为新版本」')
      return
    }
    setEditing({
      id: rx.id,
      variety: rx.variety,
      growthStage: rx.growthStage,
      name: rx.name,
      remark: rx.remark || '',
      targets: safeParse(rx.envTargetsJson, []),
      operations: safeParse(rx.operationsJson, [])
    })
  }

  const save = async () => {
    const e = editing
    if (!e.variety.trim() || !e.growthStage.trim() || !e.name.trim()) {
      message.error('请填写品种、生育期、处方名称'); return
    }
    for (const t of e.targets) {
      if (t.low != null && t.high != null && Number(t.low) > Number(t.high)) {
        message.error(`${metricLabel(t.metric)} 下限不能高于上限`); return
      }
    }
    const payload = {
      variety: e.variety.trim(),
      growthStage: e.growthStage.trim(),
      name: e.name.trim(),
      remark: e.remark,
      envTargetsJson: JSON.stringify(e.targets),
      operationsJson: JSON.stringify(e.operations)
    }
    setSaving(true)
    try {
      if (e.id) {
        await api.updatePrescription(e.id, payload)
        message.success('草稿已更新')
      } else {
        await api.createPrescription(payload, 'admin')
        message.success('已创建草稿，版本号自动分配')
      }
      setEditing(null)
      load()
    } finally {
      setSaving(false)
    }
  }

  const copy = async (rx) => {
    await api.copyPrescription(rx.id, 'admin')
    message.success('已复制为新草稿（版本号递增，默认停用）')
    load()
  }
  const publish = async (rx) => {
    await api.publishPrescription(rx.id)
    message.success('已发布，同品种+生育期旧版本自动归档')
    load()
  }
  const archive = async (rx) => {
    await api.archivePrescription(rx.id)
    message.success('已归档')
    load()
  }
  const openVersions = (rx) => setVersionOf({ variety: rx.variety, growthStage: rx.growthStage })

  const columns = [
    { title: '品种', dataIndex: 'variety', width: 160 },
    { title: '生育期', dataIndex: 'growthStage', width: 100 },
    { title: '处方名称', dataIndex: 'name' },
    {
      title: '版本', dataIndex: 'version', width: 80,
      render: (v, r) => <Button type="link" size="small" style={{ padding: 0 }}
        onClick={() => openVersions(r)}>v{v}</Button>
    },
    {
      title: '状态', dataIndex: 'status', width: 90,
      render: s => <Tag color={RX_STATUS[s]?.color}>{RX_STATUS[s]?.label || s}</Tag>
    },
    { title: '更新时间', dataIndex: 'createdAt', width: 170 },
    {
      title: '操作', width: 290, fixed: 'right',
      render: (_, r) => (
        <Space size={4}>
          <Button size="small" disabled={r.status !== 'DRAFT'} onClick={() => openEdit(r)}>编辑</Button>
          <Button size="small" icon={<CopyOutlined />} onClick={() => copy(r)}>复制新版</Button>
          {r.status === 'DRAFT'
            ? <Button size="small" type="primary" ghost icon={<CloudUploadOutlined />} onClick={() => publish(r)}>发布</Button>
            : <Button size="small" disabled={r.status === 'ARCHIVED'} onClick={() => archive(r)}>归档</Button>}
        </Space>
      )
    }
  ]

  return (
    <div style={{ padding: 16 }}>
      <Card size="small" style={{ marginBottom: 12 }}
            title={<span>🌱 当前大棚种植档案（处方按「品种 + 生育期」匹配）</span>}
            extra={<Button size="small" onClick={() => setProfileGh(gh)}>编辑档案</Button>}>
        {gh ? (
          <Descriptions size="small" column={4}>
            <Descriptions.Item label="大棚">{gh.name}</Descriptions.Item>
            <Descriptions.Item label="品种">{gh.variety || <Text type="warning">未设置</Text>}</Descriptions.Item>
            <Descriptions.Item label="生育期">{gh.growthStage || <Text type="warning">未设置</Text>}</Descriptions.Item>
            <Descriptions.Item label="当前茬次">{gh.currentBatchNo || '-'}</Descriptions.Item>
          </Descriptions>
        ) : <Text type="secondary">加载中…</Text>}
        <div style={{ color: '#999', fontSize: 12, marginTop: 4 }}>
          任务生成器每 60 秒扫描：取本棚品种+生育期对应的「已发布」处方，对比传感器最新值生成偏差任务，并按操作周期生成水肥/农事任务。
        </div>
      </Card>

      <Card title="农事处方库" extra={
        <Space>
          <Button icon={<PlusOutlined />} type="primary" onClick={openNew}>新建处方</Button>
        </Space>
      }>
        <Table rowKey="id" size="small" columns={columns}
               dataSource={list} scroll={{ x: 1000 }} pagination={{ pageSize: 10 }} />
      </Card>

      <EditorDrawer
        editing={editing}
        saving={saving}
        onClose={() => setEditing(null)}
        onChange={setEditing}
        onSave={save}
      />

      <VersionDrawer versionOf={versionOf} onClose={() => setVersionOf(null)} onEdit={rx => { setVersionOf(null); openEdit(rx) }} />

      <ProfileModal gh={profileGh} onClose={() => setProfileGh(null)} onSaved={() => { setProfileGh(null); load() }} />
    </div>
  )
}

function safeParse(s, d) {
  try { return JSON.parse(s) } catch { return d }
}

// ---------------- 处方编辑抽屉（环境目标 + 农事操作结构化编辑） ----------------

function EditorDrawer({ editing, saving, onClose, onChange, onSave }) {
  const set = (patch) => onChange({ ...editing, ...patch })
  const updateRow = (key, i, patch) => {
    const arr = editing[key].map((x, j) => j === i ? { ...x, ...patch } : x)
    set({ [key]: arr })
  }

  return (
    <Drawer title={editing?.id ? `编辑处方草稿 #${editing.id}` : '新建处方（草稿）'}
            width={960} open={!!editing} onClose={onClose} destroyOnClose
            footer={<Space><Button onClick={onClose}>取消</Button>
              <Button type="primary" loading={saving} onClick={onSave}>保存草稿</Button></Space>}>
      {editing && <>
        <Row gutter={12}>
          <Col span={6}>
            <label>作物品种 *</label>
            <Input value={editing.variety} placeholder="如 番茄-佳粉18号"
                   onChange={e => set({ variety: e.target.value })} />
          </Col>
          <Col span={5}>
            <label>生育期 *</label>
            <Input value={editing.growthStage} placeholder="苗期/开花期/结果期"
                   onChange={e => set({ growthStage: e.target.value })} />
          </Col>
          <Col span={8}>
            <label>处方名称 *</label>
            <Input value={editing.name} onChange={e => set({ name: e.target.value })} />
          </Col>
          <Col span={5}>
            <label>备注</label>
            <Input value={editing.remark} onChange={e => set({ remark: e.target.value })} />
          </Col>
        </Row>

        <Divider orientation="left">环境目标区间（传感器越界自动生成调控任务）</Divider>
        <Space direction="vertical" style={{ width: '100%' }}>
          {editing.targets.map((t, i) => (
            <Space key={i} wrap>
              <Select style={{ width: 110 }} value={t.metric}
                      options={METRICS.map(m => ({ value: m.value, label: m.label }))}
                      onChange={v => updateRow('targets', i, { metric: v, unit: METRICS.find(m => m.value === v).unit })} />
              <InputNumber addonBefore="下限" style={{ width: 140 }} value={t.low}
                           onChange={v => updateRow('targets', i, { low: v })} />
              <InputNumber addonBefore="上限" style={{ width: 140 }} value={t.high}
                           onChange={v => updateRow('targets', i, { high: v })} />
              <InputNumber addonBefore="死区" style={{ width: 130 }} value={t.tolerance ?? 0}
                           onChange={v => updateRow('targets', i, { tolerance: v })} />
              <Input style={{ width: 80 }} value={t.unit}
                     onChange={e => updateRow('targets', i, { unit: e.target.value })} />
              <Button danger size="small"
                      onClick={() => set({ targets: editing.targets.filter((_, j) => j !== i) })}>删除</Button>
            </Space>
          ))}
          <Button type="dashed" icon={<PlusOutlined />} onClick={() =>
            set({ targets: [...editing.targets, { metric: 'humidity', low: 60, high: 80, tolerance: 5, unit: '%' }] })}>
            添加环境目标
          </Button>
        </Space>

        <Divider orientation="left">水肥 / 农事操作（周期触发；可配设备联动）</Divider>
        <Space direction="vertical" style={{ width: '100%' }} size={8}>
          {editing.operations.map((op, i) => (
            <Card key={i} size="small" type="inner"
                  title={<Space>
                    <Select size="small" style={{ width: 130 }} value={op.type}
                            options={TASK_TYPES.filter(t => !t.value.startsWith('ENV')).map(t => ({ value: t.value, label: t.label }))}
                            onChange={v => updateRow('operations', i, { type: v })} />
                    <Input size="small" style={{ width: 180 }} placeholder="操作名称，如 膜下滴灌"
                           value={op.name} onChange={e => updateRow('operations', i, { name: e.target.value })} />
                  </Space>}
                  extra={<Button danger size="small"
                    onClick={() => set({ operations: editing.operations.filter((_, j) => j !== i) })}>删除</Button>}>
              <Space wrap>
                <span>周期(天) <InputNumber size="small" min={0} style={{ width: 80 }} value={op.intervalDays}
                  onChange={v => updateRow('operations', i, { intervalDays: v })} /></span>
                <span>时刻 <Input size="small" style={{ width: 90 }} placeholder="08:00" value={op.time}
                  onChange={e => updateRow('operations', i, { time: e.target.value })} /></span>
                <span>执行方式
                  <Select size="small" style={{ width: 110, marginLeft: 6 }} value={op.execMode || 'MANUAL'}
                    options={[{ value: 'MANUAL', label: '人工执行' }, { value: 'DEVICE', label: '设备联动' }]}
                    onChange={v => updateRow('operations', i, { execMode: v })} />
                </span>
                {op.execMode === 'DEVICE' && <>
                  <span>设备
                    <Select size="small" style={{ width: 140, marginLeft: 6 }} value={op.deviceType}
                      options={DEVICE_TYPES.map(d => ({ value: d.value, label: d.label }))}
                      onChange={v => updateRow('operations', i, {
                        deviceType: v,
                        action: DEVICE_TYPES.find(d => d.value === v).actions[0]
                      })} />
                  </span>
                  <span>动作
                    <Select size="small" style={{ width: 100, marginLeft: 6 }} value={op.action}
                      options={(DEVICE_TYPES.find(d => d.value === op.deviceType)?.actions || ['OPEN', 'CLOSE'])
                        .map(a => ({ value: a, label: a }))}
                      onChange={v => updateRow('operations', i, { action: v })} />
                  </span>
                  <span>保持(分) <InputNumber size="small" min={0} style={{ width: 90, marginLeft: 6 }}
                    value={op.holdMinutes}
                    onChange={v => updateRow('operations', i, { holdMinutes: v })} /></span>
                </>}
                <span>用量 <InputNumber size="small" style={{ width: 90 }} value={op.dose}
                  onChange={v => updateRow('operations', i, { dose: v })} /></span>
                <span>单位 <Input size="small" style={{ width: 70 }} value={op.doseUnit}
                  onChange={e => updateRow('operations', i, { doseUnit: e.target.value })} /></span>
                <span>预估耗时(分) <InputNumber size="small" style={{ width: 100 }} value={op.estimatedMinutes}
                  onChange={v => updateRow('operations', i, { estimatedMinutes: v })} /></span>
              </Space>
              <TextArea size="small" rows={2} style={{ marginTop: 8 }} placeholder="执行说明 / 农技要点"
                        value={op.instruction}
                        onChange={e => updateRow('operations', i, { instruction: e.target.value })} />
            </Card>
          ))}
          <Button type="dashed" icon={<PlusOutlined />} onClick={() =>
            set({ operations: [...editing.operations, {
              key: 'op' + (editing.operations.length + 1), type: 'IRRIGATION', name: '',
              execMode: 'MANUAL', intervalDays: 2, time: '08:00', instruction: ''
            }] })}>
            添加农事操作
          </Button>
        </Space>
      </>}
    </Drawer>
  )
}

// ---------------- 版本历史抽屉 ----------------

function VersionDrawer({ versionOf, onClose, onEdit }) {
  const [rows, setRows] = useState([])
  useEffect(() => {
    if (!versionOf) return
    api.prescriptionVersions(versionOf.variety, versionOf.growthStage).then(setRows)
  }, [versionOf])
  return (
    <Drawer title={versionOf ? `版本历史：${versionOf.variety} · ${versionOf.growthStage}` : ''}
            width={640} open={!!versionOf} onClose={onClose}>
      {rows.map(r => (
        <Card key={r.id} size="small" style={{ marginBottom: 8 }}
              title={<Space>v{r.version}
                <Tag color={RX_STATUS[r.status]?.color}>{RX_STATUS[r.status]?.label}</Tag>
                {r.enabled && <Tag color="blue">生效中</Tag>}
              </Space>}
              extra={<Button size="small" onClick={() => onEdit(r)} disabled={r.status !== 'DRAFT'}>查看/编辑</Button>}>
          <Descriptions size="small" column={1}>
            <Descriptions.Item label="名称">{r.name}</Descriptions.Item>
            <Descriptions.Item label="创建人/时间">{r.createdBy} · {r.createdAt}</Descriptions.Item>
            <Descriptions.Item label="发布时间">{r.publishedAt || '-'}</Descriptions.Item>
            <Descriptions.Item label="备注">{r.remark || '-'}</Descriptions.Item>
            <Descriptions.Item label="来源">{r.copiedFromId ? `复制自 #${r.copiedFromId}` : '原始版本'}</Descriptions.Item>
          </Descriptions>
        </Card>
      ))}
    </Drawer>
  )
}

// ---------------- 种植档案编辑 ----------------

function ProfileModal({ gh, onClose, onSaved }) {
  const [form, setForm] = useState({ variety: '', growthStage: '', currentBatchNo: '' })
  useEffect(() => {
    if (gh) setForm({
      variety: gh.variety || '',
      growthStage: gh.growthStage || '',
      currentBatchNo: gh.currentBatchNo || ''
    })
  }, [gh])
  if (!gh) return null
  const save = async () => {
    await api.updateCropProfile(gh.id, form)
    message.success('种植档案已更新')
    onSaved()
  }
  return (
    <Modal title={`编辑种植档案：${gh.name}`} open={!!gh} onCancel={onClose} onOk={save} okText="保存">
      <Space direction="vertical" style={{ width: '100%' }}>
        <div><label>作物品种</label>
          <Input value={form.variety} placeholder="如 番茄-佳粉18号"
                 onChange={e => setForm({ ...form, variety: e.target.value })} /></div>
        <div><label>当前生育期</label>
          <Input value={form.growthStage} placeholder="苗期 / 开花期 / 结果期"
                 onChange={e => setForm({ ...form, growthStage: e.target.value })} /></div>
        <div><label>当前茬次/批次号</label>
          <Input value={form.currentBatchNo} placeholder="如 2026秋茬-1号棚（任务与产量据此关联）"
                 onChange={e => setForm({ ...form, currentBatchNo: e.target.value })} /></div>
      </Space>
    </Modal>
  )
}

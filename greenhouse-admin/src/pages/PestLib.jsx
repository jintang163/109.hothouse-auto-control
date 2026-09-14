import React, { useEffect, useState } from 'react'
import {
  Card, Table, Tag, Button, Space, Modal, Form, Input, Select, Drawer, Descriptions,
  Image, message, Popconfirm
} from 'antd'
import { PlusOutlined, EyeOutlined } from '@ant-design/icons'
import { api } from '../api'

const { TextArea } = Input

/** 病虫害知识库（图谱+防治）管理 + 移动端识别记录查看 */
export default function PestLib({ greenhouseId }) {
  const [list, setList] = useState([])
  const [diagnoses, setDiagnoses] = useState([])
  const [editing, setEditing] = useState(null)
  const [diagDetail, setDiagDetail] = useState(null)

  const load = async () => {
    const [k, d] = await Promise.all([api.pestKnowledge(), api.pestDiagnoses(greenhouseId)])
    setList(k); setDiagnoses(d)
  }
  useEffect(() => { load() }, [greenhouseId])

  const remove = async (k) => {
    await api.deletePestKnowledge(k.id)
    message.success('已删除')
    load()
  }

  const cols = [
    { title: '名称', dataIndex: 'name', width: 140, render: v => <b>{v}</b> },
    { title: '类型', dataIndex: 'category', width: 90,
      render: v => <Tag color={v === '虫害' ? 'orange' : 'red'}>{v}</Tag> },
    { title: '危害作物', dataIndex: 'crops', width: 160 },
    { title: '鉴别特征', dataIndex: 'featuresJson', render: v => {
      let fs = []
      try { fs = JSON.parse(v || '[]') } catch {}
      return <span>{fs.map(f => <Tag key={f}>{f}</Tag>)}</span>
    } },
    { title: '图谱', dataIndex: 'imageUrl', width: 70,
      render: v => v ? <Image width={44} src={v} /> : <span style={{ color: '#bbb' }}>—</span> },
    {
      title: '操作', width: 150, fixed: 'right',
      render: (_, r) => <Space size={4}>
        <Button size="small" onClick={() => setEditing(r)}>编辑</Button>
        <Popconfirm title="确认删除该条目？" onConfirm={() => remove(r)}>
          <Button size="small" danger>删除</Button>
        </Popconfirm>
      </Space>
    }
  ]

  const diagCols = [
    { title: '时间', dataIndex: 'createdAt', width: 170 },
    {
      title: '照片', dataIndex: 'photoBase64', width: 70,
      render: v => v
        ? <Image width={44} height={44} style={{ objectFit: 'cover', borderRadius: 4 }} src={v.slice(0, 120).startsWith('data:') ? v : v} />
        : <span style={{ color: '#bbb' }}>无</span>
    },
    { title: '识别结果', dataIndex: 'matchedName', width: 150,
      render: (v, r) => <Space size={4}>
        <b>{v}</b>
        {r.confidence != null && <Tag color={r.confidence >= 0.6 ? 'green' : 'gold'}>
          {Math.round((r.confidence || 0) * 100)}%</Tag>}
      </Space> },
    { title: '处置任务', dataIndex: 'taskCreated', width: 100,
      render: v => v ? <Tag color="blue">已生成</Tag> : <Tag>未生成</Tag> },
    { title: '操作人', dataIndex: 'operator', width: 100 },
    { title: '查看', width: 70, render: (_, r) =>
      <Button size="small" icon={<EyeOutlined />} onClick={() => setDiagDetail(r)}>详情</Button> }
  ]

  return (
    <div style={{ padding: 16 }}>
      <Card title="病虫害知识库（图谱 + 防治处方）" size="small" style={{ marginBottom: 12 }}
            extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing({})}>新增条目</Button>}>
        <Table rowKey="id" size="small" columns={cols} dataSource={list}
               scroll={{ x: 900 }} pagination={{ pageSize: 8 }} />
      </Card>

      <Card title="移动端识别记录（拍照 + 特征匹配）" size="small">
        <Table rowKey="id" size="small" columns={diagCols} dataSource={diagnoses}
               pagination={{ pageSize: 8 }} />
      </Card>

      <KnowledgeEditor entry={editing} onClose={() => setEditing(null)} onDone={() => { setEditing(null); load() }} />
      <DiagnosisDrawer d={diagDetail} onClose={() => setDiagDetail(null)} />
    </div>
  )
}

// ---------------- 知识库条目编辑 ----------------

function KnowledgeEditor({ entry, onClose, onDone }) {
  const [form] = Form.useForm()
  useEffect(() => {
    if (entry) {
      let features = []
      try { features = JSON.parse(entry.featuresJson || '[]') } catch {}
      form.setFieldsValue({
        ...entry,
        featuresText: (features || []).join('、')
      })
    }
  }, [entry])
  if (!entry) return null
  const submit = async () => {
    const v = await form.validateFields()
    const features = v.featuresText.split(/[、,，\n]/).map(s => s.trim()).filter(Boolean)
    const payload = {
      name: v.name, category: v.category, crops: v.crops,
      featuresJson: JSON.stringify(features),
      symptoms: v.symptoms, cause: v.cause,
      treatment: v.treatment, pesticide: v.pesticide,
      imageUrl: v.imageUrl || ''
    }
    if (entry.id) {
      await api.updatePestKnowledge(entry.id, payload)
      message.success('已更新')
    } else {
      await api.savePestKnowledge(payload)
      message.success('已新增')
    }
    onDone()
  }
  return (
    <Modal title={entry.id ? `编辑：${entry.name}` : '新增病虫害条目'} open={!!entry}
           onCancel={onClose} onOk={submit} okText="保存" width={720}>
      <Form form={form} layout="vertical" initialValues={{ category: '病害' }}>
        <Space>
          <Form.Item name="name" label="名称" rules={[{ required: true }]}>
            <Input style={{ width: 200 }} placeholder="如 番茄早疫病" />
          </Form.Item>
          <Form.Item name="category" label="类型" rules={[{ required: true }]}>
            <Select style={{ width: 140 }} options={[
              { value: '病害', label: '病害' },
              { value: '虫害', label: '虫害' },
              { value: '生理性病害', label: '生理性病害' }
            ]} />
          </Form.Item>
          <Form.Item name="crops" label="危害作物">
            <Input style={{ width: 220 }} placeholder="番茄,黄瓜" />
          </Form.Item>
        </Space>
        <Form.Item name="featuresText" label="鉴别特征（顿号/逗号分隔，供移动端勾选匹配）"
                   rules={[{ required: true }]}>
          <TextArea rows={2} placeholder="叶片褐色同心轮纹斑、下部老叶先发病、黑色霉层" />
        </Form.Item>
        <Form.Item name="symptoms" label="典型症状（图谱文字）">
          <TextArea rows={2} />
        </Form.Item>
        <Form.Item name="cause" label="发生条件 / 规律">
          <TextArea rows={2} />
        </Form.Item>
        <Form.Item name="treatment" label="防治措施" rules={[{ required: true }]}>
          <TextArea rows={3} />
        </Form.Item>
        <Form.Item name="pesticide" label="推荐用药（剂量 / 安全间隔期）">
          <TextArea rows={2} />
        </Form.Item>
        <Form.Item name="imageUrl" label="图谱图片 URL（可空）">
          <Input placeholder="预留：接入图库后填写" />
        </Form.Item>
      </Form>
    </Modal>
  )
}

// ---------------- 识别详情 ----------------

function DiagnosisDrawer({ d, onClose }) {
  if (!d) return null
  let candidates = []
  let selected = []
  try { candidates = JSON.parse(d.candidatesJson || '[]') } catch {}
  try { selected = JSON.parse(d.selectedFeaturesJson || '[]') } catch {}
  return (
    <Drawer title="病虫害识别详情" width={560} open={!!d} onClose={onClose}>
      {d.photoBase64 && (
        <Image src={d.photoBase64.startsWith('data:') ? d.photoBase64 : d.photoBase64}
               style={{ maxWidth: '100%', borderRadius: 8, marginBottom: 12 }} />
      )}
      <Descriptions column={1} bordered size="small">
        <Descriptions.Item label="时间">{d.createdAt}</Descriptions.Item>
        <Descriptions.Item label="识别结果">
          <b>{d.matchedName}</b>
          {d.confidence != null && <Tag color={d.confidence >= 0.6 ? 'green' : 'gold'} style={{ marginLeft: 8 }}>
            置信度 {Math.round((d.confidence || 0) * 100)}%</Tag>}
        </Descriptions.Item>
        <Descriptions.Item label="勾选特征">
          {selected.map(f => <Tag key={f}>{f}</Tag>)}
        </Descriptions.Item>
      </Descriptions>
      <Card size="small" style={{ marginTop: 12 }} title="候选列表">
        {candidates.length ? candidates.map(c =>
          <div key={c.id} style={{ marginBottom: 4 }}>
            {c.name}（{c.category}） · 命中 {c.hit}/{c.total} · <b>{Math.round(c.confidence * 100)}%</b>
          </div>
        ) : <span style={{ color: '#999' }}>无候选</span>}
      </Card>
      <Card size="small" style={{ marginTop: 12 }} title="推送的处置建议">
        <pre style={{ whiteSpace: 'pre-wrap', margin: 0, fontFamily: 'inherit', fontSize: 13 }}>{d.advice}</pre>
      </Card>
    </Drawer>
  )
}

import React, { useEffect, useState } from 'react'
import { Card, Form, InputNumber, Input, Switch, Button, Space, message, Divider, Popconfirm } from 'antd'
import { api } from '../api'

/** 作物策略配置：阈值 / 回差 / 防抖 / 冷却 / 定时计划 */
export default function Strategy({ greenhouseId }) {
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [scheduleText, setScheduleText] = useState('[]')

  useEffect(() => {
    api.strategy(greenhouseId).then(s => {
      if (s) {
        form.setFieldsValue(s)
        setScheduleText(s.scheduleJson || '[]')
      }
    })
  }, [greenhouseId])

  const save = async () => {
    const values = await form.validateFields()
    // 校验定时计划 JSON
    let items
    try {
      items = JSON.parse(scheduleText)
      if (!Array.isArray(items)) throw new Error()
      items.forEach(it => {
        if (!it.time || !it.deviceType || !it.action) {
          throw new Error('每项需含 time / deviceType / action')
        }
        if (!['FAN', 'WET_CURTAIN', 'SHADE_NET'].includes(it.deviceType)) {
          throw new Error(`deviceType 非法：${it.deviceType}`)
        }
      })
    } catch (e) {
      message.error('定时计划 JSON 不合法：' + e.message)
      return
    }
    setLoading(true)
    try {
      await api.saveStrategy(greenhouseId, {
        ...values,
        scheduleJson: JSON.stringify(items)
      })
      message.success('策略已保存（自动模式下立即生效）')
    } finally {
      setLoading(false)
    }
  }

  return (
    <Card title="作物环控策略配置" style={{ margin: 16, maxWidth: 820 }}>
      <Form form={form} layout="vertical"
            initialValues={{ name: '新策略', enabled: true, debounceSec: 10, cooldownSec: 60 }}>
        <Form.Item name="name" label="策略名称" rules={[{ required: true }]}>
          <Input placeholder="如：番茄-结果期环控策略" />
        </Form.Item>
        <Form.Item name="enabled" label="启用策略" valuePropName="checked">
          <Switch checkedChildren="启用" unCheckedChildren="停用" />
        </Form.Item>

        <Divider orientation="left">温度（℃）— 联动湿帘 + 风机降温</Divider>
        <Space wrap>
          <Form.Item name="tempHigh" label="降温启动线 ≥" rules={[{ required: true }]}>
            <InputNumber min={0} max={60} step={0.5} addonAfter="℃" style={{ width: 160 }} />
          </Form.Item>
          <Form.Item name="tempRecover" label="降温恢复点 ≤" rules={[{ required: true }]}>
            <InputNumber min={0} max={60} step={0.5} addonAfter="℃" style={{ width: 160 }} />
          </Form.Item>
          <Form.Item name="tempCritical" label="越限告警线 ≥" rules={[{ required: true }]}>
            <InputNumber min={0} max={60} step={0.5} addonAfter="℃" style={{ width: 160 }} />
          </Form.Item>
        </Space>

        <Divider orientation="left">湿度（%）— 联动湿帘加湿</Divider>
        <Space wrap>
          <Form.Item name="humiLow" label="加湿启动 ≤" rules={[{ required: true }]}>
            <InputNumber min={0} max={100} step={1} addonAfter="%" style={{ width: 160 }} />
          </Form.Item>
          <Form.Item name="humiRecover" label="加湿恢复 ≥" rules={[{ required: true }]}>
            <InputNumber min={0} max={100} step={1} addonAfter="%" style={{ width: 160 }} />
          </Form.Item>
        </Space>

        <Divider orientation="left">光照（lux）— 联动遮阳网</Divider>
        <Form.Item name="lightHigh" label="遮阳展开 ≥" rules={[{ required: true }]}>
          <InputNumber min={0} max={200000} step={1000} addonAfter="lux" style={{ width: 200 }} />
        </Form.Item>

        <Divider orientation="left">防抖与冷却</Divider>
        <Space wrap>
          <Form.Item name="debounceSec" label="条件持续（防抖）" tooltip="阈值条件需持续该秒数才触发，过滤瞬时波动">
            <InputNumber min={0} max={600} addonAfter="秒" style={{ width: 160 }} />
          </Form.Item>
          <Form.Item name="cooldownSec" label="动作最小间隔（冷却）" tooltip="同一执行器两次动作的最小间隔，防频繁启停">
            <InputNumber min={0} max={3600} addonAfter="秒" style={{ width: 160 }} />
          </Form.Item>
        </Space>

        <Divider orientation="left">定时计划（切到「定时」模式后按计划执行）</Divider>
        <Input.TextArea rows={5} value={scheduleText} onChange={e => setScheduleText(e.target.value)} />
        <small style={{ color: '#999' }}>
          JSON 数组，每项：time(HH:mm)、deviceType(FAN/WET_CURTAIN/SHADE_NET)、action(ON/OFF/OPEN/CLOSE)。
          示例：[{'"time":"11:30","deviceType":"SHADE_NET","action":"OPEN"'}]
        </small>

        <div style={{ marginTop: 16 }}>
          <Space>
            <Button type="primary" loading={loading} onClick={save}>保存策略</Button>
            <Popconfirm title="重置为当前已保存内容？" onConfirm={() => { api.strategy(greenhouseId).then(s => { form.setFieldsValue(s); setScheduleText(s.scheduleJson || '[]') }) }}>
              <Button>重置</Button>
            </Popconfirm>
          </Space>
        </div>
      </Form>
    </Card>
  )
}

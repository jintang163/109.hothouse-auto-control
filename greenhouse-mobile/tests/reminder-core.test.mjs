// 保养提醒通知核心逻辑测试（node --test，无第三方依赖）。
// 重点覆盖后台/离线场景：断线重连或恢复前台后的补拉会与已通知状态 diff，
// 状态不变不重复打扰，升级才再次通知，登记保养后重新出现可再通知。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  diffReminderNotifications,
  buildReminderMessage,
  buildSummaryText,
  severityOf
} from '../src/common/reminder-core.mjs'

const fanDueSoon = {
  deviceSn: 'FAN-001', deviceName: '1号风机', status: 'DUE_SOON',
  totalRunHours: 496.2, remainingHours: 3.8
}
const fanOverdue = {
  ...fanDueSoon, status: 'OVERDUE', totalRunHours: 501.2, remainingHours: -1.2
}
const wcDueSoon = {
  deviceSn: 'WC-001', deviceName: '1号湿帘水泵', status: 'DUE_SOON',
  totalRunHours: 498.0, remainingHours: 2.0
}

test('新出现的提醒触发通知', () => {
  const { notifications, nextState } = diffReminderNotifications({}, [fanDueSoon])
  assert.equal(notifications.length, 1)
  assert.equal(notifications[0].deviceSn, 'FAN-001')
  assert.deepEqual(nextState, { 'FAN-001': 'DUE_SOON' })
})

test('状态不变不重复通知（断线重连/后台恢复后的补拉场景）', () => {
  const first = diffReminderNotifications({}, [fanDueSoon])
  // 模拟重连后再次拉到同样的提醒
  const second = diffReminderNotifications(first.nextState, [fanDueSoon])
  assert.equal(second.notifications.length, 0)
  assert.deepEqual(second.nextState, first.nextState)
})

test('临近升级为到期会再次通知', () => {
  const first = diffReminderNotifications({}, [fanDueSoon])
  const second = diffReminderNotifications(first.nextState, [fanOverdue])
  assert.equal(second.notifications.length, 1)
  assert.equal(second.notifications[0].status, 'OVERDUE')
  assert.equal(second.nextState['FAN-001'], 'OVERDUE')
})

test('降级不通知但更新状态', () => {
  const first = diffReminderNotifications({}, [fanOverdue])
  const second = diffReminderNotifications(first.nextState, [fanDueSoon])
  assert.equal(second.notifications.length, 0)
  assert.equal(second.nextState['FAN-001'], 'DUE_SOON')
})

test('提醒消失后重新出现会再次通知（保养登记后周期重新起算）', () => {
  const first = diffReminderNotifications({}, [fanOverdue])
  // 登记保养 → 提醒列表变空 → 状态清空
  const cleared = diffReminderNotifications(first.nextState, [])
  assert.equal(cleared.notifications.length, 0)
  assert.deepEqual(cleared.nextState, {})
  // 下一周期再次临近 → 重新通知
  const again = diffReminderNotifications(cleared.nextState, [fanDueSoon])
  assert.equal(again.notifications.length, 1)
})

test('离线期间多台设备同时到期：补拉后合并为一批通知', () => {
  // 模拟离线前只有 FAN-001 已通知，离线期间 WC-001 也临近
  const prev = { 'FAN-001': 'DUE_SOON' }
  const { notifications, nextState } = diffReminderNotifications(prev, [fanDueSoon, wcDueSoon])
  assert.equal(notifications.length, 1)
  assert.equal(notifications[0].deviceSn, 'WC-001')
  assert.deepEqual(nextState, { 'FAN-001': 'DUE_SOON', 'WC-001': 'DUE_SOON' })
})

test('非提醒状态（OK/NO_RULE/未知）不产生通知', () => {
  const ok = { ...fanDueSoon, status: 'OK' }
  const noRule = { ...fanDueSoon, status: 'NO_RULE' }
  const { notifications, nextState } = diffReminderNotifications({}, [ok, noRule, null, {}])
  assert.equal(notifications.length, 0)
  assert.deepEqual(nextState, {})
})

test('severityOf 只认识两种提醒级别', () => {
  assert.equal(severityOf('OVERDUE'), 2)
  assert.equal(severityOf('DUE_SOON'), 1)
  assert.equal(severityOf('OK'), 0)
  assert.equal(severityOf(undefined), 0)
})

test('文案：临近含剩余小时，到期含超期小时', () => {
  const soon = buildReminderMessage(fanDueSoon)
  assert.match(soon, /1号风机/)
  assert.match(soon, /496\.2h/)
  assert.match(soon, /剩 3\.8h/)
  const over = buildReminderMessage(fanOverdue)
  assert.match(over, /已超保养期 1\.2h/)
})

test('汇总：有到期优先报到期，超过 3 台折叠', () => {
  const one = buildSummaryText([fanDueSoon])
  assert.equal(one.title, '设备临近保养 1 台')
  assert.match(one.body, /1号风机/)
  const many = buildSummaryText([fanOverdue, wcDueSoon,
    { ...wcDueSoon, deviceSn: 'SN-001', deviceName: '遮阳网1' },
    { ...wcDueSoon, deviceSn: 'SN-002', deviceName: '遮阳网2' }])
  assert.equal(many.title, '设备保养到期 1 台')
  assert.match(many.body, /等 4 台设备/)
})

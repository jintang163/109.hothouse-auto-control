// 保养提醒通知的纯逻辑核心：不依赖 uni.* / 浏览器 API，可在 Node 中直接单测。
// 已通知状态口径：{ [deviceSn]: 'DUE_SOON' | 'OVERDUE' }，由调用方持久化。

const SEVERITY = { DUE_SOON: 1, OVERDUE: 2 }

export function severityOf(status) {
  return SEVERITY[status] || 0
}

/**
 * 对比「上次已通知状态」与「当前提醒列表」，得出本次应通知的提醒与新的状态。
 * 规则：
 *  - 新出现的提醒 → 通知；
 *  - 级别升级（临近 DUE_SOON → 到期 OVERDUE）→ 再次通知；
 *  - 级别不变或降级 → 不重复通知（断线重连 / 后台恢复后的补拉不会重复打扰），仅更新状态；
 *  - 已消失的提醒（保养已登记、恢复 OK）→ 移出状态，下次出现时可重新通知。
 */
export function diffReminderNotifications(prevState, reminders) {
  const prev = prevState || {}
  const nextState = {}
  const notifications = []
  for (const r of reminders || []) {
    if (!r || !r.deviceSn) continue
    const sev = severityOf(r.status)
    if (sev <= 0) continue
    nextState[r.deviceSn] = r.status
    if (sev > severityOf(prev[r.deviceSn])) {
      notifications.push(r)
    }
  }
  return { notifications, nextState }
}

/** 单台设备的提醒文案 */
export function buildReminderMessage(r) {
  const name = r.deviceName || r.deviceSn
  const hours = typeof r.totalRunHours === 'number' ? r.totalRunHours.toFixed(1) : r.totalRunHours
  if (r.status === 'OVERDUE') {
    const over = r.remainingHours != null ? Math.abs(r.remainingHours).toFixed(1) : '?'
    return `${name}：累计运行 ${hours}h，已超保养期 ${over}h，请尽快保养`
  }
  const left = r.remainingHours != null ? r.remainingHours.toFixed(1) : '?'
  return `${name}：累计运行 ${hours}h，临近保养周期（剩 ${left}h），建议提前安排`
}

/** 多条提醒合并为一条系统通知的标题与正文 */
export function buildSummaryText(notifications) {
  const overdueCount = notifications.filter(r => r.status === 'OVERDUE').length
  const title = overdueCount
    ? `设备保养到期 ${overdueCount} 台`
    : `设备临近保养 ${notifications.length} 台`
  let body = notifications.slice(0, 3).map(buildReminderMessage).join('\n')
  if (notifications.length > 3) {
    body += `\n等 ${notifications.length} 台设备…`
  }
  return { title, body }
}

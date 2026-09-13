// 保养提醒通知适配层：系统级通知优先，应用内提醒兜底。
//  - H5：浏览器 Notification（系统通知，标签页在后台时也可弹出，需用户授权）；
//  - App：plus.push.createMessage 本地系统通知（落在系统通知栏，无需联网）；
//  - 小程序/其他：无系统通知能力，降级为应用内 toast。
// 注意：本层只做「本地」通知。App 被杀进程后的离线触达需要 UniPush 厂商通道
// （属后续扩展，见 README「后续可扩展」）；后台/离线期间错过的状态变化，
// 由恢复前台/重连后的补拉 diff 补发（纯逻辑见 reminder-core.mjs）。
import { diffReminderNotifications, buildReminderMessage, buildSummaryText } from './reminder-core.mjs'

const STATE_KEY = 'maintNotifyState'

/** 当前平台系统通知能力：'system' 可发系统级通知 / 'in-app' 仅应用内提醒 */
export function notifyCapability() {
  // #ifdef H5
  return (typeof window !== 'undefined' && 'Notification' in window) ? 'system' : 'in-app'
  // #endif
  // #ifdef APP-PLUS
  return 'system'
  // #endif
  // #ifndef H5 || APP-PLUS
  return 'in-app'
  // #endif
}

/** 系统通知授权状态：'granted' | 'denied' | 'default'（未询问） | 'unsupported' */
export function systemNotifyPermission() {
  // #ifdef H5
  if (typeof window === 'undefined' || !('Notification' in window)) return 'unsupported'
  return Notification.permission
  // #endif
  // #ifdef APP-PLUS
  // plus.push 本地通知无需运行时授权；Android 13+ 若被系统设置关闭通知，createMessage 不弹栏，
  // 真机验证时请在系统设置中确认应用通知开关（见 README 移动端通知自检）。
  return 'granted'
  // #endif
  // #ifndef H5 || APP-PLUS
  return 'unsupported'
  // #endif
}

/** 请求系统通知授权（H5 需用户手势触发才有效）；resolve 为当前是否已可发系统通知 */
export function ensureSystemNotifyPermission() {
  // #ifdef H5
  return new Promise((resolve) => {
    if (typeof window === 'undefined' || !('Notification' in window)) { resolve(false); return }
    if (Notification.permission !== 'default') { resolve(Notification.permission === 'granted'); return }
    Notification.requestPermission()
      .then(p => resolve(p === 'granted'))
      .catch(() => resolve(false))
  })
  // #endif
  // #ifdef APP-PLUS
  return Promise.resolve(true)
  // #endif
  // #ifndef H5 || APP-PLUS
  return Promise.resolve(false)
  // #endif
}

/** 发一条系统级通知；返回是否真正落到了系统通知通道（false → 调用方走应用内兜底） */
export function sendSystemNotification(title, body) {
  // #ifdef H5
  if (typeof window !== 'undefined' && 'Notification' in window && Notification.permission === 'granted') {
    try {
      // tag 相同会替换旧通知，避免连续提醒在通知栏刷屏
      new Notification(title, { body, tag: 'gh-maintenance', renotify: true })
      return true
    } catch (e) { /* 个别 WebView 构造即抛错，落到应用内兜底 */ }
  }
  return false
  // #endif
  // #ifdef APP-PLUS
  try {
    plus.push.createMessage(body, title, { cover: false, sound: 'system' })
    return true
  } catch (e) {
    return false
  }
  // #endif
  // #ifndef H5 || APP-PLUS
  return false
  // #endif
}

/**
 * 保养提醒主入口：每次拉取到最新提醒列表后调用（WS maintenance 帧、页面 onShow、
 * 断线重连补拉都会走到）。新出现/升级的提醒优先发系统级通知，系统通道不可用时
 * 降级为应用内 toast；已通知状态持久化在本地存储，重连补拉不会重复打扰。
 * 返回 { notified, viaSystem } 供自检与日志。
 */
export function notifyMaintenanceReminders(reminders) {
  let prev = {}
  try { prev = uni.getStorageSync(STATE_KEY) || {} } catch (e) { /* 存储不可用时按无历史处理 */ }
  const { notifications, nextState } = diffReminderNotifications(prev, reminders)
  try { uni.setStorageSync(STATE_KEY, nextState) } catch (e) { /* 忽略 */ }
  if (!notifications.length) {
    return { notified: 0, viaSystem: false }
  }
  const { title, body } = buildSummaryText(notifications)
  const viaSystem = sendSystemNotification(title, body)
  if (!viaSystem) {
    uni.showToast({
      title: notifications.length > 1 ? `${title}，请查看保养提醒` : buildReminderMessage(notifications[0]),
      icon: 'none',
      duration: 4000
    })
  }
  return { notified: notifications.length, viaSystem }
}

// REST 封装 + WebSocket 实时订阅
const BASE = ''

async function request(path, options = {}) {
  const resp = await fetch(BASE + path, {
    headers: { 'Content-Type': 'application/json' },
    ...options
  })
  const body = await resp.json().catch(() => { throw new Error('响应解析失败') })
  if (body.code !== 0) {
    throw new Error(body.message || '请求失败')
  }
  return body.data
}

export const api = {
  listGreenhouses: () => request('/api/greenhouses'),
  overview: (id) => request(`/api/greenhouses/${id}/overview`),
  setMode: (id, mode, operator = 'admin') =>
    request(`/api/greenhouses/${id}/mode?mode=${mode}&operator=${operator}`, { method: 'PUT' }),

  devices: (ghId) => request(`/api/devices?greenhouseId=${ghId}`),
  control: (payload) => request('/api/control', { method: 'POST', body: JSON.stringify(payload) }),

  strategy: (ghId) => request(`/api/strategies/${ghId}`),
  saveStrategy: (ghId, payload) =>
    request(`/api/strategies/${ghId}`, { method: 'PUT', body: JSON.stringify(payload) }),

  history: (ghId, metric, from, to) =>
    request(`/api/sensor/history?greenhouseId=${ghId}&metric=${metric}`
      + (from ? `&from=${from}` : '') + (to ? `&to=${to}` : '')),

  alarms: (status) => request(`/api/alarms${status ? `?status=${status}` : ''}`),
  handleAlarm: (id, operator = 'admin') =>
    request(`/api/alarms/${id}/handle?operator=${operator}`, { method: 'POST' }),

  logs: (ghId) => request(`/api/logs?greenhouseId=${ghId}`),
  commands: (ghId) => request(`/api/commands?greenhouseId=${ghId}`),
  inspections: (ghId) => request(`/api/inspections?greenhouseId=${ghId}`)
}

/**
 * 订阅 /ws/realtime。
 * onEvent(event, data)：sensor / device / alarm / command
 */
export function openRealtimeSocket(onEvent, onStatus) {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  let ws
  let closed = false
  let retryTimer

  function connect() {
    ws = new WebSocket(`${proto}://${location.host}/ws/realtime`)
    ws.onopen = () => onStatus?.(true)
    ws.onclose = () => {
      onStatus?.(false)
      if (!closed) retryTimer = setTimeout(connect, 3000)
    }
    ws.onerror = () => ws.close()
    ws.onmessage = (e) => {
      try {
        const msg = JSON.parse(e.data)
        onEvent(msg.event, msg.data)
      } catch { /* 忽略非法帧 */ }
    }
  }
  connect()
  return () => { closed = true; clearTimeout(retryTimer); ws?.close() }
}

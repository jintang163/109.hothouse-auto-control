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
  inspections: (ghId) => request(`/api/inspections?greenhouseId=${ghId}`),

  // 设备运维
  maintenanceLedger: (ghId) => request(`/api/maintenance/ledger${ghId ? `?greenhouseId=${ghId}` : ''}`),
  maintenanceReminders: (ghId) =>
    request(`/api/maintenance/reminders${ghId ? `?greenhouseId=${ghId}` : ''}`),
  maintenanceRules: () => request('/api/maintenance/rules'),
  saveMaintenanceRules: (rules) =>
    request('/api/maintenance/rules', { method: 'PUT', body: JSON.stringify(rules) }),
  addMaintenanceRecord: (payload) =>
    request('/api/maintenance/records', { method: 'POST', body: JSON.stringify(payload) }),
  maintenanceRecords: (sn) =>
    request(`/api/maintenance/records${sn ? `?deviceSn=${sn}` : ''}`),
  faultStats: (days = 30) => request(`/api/maintenance/fault-stats?days=${days}`),
  runMaintenance: () => request('/api/maintenance/run-now', { method: 'POST' }),

  // 大棚种植档案（品种/生育期/茬次）
  updateCropProfile: (id, payload) =>
    request(`/api/greenhouses/${id}/crop-profile`, { method: 'PUT', body: JSON.stringify(payload) }),

  // 农事处方库
  prescriptions: () => request('/api/prescriptions'),
  prescription: (id) => request(`/api/prescriptions/${id}`),
  prescriptionVersions: (variety, growthStage) =>
    request(`/api/prescriptions/versions?variety=${encodeURIComponent(variety)}&growthStage=${encodeURIComponent(growthStage)}`),
  createPrescription: (payload, operator = 'admin') =>
    request(`/api/prescriptions?operator=${operator}`, { method: 'POST', body: JSON.stringify(payload) }),
  updatePrescription: (id, payload) =>
    request(`/api/prescriptions/${id}`, { method: 'PUT', body: JSON.stringify(payload) }),
  copyPrescription: (id, operator = 'admin') =>
    request(`/api/prescriptions/${id}/copy?operator=${operator}`, { method: 'POST' }),
  publishPrescription: (id) =>
    request(`/api/prescriptions/${id}/publish`, { method: 'POST' }),
  archivePrescription: (id) =>
    request(`/api/prescriptions/${id}/archive`, { method: 'POST' }),

  // 农事任务
  farmTasks: (ghId) => request(`/api/farm-tasks${ghId ? `?greenhouseId=${ghId}` : ''}`),
  createFarmTask: (payload, operator = 'admin') =>
    request(`/api/farm-tasks?operator=${operator}`, { method: 'POST', body: JSON.stringify(payload) }),
  runDevices: (id, operator = 'admin') =>
    request(`/api/farm-tasks/${id}/run-devices?operator=${operator}`, { method: 'POST' }),
  completeManual: (id, payload) =>
    request(`/api/farm-tasks/${id}/complete-manual`, { method: 'POST', body: JSON.stringify(payload) }),
  cancelFarmTask: (id, operator = 'admin') =>
    request(`/api/farm-tasks/${id}/cancel?operator=${operator}`, { method: 'POST' }),

  // 产量 / 农事日志分析
  yields: (ghId) => request(`/api/yields${ghId ? `?greenhouseId=${ghId}` : ''}`),
  addYield: (payload) => request('/api/yields', { method: 'POST', body: JSON.stringify(payload) }),
  yieldAnalysis: (ghId) => request(`/api/yields/analysis${ghId ? `?greenhouseId=${ghId}` : ''}`),

  // 病虫害
  pestKnowledge: (keyword) => request(`/api/pest/knowledge${keyword ? `?keyword=${encodeURIComponent(keyword)}` : ''}`),
  savePestKnowledge: (payload) =>
    request('/api/pest/knowledge', { method: 'POST', body: JSON.stringify(payload) }),
  updatePestKnowledge: (id, payload) =>
    request(`/api/pest/knowledge/${id}`, { method: 'PUT', body: JSON.stringify(payload) }),
  deletePestKnowledge: (id) =>
    request(`/api/pest/knowledge/${id}`, { method: 'DELETE' }),
  pestDiagnoses: (ghId) => request(`/api/pest/diagnoses${ghId ? `?greenhouseId=${ghId}` : ''}`)
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

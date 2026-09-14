// 后端接口封装（uni.request 全端可用：H5 / App / 小程序）
import { store } from './store.js'
// #ifdef H5
const BASE = '' // H5 开发态走 vite 代理（vite.config.js 已将 /api 代理到 8080）
// #endif
// #ifndef H5
const BASE = 'http://localhost:8080' // App/小程序真机改为实际服务地址
// #endif

export function request(path, method = 'GET', data) {
  return new Promise((resolve, reject) => {
    uni.request({
      url: BASE + path,
      method,
      data,
      header: { 'Content-Type': 'application/json' },
      success: (res) => {
        const body = res.data || {}
        if (body.code === 0) {
          resolve(body.data)
        } else {
          uni.showToast({ title: body.message || '请求失败', icon: 'none' })
          reject(new Error(body.message || '请求失败'))
        }
      },
      fail: (err) => {
        uni.showToast({ title: '网络异常', icon: 'none' })
        reject(err)
      }
    })
  })
}

export const api = {
  greenhouses: () => request('/api/greenhouses'),
  overview: (id) => request(`/api/greenhouses/${id}/overview`),
  setMode: (id, mode, operator) =>
    request(`/api/greenhouses/${id}/mode?mode=${mode}&operator=${encodeURIComponent(operator)}`, 'PUT'),
  control: (payload) => request('/api/control', 'POST', payload),
  commands: (ghId) => request(`/api/commands?greenhouseId=${ghId}`),
  alarms: (status) => request(`/api/alarms${status ? `?status=${status}` : ''}`),
  handleAlarm: (id, operator) =>
    request(`/api/alarms/${id}/handle?operator=${encodeURIComponent(operator)}`, 'POST'),
  inspections: (ghId) => request(`/api/inspections?greenhouseId=${ghId}`),
  addInspection: (payload) => request('/api/inspections', 'POST', payload),
  maintenanceReminders: (ghId) =>
    request(`/api/maintenance/reminders${ghId ? `?greenhouseId=${ghId}` : ''}`),
  maintenanceLedger: (ghId) =>
    request(`/api/maintenance/ledger${ghId ? `?greenhouseId=${ghId}` : ''}`),
  addMaintenanceRecord: (payload) => request('/api/maintenance/records', 'POST', payload),

  // 农事处方与任务
  farmTasks: (ghId) => request(`/api/farm-tasks${ghId ? `?greenhouseId=${ghId}` : ''}`),
  runDevices: (id, operator) =>
    request(`/api/farm-tasks/${id}/run-devices?operator=${encodeURIComponent(operator || store.operator)}`, 'POST'),
  completeManual: (id, payload) =>
    request(`/api/farm-tasks/${id}/complete-manual`, 'POST', payload),
  cancelFarmTask: (id, operator) =>
    request(`/api/farm-tasks/${id}/cancel?operator=${encodeURIComponent(operator || store.operator)}`, 'POST'),
  prescriptions: () => request('/api/prescriptions'),

  // 病虫害知识库 + 识别
  pestKnowledge: () => request('/api/pest/knowledge'),
  pestFeatures: () => request('/api/pest/features'),
  pestDiagnose: (payload) => request('/api/pest/diagnose', 'POST', payload),
  pestCreateTask: (id, operator) =>
    request(`/api/pest/diagnoses/${id}/create-task?operator=${encodeURIComponent(operator || store.operator)}`, 'POST')
}

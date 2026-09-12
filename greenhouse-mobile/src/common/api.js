// 后端接口封装（uni.request 全端可用：H5 / App / 小程序）
const BASE = ''

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
  addInspection: (payload) => request('/api/inspections', 'POST', payload)
}

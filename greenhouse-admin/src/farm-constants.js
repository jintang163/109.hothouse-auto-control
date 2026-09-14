// 农事处方模块共享常量（与后端枚举保持一致）
export const METRICS = [
  { value: 'temperature', label: '温度', unit: '℃' },
  { value: 'humidity', label: '湿度', unit: '%' },
  { value: 'light', label: '光照', unit: 'lux' },
  { value: 'co2', label: 'CO₂', unit: 'ppm' }
]

export const TASK_TYPES = [
  { value: 'ENV_TEMP', label: '环境调控-温度' },
  { value: 'ENV_HUMIDITY', label: '环境调控-湿度' },
  { value: 'ENV_LIGHT', label: '环境调控-光照' },
  { value: 'ENV_CO2', label: '环境调控-CO₂' },
  { value: 'IRRIGATION', label: '灌溉' },
  { value: 'FERTIGATION', label: '施肥' },
  { value: 'PLANT_PROTECTION', label: '植保' },
  { value: 'PRUNING', label: '整枝农事' },
  { value: 'HARVEST', label: '采收' },
  { value: 'INSPECTION_TASK', label: '观察巡检' },
  { value: 'OTHER', label: '其他' }
]

export const TASK_STATUS = [
  { value: 'PENDING', label: '待执行', color: 'gold' },
  { value: 'EXECUTING', label: '联动中', color: 'blue' },
  { value: 'DONE', label: '已完成', color: 'green' },
  { value: 'FAILED', label: '联动失败', color: 'red' },
  { value: 'CANCELLED', label: '已取消', color: 'default' }
]

export const TRIGGER_TYPES = {
  DEVIATION: { label: '环境偏差', color: 'orange' },
  PERIODIC: { label: '周期计划', color: 'blue' },
  MANUAL: { label: '人工', color: 'default' },
  PEST: { label: '病虫害识别', color: 'volcano' }
}

export const EXEC_MODES = {
  DEVICE: { label: '设备联动', color: 'blue' },
  MANUAL: { label: '人工执行', color: 'default' },
  DEVICE_THEN_MANUAL: { label: '设备转人工', color: 'orange' }
}

export const DEVICE_TYPES = [
  { value: 'FAN', label: '风机', actions: ['ON', 'OFF'] },
  { value: 'WET_CURTAIN', label: '湿帘水泵', actions: ['OPEN', 'CLOSE'] },
  { value: 'SHADE_NET', label: '遮阳网', actions: ['OPEN', 'CLOSE'] }
]

export const RX_STATUS = {
  DRAFT: { label: '草稿', color: 'default' },
  PUBLISHED: { label: '已发布', color: 'green' },
  ARCHIVED: { label: '已归档', color: 'default' }
}

export const typeLabel = (v) => TASK_TYPES.find(t => t.value === v)?.label || v
export const statusMeta = (v) => TASK_STATUS.find(t => t.value === v) || { label: v, color: 'default' }
export const triggerMeta = (v) => TRIGGER_TYPES[v] || { label: v, color: 'default' }
export const execMeta = (v) => EXEC_MODES[v] || { label: v, color: 'default' }
export const metricLabel = (v) => METRICS.find(m => m.value === v)?.label || v

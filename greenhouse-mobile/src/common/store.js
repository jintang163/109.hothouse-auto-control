// 简单的跨页面共享状态（大棚选择）
import { reactive } from 'vue'

export const store = reactive({
  ghId: Number(uni.getStorageSync('ghId')) || 1,
  ghName: '',
  operator: uni.getStorageSync('operator') || '现场人员'
})

export function setGh(id) {
  store.ghId = id
  uni.setStorageSync('ghId', id)
}

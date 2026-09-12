<template>
  <view>
    <view class="card row" style="gap:16rpx">
      <view v-for="f in filters" :key="f.value"
            :class="['filter', filter===f.value ? 'filter-on' : '']"
            @click="switchFilter(f.value)">{{ f.label }}</view>
      <view style="flex:1"></view>
      <text class="muted">下拉刷新</text>
    </view>

    <view v-for="a in alarms" :key="a.id" class="card alarm-card"
          :class="a.level==='CRITICAL' ? 'alarm-critical' : (a.level==='WARN' ? 'alarm-warn' : '')">
      <view class="row between">
        <view>
          <text :class="['tag', levelClass(a.level)]">{{ levelText(a.level) }}</text>
          <text :class="['tag', a.status==='OPEN' ? 'tag-red' : 'tag-green']" style="margin-left:12rpx">
            {{ a.status === 'OPEN' ? '未处理' : '已处理' }}
          </text>
        </view>
        <text class="muted">{{ a.createdAt }}</text>
      </view>
      <view class="alarm-msg">{{ a.message }}</view>
      <view class="row between">
        <text class="muted">{{ typeText(a.type) }} · 大棚{{ a.greenhouseId }} · {{ a.deviceSn || '-' }}</text>
        <button v-if="a.status==='OPEN'" size="mini" class="btn-primary"
                @click="handle(a)">处理</button>
        <text v-else class="muted">{{ a.handledBy }} 已处置</text>
      </view>
    </view>

    <view v-if="!alarms.length" class="card muted" style="text-align:center">暂无告警</view>
  </view>
</template>

<script>
import { api } from '@/common/api.js'
import { store } from '@/common/store.js'

export default {
  data() {
    return {
      filter: 'OPEN',
      alarms: [],
      socket: null,
      filters: [
        { value: 'OPEN', label: '未处理' },
        { value: 'HANDLED', label: '已处理' },
        { value: '', label: '全部' }
      ]
    }
  },
  onShow() {
    this.load()
  },
  onPullDownRefresh() {
    this.load().finally(() => uni.stopPullDownRefresh())
  },
  methods: {
    async load() {
      this.alarms = await api.alarms(this.filter || undefined)
    },
    switchFilter(v) { this.filter = v; this.load() },
    handle(a) {
      uni.showModal({
        title: '处理告警',
        content: '确认该告警已现场处置？',
        success: async (r) => {
          if (!r.confirm) return
          await api.handleAlarm(a.id, store.operator)
          uni.showToast({ title: '已处理', icon: 'success' })
          this.load()
        }
      })
    },
    levelText(l) { return { INFO: '提示', WARN: '警告', CRITICAL: '严重' }[l] || l },
    levelClass(l) { return { INFO: 'tag-blue', WARN: 'tag-orange', CRITICAL: 'tag-red' }[l] || 'tag-gray' },
    typeText(t) {
      return { THRESHOLD: '阈值越限', DEVICE_OFFLINE: '设备离线',
               COMMAND_FAILED: '指令失败', INTERLOCK_BLOCKED: '互锁拦截' }[t] || t
    }
  }
}
</script>

<style lang="scss" scoped>
.filter {
  padding: 8rpx 28rpx; border-radius: 28rpx; background: #eef3ee;
  color: #5a6a5a; font-size: 26rpx;
}
.filter-on { background: #2e7d32; color: #fff; }
.alarm-card { border-left: 8rpx solid #d9d9d9; }
.alarm-warn { border-left-color: #fa8c16; }
.alarm-critical { border-left-color: #ff4d4f; }
.alarm-msg { font-size: 30rpx; margin: 16rpx 0; }
</style>

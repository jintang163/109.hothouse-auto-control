<template>
  <view>
    <!-- 大棚选择 + 模式 -->
    <view class="card">
      <view class="row between">
        <picker :range="ghNames" :value="ghIndex" @change="onPickGh">
          <view class="gh-name">🏡 {{ ghName || '加载中...' }} <text class="muted">▾</text></view>
        </picker>
        <view :class="['tag', modeTagClass]">{{ modeLabel }}</view>
      </view>
      <view class="muted" style="margin-top:8rpx">
        {{ ghInfo.location || '' }} · 作物：{{ ghInfo.crop || '-' }}
      </view>
      <view class="row" style="margin-top:16rpx;gap:12rpx">
        <button size="mini" :class="ghInfo.mode==='AUTO' ? 'btn-primary' : ''"
                @click="switchMode('AUTO')">自动</button>
        <button size="mini" :class="ghInfo.mode==='MANUAL' ? 'btn-primary' : ''"
                @click="switchMode('MANUAL')">手动</button>
        <button size="mini" :class="ghInfo.mode==='SCHEDULE' ? 'btn-primary' : ''"
                @click="switchMode('SCHEDULE')">定时</button>
        <view style="flex:1"></view>
        <view :class="['tag', wsOnline ? 'tag-green' : 'tag-gray']">
          {{ wsOnline ? '实时已连接' : '实时重连中' }}
        </view>
      </view>
    </view>

    <!-- 环境指标 -->
    <view class="grid">
      <view v-for="m in metrics" :key="m.key" class="metric">
        <view class="m-icon">{{ m.icon }}</view>
        <view class="m-value" :style="{ color: m.color }">
          {{ m.value != null ? m.value : '--' }}<text class="m-unit">{{ m.unit }}</text>
        </view>
        <view class="m-name">{{ m.name }}</view>
      </view>
    </view>

    <!-- 保养提醒 -->
    <view v-if="dueReminders.length" class="card maint-card"
          :class="overdueReminders.length ? 'maint-overdue' : 'maint-duesoon'"
          @click="goMaintenance">
      <view class="row between">
        <view class="row" style="gap:10rpx">
          <text style="font-size:34rpx">🔧</text>
          <text style="font-weight:600">保养提醒</text>
          <view :class="['tag', overdueReminders.length ? 'tag-red' : 'tag-orange']">
            {{ overdueReminders.length ? overdueReminders.length + ' 台已到期' : dueReminders.length + ' 台临近' }}
          </view>
        </view>
        <text class="muted">查看 ›</text>
      </view>
      <view style="margin-top:12rpx">
        <text v-for="(r, i) in dueReminders.slice(0, 2)" :key="r.deviceSn" class="maint-line">
          {{ r.deviceName }}：累计 {{ r.totalRunHours.toFixed(1) }}h，
          {{ r.status === 'OVERDUE'
             ? '已超期 ' + Math.abs(r.remainingHours).toFixed(1) + 'h'
             : '剩 ' + r.remainingHours.toFixed(1) + 'h' }}
        </text>
        <text v-if="dueReminders.length > 2" class="muted"> 等 {{ dueReminders.length }} 台设备…</text>
      </view>
    </view>

    <!-- 农事处方模块入口 -->
    <view class="card">
      <view class="section-title">农事生产</view>
      <view class="farm-entry-row">
        <view class="farm-entry" @click="goFarmTasks">
          <text class="farm-icon">🌾</text>
          <text class="farm-name">农事任务</text>
          <text class="muted">处方生成 · 联动/反馈</text>
        </view>
        <view class="farm-entry" @click="goPest">
          <text class="farm-icon">🐛</text>
          <text class="farm-name">病虫害识别</text>
          <text class="muted">拍照 · 图谱 · 防治</text>
        </view>
      </view>
    </view>

    <!-- 执行器状态 -->
    <view class="card">
      <view class="section-title">执行器状态</view>
      <view v-for="d in actuators" :key="d.sn" class="row between dev-row">
        <view>
          <text>{{ devMeta[d.type].icon }} {{ d.name }}</text>
          <view :class="['tag', d.online ? 'tag-green' : 'tag-gray']" style="margin-left:12rpx">
            {{ d.online ? '在线' : '离线' }}
          </view>
        </view>
        <view :style="{color: isOn(d) ? '#52c41a' : '#999', fontWeight: 600}">
          {{ d.state ? (isOn(d) ? devMeta[d.type].onText : devMeta[d.type].offText) : '未知' }}
        </view>
      </view>
      <view class="muted" style="margin-top:12rpx">
        提示：到「控阀」页可手动控制，指令受安全互锁保护。
      </view>
    </view>
  </view>
</template>

<script>
import { api } from '@/common/api.js'
import { store } from '@/common/store.js'
import { notifyMaintenanceReminders } from '@/common/notify.js'

export default {
  data() {
    return {
      greenhouses: [],
      ghInfo: {},
      latest: {},
      devices: [],
      reminders: [],
      wsOnline: false,
      socket: null,
      reconnectTimer: null,
      devMeta: {
        FAN: { icon: '🌀', on: 'ON', onText: '运行', offText: '停止' },
        WET_CURTAIN: { icon: '🚿', on: 'OPEN', onText: '开启', offText: '关闭' },
        SHADE_NET: { icon: '⛱', on: 'OPEN', onText: '展开', offText: '收拢' }
      }
    }
  },
  computed: {
    ghId() { return store.ghId },
    ghName() { return this.ghInfo.name },
    ghNames() { return this.greenhouses.map(g => g.name) },
    ghIndex() { return this.greenhouses.findIndex(g => g.id === this.ghId) },
    modeLabel() { return { AUTO: '自动', MANUAL: '手动', SCHEDULE: '定时' }[this.ghInfo.mode] || '-' },
    modeTagClass() {
      return { AUTO: 'tag-green', MANUAL: 'tag-orange', SCHEDULE: 'tag-blue' }[this.ghInfo.mode] || 'tag-gray'
    },
    actuators() {
      return this.devices.filter(d => this.devMeta[d.type])
    },
    dueReminders() {
      return this.reminders.filter(r => r.status === 'OVERDUE' || r.status === 'DUE_SOON')
    },
    overdueReminders() {
      return this.reminders.filter(r => r.status === 'OVERDUE')
    },
    metrics() {
      const v = this.latest
      return [
        { key: 'temperature', name: '温度', unit: '℃', icon: '🌡', color: '#f5222d',
          value: v.temperature ? Number(v.temperature.value).toFixed(1) : null },
        { key: 'humidity', name: '湿度', unit: '%', icon: '💧', color: '#1677ff',
          value: v.humidity ? Number(v.humidity.value).toFixed(1) : null },
        { key: 'light', name: '光照', unit: 'lux', icon: '☀', color: '#faad14',
          value: v.light ? Math.round(v.light.value) : null },
        { key: 'co2', name: 'CO₂', unit: 'ppm', icon: '🫧', color: '#722ed1',
          value: v.co2 ? Math.round(v.co2.value) : null }
      ]
    }
  },
  onShow() {
    this.loadOverview().then(() => this.connectWs())
  },
  created() {
    // 应用从后台恢复时（App.vue 广播）：WS 可能已被系统挂起断开，需重连并补拉
    uni.$on('app-foreground', this.onAppForeground)
  },
  onHide() { this.closeWs() },
  onUnload() {
    uni.$off('app-foreground', this.onAppForeground)
    this.closeWs()
  },
  onPullDownRefresh() {
    this.loadOverview().finally(() => uni.stopPullDownRefresh())
  },
  methods: {
    isOn(d) { return d.state === this.devMeta[d.type].on },
    onPickGh(e) {
      const g = this.greenhouses[Number(e.detail.value)]
      store.ghId = g.id
      uni.setStorageSync('ghId', g.id)
      this.loadOverview()
    },
    async loadOverview() {
      if (!this.greenhouses.length) {
        this.greenhouses = await api.greenhouses()
        if (!this.greenhouses.find(g => g.id === store.ghId) && this.greenhouses[0]) {
          store.ghId = this.greenhouses[0].id
        }
      }
      const o = await api.overview(store.ghId)
      this.ghInfo = o.greenhouse
      this.latest = o.latest || {}
      this.devices = o.devices || []
      this.loadReminders()
    },
    async loadReminders() {
      try {
        this.reminders = await api.maintenanceReminders(store.ghId)
        // 新出现/升级的保养提醒：系统级通知优先，应用内提醒兜底；
        // 后台/离线恢复后的补拉也走这里，由 notify 层按状态去重，不会重复打扰
        notifyMaintenanceReminders(this.reminders)
      } catch (e) { /* 忽略 */ }
    },
    onAppForeground() {
      // 后台/离线恢复入口：补拉总览与提醒（错过的变化在此补发通知），并重连 WS
      this.loadOverview().then(() => this.connectWs())
    },
    goMaintenance() {
      uni.navigateTo({ url: '/pages/maintenance/maintenance' })
    },
    goFarmTasks() {
      uni.navigateTo({ url: '/pages/task/task' })
    },
    goPest() {
      uni.navigateTo({ url: '/pages/pest/pest' })
    },
    async switchMode(mode) {
      await api.setMode(store.ghId, mode, store.operator)
      uni.showToast({ title: `已切${ {AUTO:'自动',MANUAL:'手动',SCHEDULE:'定时'}[mode] }模式`, icon: 'none' })
      this.loadOverview()
    },
    connectWs() {
      this.closeWs(false)
      // #ifdef H5
      const proto = location.protocol === 'https:' ? 'wss' : 'ws'
      const url = `${proto}://${location.host}/ws/realtime`
      // #endif
      // #ifndef H5
      const url = 'ws://localhost:8080/ws/realtime'  // App/小程序真机改为实际服务地址
      // #endif
      const socket = uni.connectSocket({ url, complete: () => {} })
      this.socket = socket
      socket.onOpen(() => { this.wsOnline = true })
      socket.onMessage((e) => {
        let msg
        try { msg = JSON.parse(e.data) } catch { return }
        this.onEvent(msg.event, msg.data)
      })
      socket.onClose(() => {
        this.wsOnline = false
        clearTimeout(this.reconnectTimer)
        this.reconnectTimer = setTimeout(() => this.connectWs(), 3000)
      })
      socket.onError(() => socket.close && socket.close({}))
    },
    closeWs(reconnect = true) {
      clearTimeout(this.reconnectTimer)
      if (this.socket) {
        if (!reconnect) this.socket.onClose(() => {})
        try { this.socket.close({}) } catch {}
        this.socket = null
      }
    },
    onEvent(event, data) {
      if (event === 'sensor' && data.greenhouseId === store.ghId) {
        const t = data.time
        this.latest = {
          ...this.latest,
          ...Object.fromEntries(Object.entries(data.values || {}).map(([k, val]) => [k, { value: val, time: t }]))
        }
      } else if (event === 'device' && data.greenhouseId === store.ghId) {
        this.devices = this.devices.map(d => d.sn === data.sn ? { ...d, ...data } : d)
      } else if (event === 'alarm') {
        if (!data.greenhouseId || data.greenhouseId === store.ghId) {
          uni.showToast({ title: '告警：' + data.message, icon: 'none', duration: 4000 })
        }
      } else if (event === 'maintenance') {
        this.loadReminders()
      }
    }
  }
}
</script>

<style lang="scss" scoped>
.gh-name { font-size: 34rpx; font-weight: 600; }
.section-title { font-weight: 600; margin-bottom: 12rpx; }
.farm-entry-row { display: flex; gap: 20rpx; }
.farm-entry {
  flex: 1; border: 1rpx solid #dce8dc; border-radius: 14rpx;
  padding: 20rpx; display: flex; flex-direction: column; gap: 8rpx;
}
.farm-entry:active { background: #f2f8f1; }
.farm-icon { font-size: 48rpx; }
.farm-name { font-weight: 600; font-size: 29rpx; color: #1f3a1f; }
.grid {
  display: flex; flex-wrap: wrap; padding: 0 10rpx;
}
.metric {
  width: 50%; box-sizing: border-box; padding: 10rpx;
}
.metric view {
  background: #fff; border-radius: 16rpx; padding: 24rpx;
  box-shadow: 0 2rpx 12rpx rgba(46,125,50,.06);
}
.m-icon { font-size: 44rpx; }
.m-value { font-size: 46rpx; font-weight: 700; line-height: 1.3; }
.m-unit { font-size: 24rpx; color: #999; margin-left: 4rpx; }
.m-name { color: #7a8a7a; font-size: 26rpx; }
.dev-row { padding: 18rpx 0; border-bottom: 1rpx solid #f0f2f0; }
.dev-row:last-child { border-bottom: none; }
.maint-card { border-left: 8rpx solid #fa8c16; }
.maint-overdue { border-left-color: #ff4d4f; }
.maint-line { display: block; color: #5a4a3a; font-size: 24rpx; margin-top: 6rpx; }
</style>

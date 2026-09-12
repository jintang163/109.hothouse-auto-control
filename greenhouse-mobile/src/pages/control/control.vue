<template>
  <view>
    <view class="card">
      <view class="muted">手动控制的指令仍经过服务端安全互锁校验（湿帘未开禁启风机、风机运行禁关湿帘）。</view>
    </view>

    <view v-for="d in actuators" :key="d.sn" class="card">
      <view class="row between">
        <view>
          <view style="font-size:32rpx;font-weight:600">
            {{ meta[d.type].icon }} {{ d.name }}
          </view>
          <view style="margin-top:8rpx">
            <text :class="['tag', d.online ? 'tag-green' : 'tag-gray']">{{ d.online ? '在线' : '离线' }}</text>
            <text :style="{marginLeft:'12rpx', color: isOn(d) ? '#52c41a' : '#999', fontWeight:600}">
              {{ d.state ? (isOn(d) ? meta[d.type].onText : meta[d.type].offText) : '未知' }}
            </text>
          </view>
        </view>
        <button size="mini"
                :class="isOn(d) ? 'btn-danger' : 'btn-primary'"
                :disabled="!d.online || sending===d.sn"
                @click="control(d)">
          {{ sending === d.sn ? '下发中…' : (isOn(d) ? '停止/关闭' : '启动/开启') }}
        </button>
      </view>
    </view>

    <view class="card">
      <view class="section-title">最近指令</view>
      <view v-for="c in commands" :key="c.commandId" class="cmd-row">
        <text>{{ c.createdAt && c.createdAt.substring(5, 16) }}</text>
        <text style="margin:0 12rpx">{{ c.deviceSn }} {{ c.action }}</text>
        <text :style="{color: cmdColor(c.status)}">{{ cmdText(c.status) }}</text>
        <text v-if="c.retryCount" class="muted" style="margin-left:8rpx">重试{{ c.retryCount }}</text>
      </view>
      <view v-if="!commands.length" class="muted">暂无指令</view>
    </view>
  </view>
</template>

<script>
import { api } from '@/common/api.js'
import { store } from '@/common/store.js'

export default {
  data() {
    return {
      devices: [],
      commands: [],
      sending: '',
      meta: {
        FAN: { icon: '🌀', on: 'ON', onText: '运行', offText: '停止' },
        WET_CURTAIN: { icon: '🚿', on: 'OPEN', onText: '开启', offText: '关闭' },
        SHADE_NET: { icon: '⛱', on: 'OPEN', onText: '展开', offText: '收拢' }
      }
    }
  },
  computed: {
    actuators() { return this.devices.filter(d => this.meta[d.type]) }
  },
  onShow() { this.load() },
  onPullDownRefresh() { this.load().finally(() => uni.stopPullDownRefresh()) },
  methods: {
    isOn(d) { return d.state === this.meta[d.type].on },
    async load() {
      const o = await api.overview(store.ghId)
      this.devices = o.devices || []
      this.commands = await api.commands(store.ghId)
    },
    control(d) {
      const turnOn = !this.isOn(d)
      const action = turnOn ? (d.type === 'FAN' ? 'ON' : 'OPEN')
                            : (d.type === 'FAN' ? 'OFF' : 'CLOSE')
      uni.showModal({
        title: `${turnOn ? '开启' : '关闭'}${this.meta[d.type].onText === '展开' ? this.meta[d.type].onText : d.name}？`,
        content: `设备 ${d.sn}，动作 ${action}`,
        confirmText: '确认下发',
        success: async (r) => {
          if (!r.confirm) return
          this.sending = d.sn
          try {
            await api.control({ deviceSn: d.sn, action, operator: store.operator })
            uni.showToast({ title: '指令已受理', icon: 'success' })
          } catch {
            // 互锁拒绝的提示已由 request 统一 toast
          } finally {
            this.sending = ''
            setTimeout(() => this.load(), 1500)
          }
        }
      })
    },
    cmdText(s) {
      return { PENDING: '待下发', SENT: '待回执', ACKED: '已回执', QUEUED_OFFLINE: '离线缓存', FAILED: '失败' }[s] || s
    },
    cmdColor(s) {
      return { ACKED: '#52c41a', SENT: '#1677ff', QUEUED_OFFLINE: '#fa8c16', FAILED: '#ff4d4f' }[s] || '#999'
    }
  }
}
</script>

<style lang="scss" scoped>
.section-title { font-weight: 600; margin-bottom: 12rpx; }
.cmd-row {
  display: flex; align-items: center; padding: 14rpx 0;
  border-bottom: 1rpx solid #f0f2f0; font-size: 26rpx;
}
.cmd-row:last-child { border-bottom: none; }
</style>

<template>
  <view>
    <!-- 汇总条 -->
    <view class="card row between summary">
      <view>
        <text style="font-weight:600">设备保养提醒</text>
        <view class="muted" style="margin-top:6rpx">按累计运行小时与保养周期实时计算</view>
      </view>
      <view class="row" style="gap:12rpx">
        <view :class="['tag', overdue.length ? 'tag-red' : 'tag-green']">{{ overdue.length }} 到期</view>
        <view :class="['tag', dueSoon.length ? 'tag-orange' : 'tag-green']">{{ dueSoon.length }} 临近</view>
      </view>
    </view>

    <!-- 待处理提醒 -->
    <view v-for="r in reminders" :key="r.deviceSn" class="card"
          :class="r.status === 'OVERDUE' ? 'card-overdue' : 'card-duesoon'">
      <view class="row between">
        <view>
          <text style="font-weight:600">{{ r.deviceName }}</text>
          <text class="muted" style="margin-left:12rpx">{{ r.deviceSn }}</text>
        </view>
        <view :class="['tag', r.status === 'OVERDUE' ? 'tag-red' : 'tag-orange']">
          {{ r.status === 'OVERDUE' ? '已到保养期' : '临近保养' }}
        </view>
      </view>
      <view class="progress-wrap">
        <view class="progress-bar"
              :style="{ width: progressPct(r) + '%',
                        background: r.status === 'OVERDUE' ? '#ff4d4f' : '#fa8c16' }"></view>
      </view>
      <view class="row between" style="margin-top:8rpx">
        <text class="muted">累计 {{ r.totalRunHours.toFixed(1) }}h / 周期 {{ r.dueAtHours }}h</text>
        <text :style="{ color: r.status === 'OVERDUE' ? '#ff4d4f' : '#fa8c16', fontWeight: 600, fontSize: '24rpx' }">
          {{ r.status === 'OVERDUE'
             ? '已超期 ' + Math.abs(r.remainingHours).toFixed(1) + 'h'
             : '剩余 ' + r.remainingHours.toFixed(1) + 'h' }}
        </text>
      </view>
      <view v-if="r.recentFaultCount30d > 0" class="fault-line">
        ⚠ 近30天故障 {{ r.recentFaultCount30d }} 次
      </view>
      <view v-if="r.suggestion" class="sugg-line">{{ r.suggestion }}</view>
      <view class="row between" style="margin-top:14rpx">
        <text class="muted">启停 {{ r.startCount }} 次 · 上次保养：{{ r.lastDoneAt ? r.lastDoneAt.substring(5, 16) : '未登记' }}</text>
        <button size="mini" class="btn-primary" @click="openForm(r)">登记保养</button>
      </view>
    </view>

    <view v-if="!reminders.length" class="card" style="text-align:center">
      <text style="font-size:48rpx">✅</text>
      <view class="muted" style="margin-top:10rpx">暂无待处理保养，设备状态良好</view>
    </view>

    <!-- 全部设备台账 -->
    <view class="card section">
      <view class="section-title">全部设备台账</view>
      <view v-for="r in ledger" :key="r.deviceSn" class="ledger-row">
        <view class="row between">
          <text>{{ r.deviceTypeName }} · {{ r.deviceName }}</text>
          <text class="muted">{{ r.totalRunHours.toFixed(1) }}h · {{ r.startCount }} 次
            <text :style="{ color: r.running ? '#52c41a' : '#999' }"> {{ r.running ? '运行中' : '已停止' }}</text>
          </text>
        </view>
      </view>
    </view>

    <!-- 登记弹窗 -->
    <view v-if="showForm" class="mask" @click.self="showForm = false">
      <view class="sheet">
        <view style="font-size:32rpx;font-weight:600;margin-bottom:20rpx">
          登记保养 - {{ current?.deviceName }}
        </view>
        <view class="muted" style="margin-bottom:16rpx">
          当前累计运行 {{ current ? current.totalRunHours.toFixed(1) : '-' }} 小时
        </view>
        <view class="field-label">保养人</view>
        <input v-model="form.operator" class="field-input" placeholder="姓名" />
        <view class="field-label">保养内容</view>
        <textarea v-model="form.note" class="field-textarea"
                  placeholder="如：加注润滑脂、检查皮带、更换轴承…" />
        <view class="row" style="gap:20rpx;margin-top:24rpx">
          <button style="flex:1" @click="showForm=false">取消</button>
          <button style="flex:1" class="btn-primary" :loading="saving" @click="submit">提交</button>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import { api } from '@/common/api.js'
import { store } from '@/common/store.js'

export default {
  data() {
    return {
      ledger: [],
      showForm: false,
      saving: false,
      current: null,
      form: { operator: store.operator, note: '' }
    }
  },
  computed: {
    reminders() {
      return this.ledger.filter(r => r.status === 'OVERDUE' || r.status === 'DUE_SOON')
    },
    overdue() { return this.ledger.filter(r => r.status === 'OVERDUE') },
    dueSoon() { return this.ledger.filter(r => r.status === 'DUE_SOON') }
  },
  onShow() { this.load() },
  onPullDownRefresh() {
    this.load().finally(() => uni.stopPullDownRefresh())
  },
  methods: {
    async load() {
      this.ledger = await api.maintenanceLedger(store.ghId)
    },
    progressPct(r) {
      return Math.max(2, Math.min(100, Math.round((r.progress || 0) * 100)))
    },
    openForm(r) {
      this.current = r
      this.form = { operator: store.operator, note: '' }
      this.showForm = true
    },
    async submit() {
      if (!this.form.operator || !this.form.operator.trim()) {
        uni.showToast({ title: '请填写保养人', icon: 'none' }); return
      }
      this.saving = true
      try {
        await api.addMaintenanceRecord({
          deviceSn: this.current.deviceSn,
          operator: this.form.operator,
          note: this.form.note
        })
        uni.showToast({ title: '保养已登记，周期重新起算', icon: 'none' })
        this.showForm = false
        this.load()
      } finally {
        this.saving = false
      }
    }
  }
}
</script>

<style lang="scss" scoped>
.summary { align-items: center; }
.card-overdue { border-left: 8rpx solid #ff4d4f; }
.card-duesoon { border-left: 8rpx solid #fa8c16; }
.progress-wrap {
  margin-top: 16rpx; height: 14rpx; background: #eef2ee; border-radius: 8rpx; overflow: hidden;
}
.progress-bar { height: 100%; border-radius: 8rpx; transition: width .3s; }
.fault-line { margin-top: 12rpx; color: #fa8c16; font-size: 24rpx; }
.sugg-line {
  margin-top: 6rpx; font-size: 24rpx; color: #cf1322;
  background: #fff2f0; border-radius: 8rpx; padding: 10rpx 14rpx;
}
.section-title { font-weight: 600; margin-bottom: 8rpx; }
.ledger-row { padding: 16rpx 0; border-bottom: 1rpx solid #f0f2f0; font-size: 26rpx; }
.ledger-row:last-child { border-bottom: none; }
.mask {
  position: fixed; inset: 0; background: rgba(0,0,0,.4);
  display: flex; align-items: flex-end; z-index: 99;
}
.sheet {
  width: 100%; background: #fff; border-radius: 24rpx 24rpx 0 0;
  padding: 32rpx; box-sizing: border-box;
}
.field-label { color: #5a6a5a; font-size: 26rpx; margin: 16rpx 0 8rpx; }
.field-input {
  border: 1rpx solid #d9e2d9; border-radius: 10rpx;
  padding: 16rpx; height: 64rpx; box-sizing: border-box;
}
.field-textarea {
  border: 1rpx solid #d9e2d9; border-radius: 10rpx;
  padding: 16rpx; width: 100%; height: 180rpx; box-sizing: border-box;
}
</style>

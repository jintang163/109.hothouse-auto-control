<template>
  <view>
    <!-- 状态筛选 -->
    <view class="card row" style="gap:16rpx;flex-wrap:wrap">
      <view v-for="f in filters" :key="f.value"
            :class="['filter-chip', filter===f.value ? 'chip-on' : '']"
            @click="filter=f.value">{{ f.label }}（{{ f.count }}）</view>
    </view>

    <view v-for="t in shown" :key="t.id" class="card task-card">
      <view class="row between">
        <view class="row" style="gap:10rpx;flex-wrap:wrap">
          <text style="font-weight:600">#{{ t.id }} {{ t.title }}</text>
        </view>
        <view :class="['tag', statusClass(t.status)]">{{ statusLabel(t.status) }}</view>
      </view>

      <view class="row" style="gap:12rpx;margin-top:10rpx;flex-wrap:wrap">
        <view class="tag tag-blue">{{ typeLabel(t.type) }}</view>
        <view class="tag tag-gray">{{ triggerLabel(t.triggerType) }}</view>
        <view class="tag tag-gray">{{ t.execMode==='DEVICE' ? '设备联动' : '人工执行' }}</view>
        <text v-if="t.batchNo" class="muted">茬次：{{ t.batchNo }}</text>
      </view>

      <view v-if="t.instruction" class="instruct">{{ t.instruction }}</view>

      <!-- 执行结果反馈 -->
      <view v-if="t.status==='DONE'" class="result-box">
        <text v-if="t.materialUsed!=null">实际用量：{{ t.materialUsed }}{{ t.materialUnit || '' }}　</text>
        <text v-if="t.durationMinutes!=null">耗时：{{ t.durationMinutes }} 分钟</text>
        <view v-if="t.feedback" class="muted" style="margin-top:6rpx">反馈：{{ t.feedback }}</view>
        <view class="muted" style="margin-top:6rpx">执行人：{{ t.operator || '-' }} · {{ t.finishedAt }}</view>
      </view>
      <view v-else-if="t.status==='FAILED'" class="result-box fail">
        <text>{{ t.feedback || '设备联动失败' }}</text>
      </view>

      <!-- 操作按钮 -->
      <view v-if="open(t)" class="row" style="gap:16rpx;margin-top:16rpx">
        <button v-if="t.deviceActionsJson" size="mini" class="btn-primary"
                :loading="runningId===t.id" @click="run(t)">⚡ 一键联动设备</button>
        <button size="mini" class="btn-primary" @click="openFeedback(t)">✍ 人工完成</button>
        <button size="mini" class="btn-danger" @click="cancel(t)">取消</button>
      </view>
      <view class="muted" style="margin-top:10rpx;font-size:22rpx">生成：{{ t.generatedAt }}</view>
    </view>

    <view v-if="!shown.length" class="card muted" style="text-align:center">暂无任务</view>

    <!-- 人工执行反馈 -->
    <view v-if="formTask" class="mask" @click.self="formTask=null">
      <view class="sheet">
        <view style="font-size:32rpx;font-weight:600;margin-bottom:16rpx">
          人工完成：#{{ formTask.id }} {{ formTask.title }}
        </view>
        <view class="field-label">执行人</view>
        <input v-model="form.operator" class="field-input" />
        <view class="row" style="gap:16rpx">
          <view style="flex:1">
            <view class="field-label">实际用量</view>
            <input v-model.number="form.materialUsed" type="digit" class="field-input" placeholder="如 200" />
          </view>
          <view style="flex:1">
            <view class="field-label">单位</view>
            <input v-model="form.materialUnit" class="field-input" placeholder="L / kg / 袋" />
          </view>
          <view style="flex:1">
            <view class="field-label">耗时(分钟)</view>
            <input v-model.number="form.durationMinutes" type="number" class="field-input" />
          </view>
        </view>
        <view class="field-label">执行情况 / 备注</view>
        <textarea v-model="form.feedback" class="field-textarea"
                  placeholder="如：滴灌 200L 均匀，土壤湿度恢复至 72%" />
        <view class="row" style="gap:20rpx;margin-top:24rpx">
          <button style="flex:1" @click="formTask=null">取消</button>
          <button style="flex:1" class="btn-primary" :loading="saving" @click="submitFeedback">确认完成</button>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import { api } from '@/common/api.js'
import { store } from '@/common/store.js'

const TYPE_LABEL = {
  ENV_TEMP: '环境调控-温度', ENV_HUMIDITY: '环境调控-湿度',
  ENV_LIGHT: '环境调控-光照', ENV_CO2: '环境调控-CO₂',
  IRRIGATION: '灌溉', FERTIGATION: '施肥', PLANT_PROTECTION: '植保',
  PRUNING: '整枝农事', HARVEST: '采收', INSPECTION_TASK: '观察巡检', OTHER: '其他'
}
const TRIGGER_LABEL = { DEVIATION: '环境偏差', PERIODIC: '周期计划', MANUAL: '人工', PEST: '病虫害识别' }

export default {
  data() {
    return {
      tasks: [],
      filter: 'OPEN',
      runningId: null,
      saving: false,
      formTask: null,
      form: { operator: store.operator, materialUsed: null, materialUnit: '', durationMinutes: null, feedback: '' },
      timer: null
    }
  },
  computed: {
    openTasks() { return this.tasks.filter(t => this.open(t)) },
    doneTasks() { return this.tasks.filter(t => t.status === 'DONE') },
    filters() {
      return [
        { value: 'OPEN', label: '待办', count: this.openTasks.length },
        { value: 'DONE', label: '已完成', count: this.doneTasks.length },
        { value: 'ALL', label: '全部', count: this.tasks.length }
      ]
    },
    shown() {
      if (this.filter === 'OPEN') return this.openTasks
      if (this.filter === 'DONE') return this.doneTasks
      return this.tasks
    }
  },
  onShow() {
    this.load()
    // 有待办时 5s 轮询一次，联动中状态可自动推进
    this.timer = setInterval(() => { if (this.openTasks.length) this.load(true) }, 5000)
  },
  onHide() { clearInterval(this.timer) },
  onUnload() { clearInterval(this.timer) },
  onPullDownRefresh() {
    this.load().finally(() => uni.stopPullDownRefresh())
  },
  methods: {
    async load(silent) {
      try {
        this.tasks = await api.farmTasks(store.ghId)
      } catch (e) { if (!silent) console.error(e) }
    },
    open(t) { return t.status === 'PENDING' || t.status === 'EXECUTING' || t.status === 'FAILED' },
    typeLabel(v) { return TYPE_LABEL[v] || v },
    triggerLabel(v) { return TRIGGER_LABEL[v] || v },
    statusLabel(s) {
      return { PENDING: '待执行', EXECUTING: '联动中', DONE: '已完成', FAILED: '联动失败', CANCELLED: '已取消' }[s]
    },
    statusClass(s) {
      return { PENDING: 'tag-orange', EXECUTING: 'tag-blue', DONE: 'tag-green', FAILED: 'tag-red', CANCELLED: 'tag-gray' }[s]
    },
    async run(t) {
      this.runningId = t.id
      uni.showLoading({ title: '指令下发中' })
      try {
        await api.runDevices(t.id, store.operator)
        uni.showToast({ title: '联动已启动', icon: 'success' })
        setTimeout(() => this.load(), 1500)
      } catch (e) { /* request 已提示 */ } finally {
        this.runningId = null
        uni.hideLoading()
      }
    },
    openFeedback(t) {
      this.formTask = t
      this.form = { operator: store.operator, materialUsed: null, materialUnit: '', durationMinutes: null, feedback: '' }
    },
    async submitFeedback() {
      if (!this.form.operator || !this.form.operator.trim()) {
        uni.showToast({ title: '请填写执行人', icon: 'none' }); return
      }
      this.saving = true
      try {
        await api.completeManual(this.formTask.id, {
          operator: this.form.operator,
          materialUsed: this.form.materialUsed === '' ? null : this.form.materialUsed,
          materialUnit: this.form.materialUnit,
          durationMinutes: this.form.durationMinutes === '' ? null : this.form.durationMinutes,
          feedback: this.form.feedback
        })
        uni.showToast({ title: '任务已闭环', icon: 'success' })
        this.formTask = null
        this.load()
      } finally { this.saving = false }
    },
    async cancel(t) {
      const res = await new Promise(r => uni.showModal({ title: '取消任务？', content: t.title, success: e => r(e.confirm) }))
      if (!res) return
      await api.cancelFarmTask(t.id, store.operator)
      uni.showToast({ title: '已取消', icon: 'none' })
      this.load()
    }
  }
}
</script>

<style lang="scss" scoped>
.filter-chip {
  padding: 8rpx 24rpx; border-radius: 30rpx; background: #eef3ee;
  color: #5a6a5a; font-size: 24rpx;
}
.chip-on { background: #2e7d32; color: #fff; }
.task-card { border-left: 8rpx solid #2e7d32; }
.instruct {
  margin-top: 12rpx; background: #f6f9f6; border-radius: 10rpx;
  padding: 14rpx; font-size: 25rpx; color: #445;
}
.result-box {
  margin-top: 12rpx; padding: 14rpx; border-radius: 10rpx;
  background: #f0fbe8; font-size: 25rpx;
}
.result-box.fail { background: #fff1f0; color: #cf1322; }
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
  padding: 16rpx; width: 100%; height: 160rpx; box-sizing: border-box;
}
</style>

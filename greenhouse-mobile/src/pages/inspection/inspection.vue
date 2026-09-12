<template>
  <view>
    <view class="card row between">
      <text class="muted">共 {{ records.length }} 条巡检记录</text>
      <button size="mini" class="btn-primary" @click="showForm = true">+ 新增巡检</button>
    </view>

    <view v-for="r in records" :key="r.id" class="card">
      <view class="row between">
        <text style="font-weight:600">{{ r.inspector }}</text>
        <text :class="['tag', r.result==='异常' ? 'tag-red' : 'tag-green']">{{ r.result }}</text>
      </view>
      <view style="margin:14rpx 0">{{ r.content }}</view>
      <text class="muted">{{ r.createdAt }}</text>
    </view>
    <view v-if="!records.length" class="card muted" style="text-align:center">暂无巡检记录</view>

    <!-- 新增巡检 -->
    <view v-if="showForm" class="mask" @click.self="showForm=false">
      <view class="sheet">
        <view style="font-size:32rpx;font-weight:600;margin-bottom:20rpx">新增巡检记录</view>
        <view class="field-label">巡检人</view>
        <input v-model="form.inspector" class="field-input" placeholder="姓名" />
        <view class="field-label">结论</view>
        <radio-group v-model="form.result" class="row" style="gap:30rpx;margin-bottom:12rpx">
          <label><radio value="正常" checked="true" color="#2e7d32" /> 正常</label>
          <label><radio value="异常" color="#ff4d4f" /> 异常</label>
        </radio-group>
        <view class="field-label">巡检内容 / 发现问题</view>
        <textarea v-model="form.content" class="field-textarea"
                  placeholder="如：风机皮带无松动，湿帘水位正常…" />
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
      records: [],
      showForm: false,
      saving: false,
      form: { inspector: store.operator, result: '正常', content: '' }
    }
  },
  onShow() { this.load() },
  onPullDownRefresh() {
    this.load().finally(() => uni.stopPullDownRefresh())
  },
  methods: {
    async load() {
      this.records = await api.inspections(store.ghId)
    },
    async submit() {
      if (!this.form.inspector.trim()) {
        uni.showToast({ title: '请填写巡检人', icon: 'none' }); return
      }
      if (!this.form.content.trim()) {
        uni.showToast({ title: '请填写巡检内容', icon: 'none' }); return
      }
      this.saving = true
      try {
        await api.addInspection({ greenhouseId: store.ghId, ...this.form })
        uni.showToast({ title: '已提交', icon: 'success' })
        this.showForm = false
        this.form.content = ''
        this.load()
      } finally {
        this.saving = false
      }
    }
  }
}
</script>

<style lang="scss" scoped>
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

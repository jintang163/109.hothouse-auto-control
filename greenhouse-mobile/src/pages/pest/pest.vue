<template>
  <view>
    <!-- 顶部切换 -->
    <view class="card row" style="gap:16rpx">
      <view :class="['tab', tab==='diagnose' ? 'tab-on' : '']" @click="tab='diagnose'">📷 拍照识别</view>
      <view :class="['tab', tab==='lib' ? 'tab-on' : '']" @click="tab='lib'">📚 知识库</view>
    </view>

    <!-- ============ 拍照识别 ============ -->
    <view v-if="tab==='diagnose'">
      <view class="card">
        <view class="section-title">现场照片</view>
        <view class="photo-box" @click="choosePhoto">
          <image v-if="photo" :src="photo" mode="aspectFill" class="photo-img" />
          <view v-else class="photo-placeholder">
            <text style="font-size:60rpx">📸</text>
            <text class="muted">点击拍照 / 相册选图</text>
          </view>
        </view>
        <view v-if="photo" class="row" style="margin-top:12rpx;gap:20rpx">
          <button size="mini" @click="choosePhoto">重拍</button>
          <button size="mini" class="btn-primary" @click="photo=''">移除</button>
        </view>

        <view class="section-title" style="margin-top:20rpx">
          勾选肉眼可见特征（选得越准，识别越可靠）
        </view>
        <view class="feat-wrap">
          <view v-for="f in features" :key="f"
                :class="['feat-chip', selected.includes(f) ? 'feat-on' : '']"
                @click="toggleFeature(f)">{{ f }}</view>
          <view v-if="!features.length" class="muted">特征加载中…</view>
        </view>

        <button class="btn-primary" style="margin-top:24rpx" :loading="loading"
                :disabled="!selected.length" @click="diagnose">开始识别</button>
        <view v-if="!selected.length" class="muted" style="margin-top:8rpx;font-size:22rpx">
          本地演示无图像模型，需勾选特征进行匹配（照片同步留档）
        </view>
      </view>

      <!-- 识别结果 -->
      <view v-if="result" class="card result-card">
        <view class="row between">
          <text style="font-size:32rpx;font-weight:600">🎯 {{ result.matchedName }}</text>
          <view :class="['tag', (result.confidence||0) >= 0.6 ? 'tag-green' : 'tag-orange']">
            置信度 {{ Math.round((result.confidence || 0) * 100) }}%
          </view>
        </view>

        <view v-if="candidates.length > 1" class="muted" style="margin-top:10rpx">
          其他可能：
          <text v-for="c in candidates.slice(1,4)" :key="c.id">
            {{ c.name }} {{ Math.round(c.confidence*100) }}%；
          </text>
        </view>

        <view class="advice-box">{{ result.advice }}</view>

        <view class="row" style="gap:16rpx;margin-top:20rpx">
          <button class="btn-primary" style="flex:1" :disabled="result.taskCreated" @click="createTask">
            {{ result.taskCreated ? '防治任务已生成' : '🧴 一键生成防治任务' }}
          </button>
          <button style="flex:1" @click="goTasks">去任务页</button>
        </view>
      </view>
    </view>

    <!-- ============ 知识库 ============ -->
    <view v-if="tab==='lib'">
      <view class="card">
        <input class="field-input" v-model="keyword" placeholder="搜索名称 / 作物，如 番茄、粉虱" />
      </view>
      <view v-for="k in filteredLib" :key="k.id" class="card lib-card">
        <view class="row between">
          <text style="font-weight:600;font-size:30rpx">{{ k.name }}</text>
          <view :class="['tag', k.category==='虫害' ? 'tag-orange' : 'tag-red']">{{ k.category }}</view>
        </view>
        <view class="muted" style="margin-top:6rpx">危害：{{ k.crops }}</view>
        <view style="margin-top:10rpx">
          <text v-for="f in parseFeatures(k.featuresJson)" :key="f" class="feat-chip feat-on" style="margin-right:8rpx">{{ f }}</text>
        </view>
        <view class="lib-section">【典型症状】{{ k.symptoms }}</view>
        <view class="lib-section">【发生条件】{{ k.cause }}</view>
        <view class="lib-section">【防治措施】{{ k.treatment }}</view>
        <view class="lib-section">【推荐用药】{{ k.pesticide }}</view>
      </view>
      <view v-if="!filteredLib.length" class="card muted" style="text-align:center">无匹配条目</view>
    </view>
  </view>
</template>

<script>
import { api } from '@/common/api.js'
import { store } from '@/common/store.js'

export default {
  data() {
    return {
      tab: 'diagnose',
      features: [],
      selected: [],
      photo: '',
      photoBase64: '',
      loading: false,
      result: null,
      lib: [],
      keyword: ''
    }
  },
  computed: {
    candidates() {
      try { return JSON.parse(this.result?.candidatesJson || '[]') } catch { return [] }
    },
    filteredLib() {
      const kw = this.keyword.trim()
      if (!kw) return this.lib
      return this.lib.filter(k => (k.name || '').includes(kw) || (k.crops || '').includes(kw))
    }
  },
  onShow() {
    this.loadMeta()
    this.loadLib()
  },
  onPullDownRefresh() {
    Promise.all([this.loadMeta(), this.loadLib()]).finally(() => uni.stopPullDownRefresh())
  },
  methods: {
    async loadMeta() {
      try { this.features = await api.pestFeatures() } catch (e) {}
    },
    async loadLib() {
      try { this.lib = await api.pestKnowledge() } catch (e) {}
    },
    parseFeatures(s) {
      try { return JSON.parse(s || '[]') } catch { return [] }
    },
    toggleFeature(f) {
      const i = this.selected.indexOf(f)
      if (i >= 0) this.selected.splice(i, 1)
      else this.selected.push(f)
    },
    choosePhoto() {
      uni.chooseImage({
        count: 1,
        sizeType: ['compressed'],
        sourceType: ['camera', 'album'],
        success: (res) => {
          const path = res.tempFilePaths[0]
          this.photo = path
          this.compressAndRead(path)
        }
      })
    },
    // 压缩到约 720px 并转 data URI 留档（#ifdef H5 FileReader / App+ 用 canvas）
    compressAndRead(path) {
      // #ifdef H5
      fetch(path).then(r => r.blob()).then(blob => {
        const img = new Image()
        img.onload = () => {
          const scale = Math.min(1, 720 / Math.max(img.width, img.height))
          const c = document.createElement('canvas')
          c.width = Math.round(img.width * scale)
          c.height = Math.round(img.height * scale)
          c.getContext('2d').drawImage(img, 0, 0, c.width, c.height)
          this.photoBase64 = c.toDataURL('image/jpeg', 0.7)
        }
        img.src = URL.createObjectURL(blob)
      }).catch(() => {})
      // #endif
      // #ifndef H5
      uni.getImageInfo({
        src: path,
        success: (info) => {
          // App/小程序：用 canvas 压缩后 canvasToTempFilePath 再 readFile 转 base64
          // 真机建议走 uni.compressImage；此处直接读原图并由后端截断（演示数据量可接受）
          uni.getFileSystemManager().readFile({
            filePath: info.path,
            encoding: 'base64',
            success: (r) => { this.photoBase64 = 'data:image/jpeg;base64,' + r.data },
            fail: () => { this.photoBase64 = '' }
          })
        },
        fail: () => { this.photoBase64 = '' }
      })
      // #endif
    },
    async diagnose() {
      this.loading = true
      try {
        this.result = await api.pestDiagnose({
          greenhouseId: store.ghId,
          operator: store.operator,
          photoBase64: this.photoBase64 || null,
          features: this.selected
        })
        uni.showToast({ title: '识别完成', icon: 'success' })
      } finally { this.loading = false }
    },
    async createTask() {
      const t = await api.pestCreateTask(this.result.id, store.operator)
      this.result.taskCreated = true
      this.result.taskId = t.id
      uni.showModal({
        title: '防治任务已生成',
        content: `#${t.id} ${t.title}，请在「农事」页执行并回填用药量与耗时`,
        showCancel: true,
        confirmText: '去执行',
        success: (e) => { if (e.confirm) this.goTasks() }
      })
    },
    goTasks() {
      uni.navigateTo({ url: '/pages/task/task' })
    }
  }
}
</script>

<style lang="scss" scoped>
.tab {
  flex: 1; text-align: center; padding: 14rpx 0; border-radius: 12rpx;
  background: #eef3ee; color: #5a6a5a; font-size: 27rpx;
}
.tab-on { background: #2e7d32; color: #fff; font-weight: 600; }
.photo-box {
  margin-top: 14rpx; height: 360rpx; border: 2rpx dashed #b8c8b8;
  border-radius: 14rpx; overflow: hidden;
  display: flex; align-items: center; justify-content: center;
}
.photo-img { width: 100%; height: 100%; }
.photo-placeholder { display: flex; flex-direction: column; gap: 14rpx; align-items: center; }
.section-title { font-weight: 600; font-size: 27rpx; margin-bottom: 12rpx; }
.feat-wrap { display: flex; flex-wrap: wrap; gap: 14rpx; }
.feat-chip {
  padding: 10rpx 22rpx; border-radius: 30rpx; background: #f1f4f1;
  border: 1rpx solid #dde6dd; font-size: 24rpx; color: #435043;
}
.feat-on { background: #e6f4e0; border-color: #2e7d32; color: #2e7d32; font-weight: 600; }
.result-card { border-left: 8rpx solid #fa8c16; }
.advice-box {
  margin-top: 16rpx; background: #fffbe6; border-radius: 10rpx;
  padding: 18rpx; font-size: 25rpx; white-space: pre-wrap; line-height: 1.6;
}
.lib-card .lib-section {
  margin-top: 12rpx; font-size: 25rpx; color: #334033; line-height: 1.55;
}
.field-input {
  border: 1rpx solid #d9e2d9; border-radius: 10rpx;
  padding: 16rpx; height: 64rpx; box-sizing: border-box;
}
</style>

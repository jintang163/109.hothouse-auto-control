<script>
export default {
  onLaunch() {
    console.log('智慧温室移动端启动')
  },
  onShow() {
    // 应用回到前台：广播全局事件，页面据此重连 WS 并补拉数据（后台/离线恢复入口）
    uni.$emit('app-foreground')
  },
  onHide() {
    uni.$emit('app-background')
  }
}
</script>

<style lang="scss">
page {
  background: #f4f6f4;
  font-size: 28rpx;
  color: #1f2d1f;
}
.card {
  background: #fff;
  border-radius: 16rpx;
  padding: 24rpx;
  margin: 20rpx;
  box-shadow: 0 2rpx 12rpx rgba(46, 125, 50, 0.06);
}
.row { display: flex; align-items: center; }
.between { justify-content: space-between; }
.muted { color: #90a090; font-size: 24rpx; }
.tag {
  display: inline-block;
  padding: 2rpx 16rpx;
  border-radius: 20rpx;
  font-size: 22rpx;
  color: #fff;
}
.tag-green { background: #52c41a; }
.tag-gray { background: #bfbfbf; }
.tag-red { background: #ff4d4f; }
.tag-orange { background: #fa8c16; }
.tag-blue { background: #1677ff; }
.btn-primary {
  background: #2e7d32;
  color: #fff;
  border-radius: 12rpx;
}
.btn-danger {
  background: #ff4d4f;
  color: #fff;
  border-radius: 12rpx;
}
button[disabled] { opacity: .5; }
</style>

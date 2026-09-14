# 温室大棚智能环控系统

面向设施农业的「**感知采集 → 规则调控 → 设备联动 → 告警追溯**」闭环系统：
棚内温湿度/光照/CO₂ 传感器经网关上报，平台依据阈值与作物策略自动联动
**风机、湿帘（水泵）、遮阳网**，完成降温、增湿、遮阳；支持手动 / 自动 / 定时三种模式与异常告警。
在此之上提供「**农事处方 → 任务生成 → 设备联动/人工反馈 → 农事日志 → 产量优化**」生产闭环，
并在移动端提供**病虫害拍照识别**与内置知识库。

首版交付：**Netty 接入 + 三设备联动 + AntD 管理端监控 + UniApp 移动端控阀 + 设备模拟器**，全链路可本地一键演示。

---

## 一、系统架构

```
┌──────────────┐   TCP 长连接(4字节长度前缀+JSON)  ┌──────────────────────────────────────────┐
│ 现场网关/PLC  │ ◄──────────────────────────────►│  Netty IoT 接入层 (:8600)                 │
│  传感器/执行器 │   REGISTER/HEARTBEAT/DATA/ACK   │  注册 · 心跳 · 上报 · 指令下发 · 回执      │
└──────────────┘                                 └───────────────┬──────────────────────────┘
                                                                 │ (进程内调用)
┌─────────────────────┐  REST /api/**   ┌───────────────────────▼──────────────────────────┐
│ AntD React 管理端    │ ◄────────────►  │  Spring Boot 业务后端 (:8080)                     │
│ 大屏/曲线/策略/告警   │   WS /ws/realtime│  设备/大棚/策略 · 规则引擎 · 指令服务(重试/补发)   │
└─────────────────────┘                  │  告警 · 定时调度 · 操作日志 · H2(JPA)             │
┌─────────────────────┐  REST /api/**    └───────────────▲───────────────────────────────────┘
│ UniApp 移动端(H5/App)│ ◄────────────┘                    │ WebSocket 实时推送
│ 监控/控阀/告警/巡检   │      WS /ws/realtime              │ sensor/device/alarm/command
└─────────────────────┘
       ▲
┌──────┴──────┐
│ 设备模拟器    │  Python，无第三方依赖，模拟 GW-001 上报数据并执行指令回 ACK
└─────────────┘
```

| 模块 | 目录 | 技术栈 | 端口 |
|---|---|---|---|
| IoT 接入 + 业务后端 | `greenhouse-server/` | Netty 4.1、Spring Boot 3、Spring Data JPA、WebSocket、H2 | 8080 / 8600 |
| 管理端 | `greenhouse-admin/` | React 18、Vite 5、Ant Design 5 | 5173 |
| 移动端 | `greenhouse-mobile/` | UniApp（Vue3 + Vite），一套代码出 H5/App/小程序 | 5174 |
| 设备模拟器 | `simulator/` | Python 3 标准库 | — |

## 二、自动调控原理（采集—判定—执行）

每个大棚一份作物策略（`gh_strategy`），按大棚分区隔离：

1. **采集**：网关周期上报 `DATA` → 落库 `gh_sensor_record` → 刷新最新值缓存 → WS 推送大屏 → 触发规则引擎。
2. **判定（规则引擎 `RuleEngineService`）**
   - **阈值 + 回差（迟滞）**：如温度 ≥ 32℃ 启动降温，≤ 28℃ 才停止，避免边界抖动；
   - **防抖**：阈值条件需持续 `debounceSec`（默认 10s）才成立；
   - **冷却**：同一执行器两次动作间隔不少于 `cooldownSec`（默认 60s），防频繁启停；
   - **功能状态机**：降温（湿帘+风机）/ 加湿（湿帘）/ 遮阳（遮阳网）独立判定。
3. **执行（联动链 `ControlService`）**
   - 动作**按序执行、逐环回执**：开机 `湿帘 OPEN → 风机 ON`，停机 `风机 OFF → 湿帘 CLOSE`；
   - **安全互锁**：湿帘未开成功则风机不会启动；风机运行中禁止关湿帘；手动/自动/定时所有来源的指令在下发前统一过互锁校验，被拦截即产生 WARN 告警并中止联动链；
   - **指令回执**：5s 未回执自动重试（默认 3 次），耗尽转 `FAILED` 并产生 CRITICAL 告警、中止链上后续动作；
   - **离线缓存**：网关离线时指令置 `QUEUED_OFFLINE`，网关注册重连后自动补发（来源标记 `OFFLINE_RETRY`）。
4. **告警追溯**：阈值越限 / 设备离线 / 指令失败 / 互锁拦截四类告警，同类未处理告警去重；所有动作写操作日志，指令全生命周期可查，移动端可上报巡检记录。
5. **设备运维（只读统计）**：以 ACKED 指令配对启停段，每日 00:07 全量幂等重算各执行器累计运行时长/启停次数（运行中尾段实时叠加）；按设备类型配置保养周期（默认风机/湿帘水泵 500h、遮阳网电机 200h），临近/到期通过大屏预警条、WS 推送与移动端提醒（系统级通知优先、应用内提醒兜底，详见移动端一节）；故障看板以 FAILED 指令为准（不受告警去重影响），**故障事件时间统一按回执时间口径**（`ackedAt → sentAt → createdAt` 回退，与运行台账 ACKED 配对同一时间源），跨日延迟回执计入回执发生日；按电机过载/通讯超时分类，支持按类型、大棚聚合与 30 天趋势，单设备近 30 天故障 ≥3 次自动给出检修/更换备件建议。

## 三、农事处方与生产闭环（处方 → 任务 → 日志 → 产量）

在环控闭环之上新增「**农事处方**」模块：按**作物品种 + 生育期**配置目标环境与水肥农事操作，
定时对比传感器数据自动生成农事任务，支持**一键 Netty 设备联动**或**人工执行反馈**，
全部任务自动形成闭环日志并按**茬次关联产量、品质**分析，反哺处方优化；
移动端另提供**病虫害拍照识别**与内置知识库。

```
品种+生育期处方(目标区间/水肥/操作)
        │  TaskGenerateService 每60s扫描
        ├─ 环境偏差（传感器 vs 区间±死区，超阈值）──┐
        ├─ 周期触发（intervalDays/定时刻）──────────┤
        ├─ 人工创建 ────────────────────────────────┤
        └─ 病虫害识别推送 ──────────────────────────┘
                                                  ▼
                         FarmTask（PENDING）
              ┌────────────────────┴───────────────────┐
       DEVICE 一键联动                            MANUAL 人工完成
       ControlService 联动链                      回填用量/耗时/反馈
       Netty→设备→ACK（互锁/重试/补发）                │
              └────────────────────┬───────────────────┘
                                   ▼
                       DONE 闭环日志（按茬次快照）
                                   ▼
               YieldRecord 产量/品质 → 批次分析 → 处方优化线索
```

### 1. 处方库（`gh_prescription`，管理端「农事处方」页）

- 按 **品种 + 生育期** 配置两份结构化内容（页面表单直接增删行，无需手写 JSON）：
  - **环境目标区间** `envTargetsJson`：`[{metric:temperature,low,high,tolerance,unit}]`，
    覆盖 温度/湿度/光照/CO₂；`tolerance` 为死区，越过「区间 ± 死区」才判越界；
  - **水肥 / 农事操作** `operationsJson`：灌溉、施肥、植保、整枝、采收等，
    可配 `intervalDays` 周期、触发时刻 `time`、用量、预估耗时，以及设备联动
    （设备类型、动作、保持时长 `holdMinutes`，到期自动反向动作，如开阀 10 分钟自动关）。
- **复制**：任意处方一键「复制新版」生成同品种+生育期的新草稿，版本号自动递增、记录复制来源；
- **版本控制**：`DRAFT 草稿（可编辑）→ PUBLISHED 已发布（只读）→ ARCHIVED 归档`；
  发布新版本时同品种+生育期旧发布版**自动归档**，同一组合始终只有一个生效版本；
  已发布版拒绝直接修改（接口返回错误提示「请复制为新版本」）。
- 大棚（`gh_greenhouse`）新增 `variety / growthStage / currentBatchNo` 字段（页面顶部「种植档案」可维护），任务生成器据此匹配处方、快照茬次。

### 2. 任务生成（`TaskGenerateService`，每 60s 扫描）

- **偏差触发**：取大棚品种+生育期的已发布处方，环境目标区间与传感器最新值（复用 `SensorDataService` 缓存）逐条对比；
  越界即生成 `ENV_*` 任务，并按偏差类型**建议联动动作**（高温→湿帘+风机、低湿→湿帘加湿、强光→遮阳网展开、弱光→收拢遮阳网；无对应执行器的增温/降湿/CO₂ 类转人工任务并给出处置建议）。
- **周期触发**：处方操作按 `intervalDays` 到期（可指定当日 `HH:mm`）生成设备联动或人工任务。
- **防重复**：开放中的同方向任务去重（`dedupeKey`）；任务完成后 30 分钟冷却，避免抖动反复生成；周期项当日只触发一次。

### 3. 任务执行（`FarmTaskService`，管理端「农事任务」页 / 移动端「农事任务」页）

- **一键设备联动**：任务上的设备动作经 `ControlService.executeLinked` 走联动链——**复用 Netty 下发、逐环 ACK、安全互锁、超时重试、离线补发全部既有机制**，指令来源标记 `FARM_TASK`；
  全链回执成功任务自动 `DONE`，失败转 `FAILED` 并提示改人工；`holdMinutes` 到期自动下发反向动作。
- **人工执行反馈**：线下完成后回填**实际用量（数值+单位）、耗时（分钟）、执行情况**，任务闭环；
  设备联动进行中人工终结记为 `DEVICE_THEN_MANUAL`。
- 每次生成/执行/取消均写操作日志，并通过 WS（事件 `farmtask`）实时刷新看板。

### 4. 农事日志与产量关联（`gh_yield_record`，管理端「农事日志」页）

- 任务生成时快照茬次 `batchNo`；采收后按茬次录入**产量、优质果率、糖度（°Brix）**等品质数据；
- **批次分析**：聚合该茬总产量/均产、品质均值、任务完成率/失败数/人工总耗时及任务类型分布，
  并规则式给出**处方优化线索**（如偏差任务过多→校准目标区间；糖度偏低→结果期控水控氮增施磷钾肥；设备联动失败→结合运维看板检修）。

### 5. 病虫害识别（移动端「病虫害识别」页 + `gh_pest_knowledge` / `gh_pest_diagnosis`）

- **内置知识库**：早疫病/晚疫病/灰霉病/烟粉虱等条目，含**鉴别特征、典型症状图谱（文字）、发生条件、防治措施、推荐用药与安全间隔期**；管理端「病虫害库」页可增删改。
- **拍照识别**：`uni.chooseImage` 调起相机/相册，图片压缩转 base64 随记录留档；
  本地演示无云端图像模型，由用户**勾选肉眼可见特征**，服务端按「命中特征数 / 条目特征总数」加权匹配，给出**置信度与候选列表**（对接图像识别 API 时仅需替换 `PestService.diagnose` 内部实现）。
- **处置建议推送**：识别结果展示防治措施与推荐用药，可**一键生成植保任务**（幂等），跳转到任务页执行并回填用药量、耗时，形成「识别 → 处置 → 反馈」闭环。

## 四、IoT 通信协议

TCP，UTF-8 JSON，**每帧 = 4 字节大端无符号长度 + JSON 报文体**。

| 方向 | type | 关键字段 |
|---|---|---|
| 上行 | `REGISTER` | `sn`（网关 SN，与大棚 `gatewaySn` 对应） |
| 上行 | `HEARTBEAT` | `sn`、`ts`（90s 无报文判定离线） |
| 上行 | `DATA` | `sn`、`data:{temperature,humidity,light,co2}` |
| 上行 | `STATE` | `sn`、`states:{设备SN:状态}`（执行器状态主动上报） |
| 上行 | `ACK` | `commandId`、`success`、`state`、`error` |
| 下行 | `CONTROL` | `commandId`、`deviceSn`、`action`(ON/OFF/OPEN/CLOSE)、`params` |

## 五、快速开始

### 1. 启动后端（需 JDK 17+、Maven 3.8+）

```bash
cd greenhouse-server
mvn spring-boot:run
# 或 mvn package -DskipTests && java -jar target/greenhouse-server-1.0.0.jar
```

启动后：REST `http://localhost:8080`，Netty `:8600`，H2 控制台 `/h2-console`。
首次启动自动建库并写入演示数据：**1号番茄大棚（网关 GW-001）+ 传感器/风机/湿帘/遮阳网 + 番茄结果期策略**。

### 2. 启动设备模拟器（Python 3，无依赖）

```bash
python3 simulator/simulator.py --start-temp 36 --interval 3 --noisy
```

参数：`--fail-rate 0.5`（模拟指令失败看重试/告警/联动中止）、`--disconnect 60`（周期断线看重连补发）。
模拟器内置简化物理模型：遮阳→削光照与得热，湿帘→降温增湿，湿帘+风机→蒸发降温协同。

### 3. 启动管理端（Node 18+）

```bash
cd greenhouse-admin
npm install
npm run dev        # http://localhost:5173 （已配置 /api、/ws 代理到 8080）
```

页面：实时监控大屏（模式切换、手动控阀、实时指标/设备/策略/互锁/保养提醒）、历史曲线、
环控策略（阈值/回差/防抖/冷却/定时计划）、**农事处方（品种+生育期目标/水肥操作、复制与版本发布）**、
**农事任务（偏差/周期/病虫害任务看板、一键联动、人工反馈）**、**农事日志（产量品质录入与批次分析）**、
**病虫害库（图谱防治维护、识别记录）**、告警中心、
设备运维（运行台账/保养周期/故障看板柱状图+趋势线/备件建议）、操作追溯（日志/指令/巡检）。

### 4. 启动移动端（UniApp）

```bash
cd greenhouse-mobile
npm install
npm run dev:h5     # http://localhost:5174
```

底部 4 个 Tab：监控（WS 实时，首页含保养提醒卡片与「农事生产」入口）、控阀（手动控制 + 最近指令）、告警（处理闭环）、巡检（新增记录）。
点击保养提醒卡片进入「保养提醒」页：查看全部设备台账与到期/临近设备，可直接登记保养，周期随即重新起算。
首页「农事生产」区可进入 **农事任务**（待办/已完成筛选、一键联动设备、人工回填用量耗时）与
**病虫害识别**（拍照留档、勾选特征识别、处置建议、一键生成防治任务，另含知识库浏览）。

**保养提醒的通知方式**（`src/common/notify.js` 适配层，系统级优先、应用内兜底）：

| 平台 | 系统级通知 | 未授权/不支持时 |
|---|---|---|
| H5 | 浏览器 `Notification`（标签页在后台也可弹出，需在「保养提醒」页点「开启系统通知」授权） | 应用内 toast |
| App | `plus.push` 本地系统通知（落系统通知栏；Android 13+ 需在系统设置允许通知） | 应用内 toast |
| 小程序 | 无系统通知能力 | 应用内 toast |

- 新出现或升级（临近→到期）的提醒才通知，状态持久化去重，重连/补拉不重复打扰；登记保养后状态清除，下一周期可再通知。
- **后台/离线**：H5 后台标签页 WS 不断，系统通知照常弹出；App 切后台 WS 被系统挂起，回到前台时（App.vue `onShow` 广播 `app-foreground`）自动重连 WS 并补拉提醒，错过的变化按 diff 补发通知；杀进程后的离线触达需 UniPush 厂商通道（见「后续可扩展」）。
- 自检：`cd greenhouse-mobile && npm test`（提醒 diff/去重/补拉 10 个用例，node 自带 runner，无额外依赖）。

出 App / 各家小程序：用 **HBuilderX 导入本目录**发行；真机运行前把
`src/common/api.js`（`#ifndef H5` 分支）与监控页 WS 地址中的 `localhost:8080`
改为实际服务地址（H5 开发态走 vite 代理，无需修改）。

### 五分钟演示剧本

1. 后端 + 模拟器（`--start-temp 36`）+ 管理端；
2. 大屏观察：温度 ≥32℃ 持续 10s → **湿帘先开、风机后开**（执行器卡片实时变绿）→ 温降湿升；
3. 温度回落到 28℃ 且冷却 60s 后 → **风机先停、湿帘后关**；
4. 大屏模式切「手动」，直接点风机「启动」（湿帘关着）→ 被互锁拒绝并产生 WARN 告警，去告警中心处理；
5. 模拟器加 `--fail-rate 0.5` 重连 → 指令重试/失败告警、联动链中止，在「操作追溯」查看指令全生命周期；
6. 模拟器加 `--disconnect 60` → 大屏网关变红、离线告警；手动下一条指令显示「离线缓存」，重连后自动补发；
7. 移动端「控阀」点设备、「巡检」新增记录，管理端「操作追溯」可见。

### 实时推送自检（sensor / device / alarm / command）

```bash
python3 simulator/ws_capture.py
```

被动监听只能等到 sensor/device/command —— alarm 仅在告警条件发生时产生。
该脚本会主动触发一次「湿帘未开强启风机」的互锁拦截告警，并统计捕获到的事件类型：
四类齐全退出码为 0；缺 sensor 说明模拟器未运行，缺 device/command 说明网关离线。
自检期间大棚会被临时切为手动模式，结束后自动恢复。

### 保养提醒推送链路自检（reminders / fault-stats / maintenance 帧）

```bash
python3 simulator/maintenance_notify_check.py
```

验证移动端通知依赖的服务端通道：提醒接口、故障统计接口（回执时间口径）可用，
且触发 `run-now` 幂等重算后 WS 能收到 `maintenance` 帧（移动端收到该帧即刷新提醒并通知）。
三项齐全退出码为 0。

### 移动端后台/离线场景手工验证

1. **H5 后台**：`npm run dev:h5` 打开监控页，「保养提醒」页授权系统通知后切到别的标签页；
   触发保养状态变化（如 `run-now` 重算后设备进入临近），浏览器系统通知照常弹出。
2. **H5 离线**：DevTools → Network → Offline，页面显示「实时重连中」；恢复网络后 WS 自动重连、
   补拉提醒，离线期间错过的变化补发通知且不重复（`npm test` 覆盖该 diff 逻辑）。
3. **App 后台**：HBuilderX 真机运行，切后台再回前台 → 自动重连 WS 并补拉提醒；
   杀进程后无本地通知能力（需 UniPush 厂商通道，属后续扩展）。

## 六、REST API（统一返回 `{code,message,data}`，code=0 成功）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/greenhouses` | 大棚（分区）列表 |
| GET | `/api/greenhouses/{id}/overview` | 大屏总览：实时值+设备+策略+本棚未处理告警数 |
| PUT | `/api/greenhouses/{id}/mode?mode=AUTO|MANUAL|SCHEDULE` | 切换运行模式 |
| GET | `/api/devices?greenhouseId=` | 设备列表与状态 |
| POST | `/api/control` | 手动控制（body: deviceSn/action/operator，内含互锁校验） |
| GET/PUT | `/api/strategies/{greenhouseId}` | 查询/保存策略（含定时计划 scheduleJson） |
| GET | `/api/sensor/latest` `/api/sensor/history?metric=&from=&to=` | 最新值/历史曲线 |
| GET/POST | `/api/alarms` `/api/alarms/{id}/handle` | 告警查询/处理 |
| GET/POST | `/api/maintenance/records` | 保养登记历史/新增登记（登记后周期重新起算） |
| GET | `/api/maintenance/ledger` `/reminders` | 设备运行台账（累计时长/启停次数+保养进度）/待办提醒 |
| GET/PUT | `/api/maintenance/rules` | 按设备类型查询/保存保养周期（如风机 500h） |
| GET | `/api/maintenance/fault-stats?days=30` | 近 N 天故障按类型/大棚聚合、每日趋势、备件建议（按 FAILED 指令回执时间归集） |
| POST | `/api/maintenance/run-now` | 手动触发台账全量重算（每日 00:07 自动执行，幂等） |
| GET | `/api/logs` `/api/commands` | 操作日志 / 指令全生命周期 |
| GET/POST | `/api/inspections` | 巡检记录 |
| PUT | `/api/greenhouses/{id}/crop-profile` | 维护品种/生育期/当前茬次（处方匹配键） |
| GET/POST | `/api/prescriptions` | 处方列表 / 新建草稿（版本号自动） |
| GET/PUT | `/api/prescriptions/{id}` | 详情 / 编辑（仅草稿可改） |
| POST | `/api/prescriptions/{id}/copy` `/publish` `/archive` | 复制新版本 / 发布（旧版自动归档）/ 归档 |
| GET | `/api/prescriptions/versions?variety=&growthStage=` | 某品种+生育期版本历史 |
| GET | `/api/farm-tasks?greenhouseId=` | 农事任务列表 |
| POST | `/api/farm-tasks` | 人工创建任务 |
| POST | `/api/farm-tasks/{id}/run-devices` | 一键 Netty 联动设备（复用互锁/重试/补发） |
| POST | `/api/farm-tasks/{id}/complete-manual` | 人工完成并回填用量/耗时/反馈 |
| POST | `/api/farm-tasks/{id}/cancel` | 取消任务 |
| GET/POST | `/api/yields` | 产量/品质记录查询 / 录入 |
| GET | `/api/yields/analysis?greenhouseId=` | 按茬次聚合产量品质与任务闭环、优化线索 |
| GET/POST/PUT/DELETE | `/api/pest/knowledge[/{id}]` | 病虫害知识库 CRUD |
| GET | `/api/pest/features` | 全部鉴别特征词（移动端勾选清单） |
| POST | `/api/pest/diagnose` | 拍照/特征识别（body: greenhouseId/photoBase64/features） |
| GET | `/api/pest/diagnoses?greenhouseId=` | 识别记录 |
| POST | `/api/pest/diagnoses/{id}/create-task` | 一键生成植保任务（幂等） |
| WS | `/ws/realtime` | 推送帧 `{event: sensor|device|alarm|command|maintenance|farmtask, data}` |

## 七、后续可扩展

分区多网关路由、Modbus/MQTT 多协议适配、策略可视化编排、用户权限与多租户、
InfluxDB/TDengine 时序存储、告警短信/微信通道、UniPush 厂商推送（杀进程离线触达）、
指令端到端签名与固件 OTA、病虫害云端图像识别模型接入（替换 `PestService.diagnose` 特征匹配实现）、
处方模板库导入导出与跨棚复制、产量-处方多茬统计建模。

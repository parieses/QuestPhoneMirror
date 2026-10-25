# QPM 手机镜像 Quest Phone Mirror

**手机投屏到任意设备的局域网投屏工具** —— 实时镜像 · 反向控制 · 文件浏览

*Quest / 手机 / 平板 / 电脑端，一个 APK 双角色，两端同一 Wi-Fi 即用*

[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B%20%2F%20Quest-4a9eff)](https://github.com/parieses/QuestPhoneMirror)
[![Version](https://img.shields.io/badge/Version-v0.18-4a9eff)](https://github.com/parieses/QuestPhoneMirror/releases)
[![License](https://img.shields.io/badge/License-MIT-4a9eff)](https://github.com/parieses/QuestPhoneMirror/blob/main/LICENSE)
[![Built with](https://img.shields.io/badge/Built_with-Kotlin%20%2B%20MediaCodec-4a9eff)](https://developer.android.com/reference/android/media/MediaCodec)
[![Min SDK](https://img.shields.io/badge/Min__SDK-24-4a9eff)](https://github.com/parieses/QuestPhoneMirror)

🌐 官网 [https://parieses.github.io/QuestPhoneMirror/](https://parieses.github.io/QuestPhoneMirror/)　·　📦 Releases [https://github.com/parieses/QuestPhoneMirror/releases](https://github.com/parieses/QuestPhoneMirror/releases)　·　🐛 Issues [https://github.com/parieses/QuestPhoneMirror/issues](https://github.com/parieses/QuestPhoneMirror/issues)

* * *

> 把手机画面实时镜像到 Quest 头显、另一台手机平板、或任何实现了[本协议](#接入自定义接收端)的电脑端，
> 并能在接收端上点击、滑动、输入文字来**反向操作手机**——免 PC、免公网、免账号、免 root。

* * *

## ✨ 它解决什么

想在大屏上操作手机：竞品要么依赖 PC 中转（scrcpy + USB）、要么绑定云账号、要么在 Quest 上只能看不能控。
QPM 把整条链路压进**一个 APK**——手机端 `MediaProjection` 录屏硬编 H.264 推流，接收端硬解渲染，
控制指令沿同一条 TCP 连接回传，由无障碍服务以真实手势执行。

- **双角色单 APK**：同一份包装在手机上是发送端，装在 Quest / 手机 / 平板上是接收端，打开选个角色即用
- **自动发现**：手机每秒 UDP 广播，接收端打开即连，无需输入 IP；局域网唯一发送端时**免配对码直连**
- **反向控制**：点击、拖动、文字输入、Home / 返回 / 多任务键，Quest 控制器激光、触摸屏、鼠标均适用
- **文件互传**：接收端可浏览发送端共享存储，图片 / 文本应用内预览，其他文件直接下载
- **多设备管理**：发送端「连接管理」扫描局域网接收端，只放行勾选的设备，踢人即时生效

* * *

## 🧩 功能全景

| 能力 | 一句话 |
|---|---|
| 实时镜像 | 1080p30 H.264 硬编，画质三档可调（流畅 4M / 均衡 6M / 高清 10M） |
| 自动发现 | UDP 45555 每秒广播 `QPM1\|端口\|宽x高`，接收端打开即连，无需输入 IP |
| 免密直连 | 局域网内只有 1 个发送端时自动免密直连；检测到多个则弹出选择列表 |
| 配对码保护 | 每次投屏随机生成 6 位配对码，输对才推流；连续输错 3 次断开 |
| 连接管理 | 多接收端场景：扫描列表（设备名 + IP + 状态）、勾选放行、取消勾选立即断开并拒绝重连 |
| 反向控制 | 点击 / 拖动 / 文字输入 / Home·返回·多任务键，走 `AccessibilityService.dispatchGesture` |
| 文件互传 | 浏览 / 下载发送端文件，也可把本机文件推送回发送端共享存储（均 64KB 分片） |
| 防自动息屏 | 投屏期间临时禁用系统熄屏（需「修改系统设置」权限），退出自动恢复 |
| 检查更新 | 入口页一键检查 GitHub Releases 最新版，下载 APK 并拉起系统安装 |
| 方向同步 | 手机横竖屏旋转后自动重建画布并通知接收端重排，画面不压扁、触摸不错位 |
| 加密通道 | 连接先 ECDH 密钥交换，帧体 AES-256-GCM 加密，防局域网嗅探（兼容旧版明文回落） |
| 文件推送 | 接收端选本机文件推送到发送端共享存储，进度可见、成功回显路径 |
| 投屏统计 | 接收端一键开浮窗，实时显示分辨率 / 帧率 / 码率 / 丢帧 / 解码帧率 |
| 健壮性 | CSD 三重保险 + IDR 前重发 SPS/PPS、队列满丢旧帧保延迟、编解码错误直达 UI |

* * *

## 🚀 快速开始

### 环境要求

- **发送端**：Android 7.0+ 手机（arm64），需要录屏权限 + 无障碍权限（反向控制）
- **接收端**：Quest 2 / 3 / 3S / Pro（侧载）· 任意 Android 7.0+ 手机 / 平板 · 实现了[本协议](#接入自定义接收端)的电脑端
- **网络**：两端同一局域网（同一路由器 / 同一网段，注意路由器的 AP 隔离）

### 安装

1. 从 [Releases](https://github.com/parieses/QuestPhoneMirror/releases) 下载 `app-release.apk`
   （release 签名包；**App 内「检查更新」推送的也是它**，签名一致才能覆盖安装。`app-debug.apk` 仅限开发调试）
2. **手机**：直接安装（允许未知来源）
3. **Quest**：只能侧载——开开发者模式后 `adb install -r app-release.apk`；
   侧载应用不在桌面，在「资料库 → 右上角筛选 → 未知来源」里启动

### 使用

1. 两端连**同一个 Wi-Fi**
2. 手机：App →「我是发送端」→「开始投屏」→ 授予录屏权限
3. 手机：页面显示 6 位配对码；开启「QPM 控制服务」无障碍权限（不开则对方只能看不能控）
4. 接收端：App →「我是接收端」→ 自动搜索连接：
   - **只有 1 个发送端**：自动免密直连，直接出画面
   - **有多个发送端**：弹出列表选择，选定后输入配对码
5. 画面卡顿时：回发送端把「画质」切到「流畅」，优先连 5G Wi-Fi
6. 浏览发送端文件：接收端左下角「📁 文件」按钮（需先在发送端点「授予文件访问权限」）
7. 有多台接收端在线时：发送端「连接管理」→「扫描接收端」，取消勾选即断开该设备
8. 更新：入口页底部「检查更新」→ 有新版本弹窗展示说明 →「下载并安装」；下载自动走 Gitee 镜像 / 加速代理 / GitHub 直连中最快可用的源

* * *

## 📚 功能详解

**📺 实时镜像与画质**

- `MediaProjection` 录屏 → `MediaCodec` 硬编 H.264（Surface 输入，VirtualDisplay 直接绘制）→ TCP 推流
- 画质三档：**流畅 4M / 均衡 6M / 高清 10M**，默认均衡；弱网 / 2.4G Wi-Fi 选流畅
- **丢帧策略**：发送队列深度 3，满则丢最旧帧——优先保延迟，不保完整
- **CSD 兼容**：编码参数集（SPS/PPS）三重保险缓存，并在每个 IDR 前重发——兼容只认 CSD-0/CSD-1/CSD-2 之一的机型
- 新客户端接入时主动请求关键帧，避免花屏

**🔍 自动发现与免密直连**

- 手机每秒向 `255.255.255.255:45555` 广播 `QPM1|<tcpPort>|<w>x<h>`
- 接收端扫描 1.5s：**唯一发送端 → 发空配对码免密直连**；多个 → 弹列表选择后输配对码
- 发送端同时监听是否有同类 beacon：网络里出现第二台发送端，免密直连自动关闭
- 免密成立的条件是**双方都认为网络里只有一个发送端**，单方面伪造空码会被拒

**🔐 配对码与连接管理**

- 每次点「开始投屏」生成新 6 位随机码；**配对成功前不推流、控制帧全丢弃**
- 连续输错 3 次，发送端主动断开
- 接收端连接后立即报送 `HELLO`（设备名），发送端「连接管理」实时列出：**设备名 + IP + 已连接/在线**
- 取消勾选 = 移出白名单 + 立即断开，对方收到 `REJECT` 帧并提示「发送端已拒绝本机连接」
- 白名单为空 = 全部允许（单设备用户零感知）；取消第一台时自动把其余已知设备收进名单，**只踢这一台**

**🖱️ 反向控制**

- 点击 / 拖动 / 文字输入 / `KEY`（4=返回 3=Home 187=多任务），走 `AccessibilityService.dispatchGesture`
- 接收端坐标 → 手机屏幕坐标按宽高比居中映射，不拉伸
- 单指针模型：Quest 控制器激光、触摸屏、鼠标天然兼容；**暂不支持双指缩放**
- 无障碍服务未就绪时控制指令限流丢弃并打日志，不会刷爆 logcat

**📁 文件浏览**

- 协议 5 类文件帧：列目录 / 目录结果 / 下载请求 / 64KB 分片数据 / 错误
- 发送端路径校验：canonical 路径，只允许共享存储根目录内，防 `..` 越权
- 图片（≤8MB）/ 文本（≤512KB）应用内直接预览；其他文件保存到接收端
  `Android/data/com.qpm.mirror/files/Download/`
- 文件数据走独立写锁可靠通道，不与视频抢丢帧队列；**第二条 TCP 连接，独立配对**

**🔄 检查更新**

- 入口页底部「检查更新」→ 查询 GitHub Releases 最新版（`releases/latest` 接口，免 token）
- 版本号**逐段数字比较**（`v0.10 > v0.9`，不吃字符串比较的亏）；优先下载 **release 签名包**——debug 包在澎湃OS / MIUI 上会被拒授无障碍等敏感权限
- 下载走 `DownloadManager` 落到应用外部私有目录（无需存储权限），完成后经 `FileProvider` 拉起系统安装器；安装前自动引导「允许安装未知应用」
- **多源下载加速**：版本检测是轻量 JSON 仍走 GitHub API；3MB 级 APK 按候选链顺序 HEAD 探测选源——**Gitee 镜像 → 公共加速代理 → GitHub 直连**，国内提速明显；代理随时可能挂，链式回退不断链
- GitHub 未认证接口限流 **60 次/小时/IP**：自动检查每 **12 小时**一次，手动点检查不受限；下载中途离开页面，回来后自动补提示安装，装过的版本不再打扰

> **Gitee 镜像配置（可选，国内下载再加速）**：Gitee → 新建仓库（建议同名 `QuestPhoneMirror`）→ 设置 → 仓库镜像管理 → 添加 GitHub 镜像 `https://github.com/parieses/QuestPhoneMirror.git`（勾选同步 Release），之后每次 GitHub 发版 Gitee 自动同步，App 下载自动走 Gitee 直连。

**📐 方向同步（旋转适配）**

- `VirtualDisplay` 用 `AUTO_MIRROR` 只镜像内容不缩放，手机横竖屏切换后画面会被挤成中间一条 / 压扁
- 两路信号同时监听、任一触发就核对尺寸：`DisplayManager.DisplayListener`（显示配置真的变了才回调，**视频/游戏 App 强制横屏、手机没转也能感知**）+ `OrientationEventListener`（传感器 90° 分桶防抖）
- 尺寸不一致时用 `Display.getRealMetrics()` 取实时尺寸重建 VirtualDisplay + H264Encoder；重建失败（个别机型不支持 3200×1440 这类尺寸）会**按旧尺寸自动恢复继续投屏**，不再直接断流，并记住该尺寸避免反复闪屏
- 重建后重发 `SCREEN_INFO`（`0x20`），接收端据此重排 `SurfaceView` 宽高比与 `TouchMapper` 触摸映射，**画面与点击同步适配**

**🔐 加密通道**

- 连接建立后双方先交换 EC(`secp256r1`) 临时公钥（`KEY_EXCHANGE` `0x1E`，X.509 编码）；ECDH 导出共享密钥经 SHA-256 派生出 AES-256 会话密钥
- 此后所有帧体走 AES/GCM/NoPadding 加密（128-bit tag）；nonce = 方向位 + 帧序号隐式计数——TCP 有序保证收发计数同步，不同方向位错开防止 nonce 重用
- 加密只发生在传输层封装，**帧格式与 Proto 编解码完全不变**；密钥交换缺失 / 失败时自动回落明文并打日志，兼容旧版本客户端

**📤 文件双向推送**

- 在「文件浏览」之外，接收端可主动把本机文件推送到发送端共享存储：接收端「📤 推送」选文件 → 按 64KB 分片发 `FILE_PUSH_REQ` / `FILE_PUSH_DATA`
- 发送端校验目标路径落在共享存储根目录内后落盘，完成回 `FILE_PUSH_RES`（`ok=1` 时 msg 为手机侧保存路径）
- 推送与下载同源受 `FileRelay` 根目录白名单约束，无法越权写入系统目录

**📊 投屏统计浮窗**

- 发送端编码回调累计帧数 / 字节 / 丢帧，每秒经 `STATS`（`0x1F`）帧广播给已配对接收端
- 接收端「📊 统计」开关开启后右上角半透明浮窗每 1s 刷新：**分辨率 / 推流 FPS / 码率 / 丢帧 / 解码 FPS**
- 码率、丢帧直接反映网络与编码压力，卡顿时先降画质再查网络

**🔒 其他**

- **防自动息屏**：投屏期间临时改 `SCREEN_OFF_TIMEOUT`，退出恢复原值（MediaProjection 熄屏即停，见「已知限制」）
- **两端常亮**：发送端 / 接收端 Activity 均加 `FLAG_KEEP_SCREEN_ON`
- **错误可达**：编解码 `onError`、端口占用、投影授权撤销、配对事件全部经状态通道显示在 UI 上，不再黑屏无日志

* * *

## 📝 更新日志

### v0.18（本轮）

**改进**
- **「屏幕共享防护」提示**：无障碍开启后，发送端界面会多一条说明——微信 / QQ 投屏发虚、像蒙了层毛玻璃，是系统（小米/澎湃OS 的「电诈防护 → 屏幕共享防护」）检测到「屏幕共享 + 具备控制能力的无障碍服务」后对敏感应用做的**模糊遮罩**，与投屏画质无关。README 常见问题同步给出三种处理方式（关闭系统开关 / 投屏时点左上角「继续共享」/ 只在需要控制时开无障碍）

* * *

### v0.17

**修复**
- **接收端解码器按发送端真实分辨率配置**：此前 configure 用写死的 1920×1080，而手机实际画面是 1440×3200 / 3200×1440 这类尺寸——硬解在尺寸明显不符时，部分机型会在 native 层直接崩溃（Java 层拦不住，表现为「带配对成功却闪退回首页」「手机一转屏接收端就没了」）。现在收到 `SCREEN_INFO` 就按真实尺寸创建解码器，尺寸变化时**热替换**新解码器（`MirrorClient.decoder` 改为可替换，旧实例立即停掉），进程内还会记住上次尺寸，重建/重进界面都不再退回默认值
- **旋转时先播报尺寸、再放行新码流**：`SCREEN_INFO` 在新尺寸帧之前写入连接（`activeGen` 之后才更新），确保接收端已经换好对应尺寸的解码器，不会被新分辨率帧打个措手不及
- **发送线程异常不再静默**：对端进程消失导致的发送失败原先被 `catch (_: Exception)` 吞掉，日志里只剩「连接异常: Socket closed」看不出起因；现在记录异常类型与消息

* * *

### v0.16

**修复**
- **手机横屏必断投屏（真正根因）**：MediaProjection 的令牌一生只能 `createVirtualDisplay` 一次——Android 14 起第二次调用直接抛「Don't take multiple captures…」并**连带撤销整个录屏授权**（紧跟其后的 `Cannot create VirtualDisplay with non-current MediaProjection` 就是授权已被系统作废）。旧实现旋转时「release 旧虚拟显示 → 再建新的」，第二次创建必然失败、按旧尺寸回退再失败，只能 `stopAll` 断流。现改为**复用同一个虚拟显示**：新建编码器（编码器输入面尺寸在 configure 时固定，无法原地改）→ `setSurface` 把新输入面挂上去 → `resize` 改采集分辨率，三步全部成功才停旧编码器；任一步失败则把旧 surface 挂回去，旧流水线继续出画面
- **接入 Android 14 `onCapturedContentResize`**：系统在采集内容真正变尺寸时（旋转、应用强制横屏、分屏拖动）直接告知新尺寸，与 DisplayListener、传感器两路互补，仍然统一走 120ms 防抖 + 尺寸核对
- **编码器代际守卫细化**：区分「已分配代际」与「在职代际」，换面失败回滚时旧编码器继续出帧（此前回滚会让旧编码器的帧被代际守卫永久丢弃，表现为回滚后黑屏）
- **防息屏原值污染**：上次异常退出没恢复系统息屏超时，值停在哨兵 `Int.MAX_VALUE`，下次启动把它当「用户原值」原样写回，手机从此永不自动熄屏（日志表现为「已恢复自动息屏时间：2147483647s」）。现原值持久化到 SharedPreferences，读到哨兵时保留历史原值，未知则恢复 1 分钟兜底
- **接收端解码加固**：拿到 SPS/PPS 或首个关键帧之前不再把 P 帧塞进硬解（无参数集时部分机型在 native 层直接崩溃），并记录「收到 SPS/PPS / 首个关键帧 / 首个渲染帧」里程碑，便于精确定位崩溃发生在哪一步

**新功能**
- **进程退出记录**：启动时读取 Android 11+ 的进程退出历史（`ApplicationExitInfo`），把 `CRASH_NATIVE` / `ANR` / `LOW_MEMORY` 等原因码、状态与 Android 12+ 的 tombstone 全文写进 `crashes/exit-*.log`，诊断页可查看分享——专治「闪退回首页但日志里没有任何堆栈」这类 Java 层抓不到的退出

* * *

### v0.15

**新功能**
- **App 内诊断日志**：入口页新增「诊断日志」卡片——查看当天日志尾部、一键分享（经 FileProvider，可发微信/QQ）、查看/分享崩溃记录、清空当天。全模块日志统一走 `QpmLog` 双通道（logcat + `files/logs/qpm-日期.log`，同步写保证崩溃前最后几行落盘、保留 3 天、单文件 8MB 封顶），没有电脑也能抓 log
- **全局崩溃捕获**：`QpmApp` 安装未捕获异常处理器，任何线程的致命异常都会把完整堆栈同时写进 logcat、日志文件和 `crashes/crash-*.log`——「已连接却退回主界面」这类进程级故障终于有据可查
- **连接链路全节点日志**：发现循环进入/退出、`connectTo` 参数、TCP 已连接、HELLO 已发送、加密建立/回落（带头型）、主循环首帧、每 3s 收帧汇总、EOF/读帧异常（带累计帧数）全部落日志

**修复**
- 手机横屏必断投屏：旋转重建时旧编码器的异步回调（`onError`/`onFrame`）可能晚于新编码器建成才触发，旧实现里一个迟到的 `onError` 会 `post { stopAll() }` 把刚建好的新流水线拆掉——与重建成败无关，所以「一转屏就断」。现按编码器代际丢弃旧实例的迟到回调；同时把拆除顺序对调为**先停编码器、再释放显示**（编码器运行中释放其输入 VirtualDisplay 会 abandon 输入 surface、随即抛 `onError`，旧顺序正是触发器）
- 编码器回调线程未捕获异常直接杀进程：`onOutputBufferAvailable` 整体兜底（旧实现只包了 `releaseOutputBuffer`，漏了 `getOutputBuffer`——停编解码器后的在途回调足以致命）；`onOutputFormatChanged`、`onError` 同样加固
- 接收端 `surfaceCreated` 里解码器回调直接 `runOnUiThread`，界面销毁后仍会触碰已回收视图；改为带销毁守卫的 `runUi`，并补齐缺失的 `onDestroy`（置销毁标志、清定时器）

* * *

### v0.14

**修复**
- 接收端配对成功后立马断开、退回主界面（手机 / Quest 作接收端均复现）：根因是配对成功后首帧数据流上有三处未防护的异常出口——解码器 `feed()` 的 `buffer.put()` 遇超大关键帧抛 `BufferOverflowException` 打断整条连接；读循环帧分发无兜底、一帧异常即断流；编解码回调线程 `releaseOutputBuffer` 未捕获 `IllegalStateException` 直接杀进程。现已全部加固：超大帧丢载荷但归还输入缓冲（防缓冲池枯竭黑屏）、单帧异常只丢该帧、回调异常全部兜底
- 加密通道实际未启用：接收端 v0.13 起先明文发 `HELLO` 再发 `KEY_EXCHANGE`，而发送端仍要求「首帧必须是 KE」，导致双方永不武装、全程明文。发送端握手改为循环消费（`HELLO` 内联记录、拿到 KE 才回发并武装），加密真正生效且计数器严格对齐
- 加密运算失败后通道永久卡死：`SecureChannel` 失败只置 `broken` 不清密钥，后续帧持续加解密失败；现改为失败即清密钥，双向自动收敛回明文，连接保持可用
- 离开接收端界面 / 停止投屏时偶发闪退：beacon 广播线程在 `Thread.sleep` 中被 `interrupt()`，未捕获的 `InterruptedException` 逃逸出 Runnable 直接杀进程；两个广播循环均已消化中断与网络异常
- 横屏看视频 / 玩游戏时接收端画面挤成中间一条：手机屏幕已经横过来、编码器却仍按竖屏尺寸出图（VirtualDisplay 尺寸没变，系统只能把横屏画面缩进竖屏缓冲，上下留大片黑边，视频只占中间 20% 高）。只监听 `OrientationEventListener` 会漏掉「视频 App 用 `setRequestedOrientation` 强制横屏、手机本身没转（或开着旋转锁定）」这种最常见的情况——现同时注册 `DisplayManager.DisplayListener`，两路任一触发都核对 `getRealMetrics()` 与当前编码尺寸，不一致即重建；重建失败（个别机型不支持 3200×1440 这类尺寸）不再 `stopAll` 断流，而是按旧尺寸恢复继续投屏，并记住该尺寸避免反复闪屏

* * *

### v0.13

**新功能**
- **方向同步优化**：手机横竖屏旋转后自动重建画布并重排接收端画面与触摸映射，画面不压扁、点击不错位
- **加密通道**：连接先 ECDH(`secp256r1`) 密钥交换，帧体 AES-256-GCM 加密，防局域网嗅探；密钥交换失败自动回落明文（兼容旧版客户端）
- **文件双向推送**：接收端可主动把本机文件推送到发送端共享存储（64KB 分片、进度可见、成功回显手机侧保存路径）
- **投屏统计浮窗**：接收端一键开浮窗，实时显示分辨率 / 帧率 / 码率 / 丢帧 / 解码帧率

**修复**
- 单发单收仍被要求输入配对码：发送端把自身 beacon 误判为「其他发送端」导致免密直连被拒；已过滤本机地址、并让手动 IP 连接也先尝试免密直连
- 输入配对码后卡在「正在配对」：加密握手时 `HELLO` 占用了加密计数器槽位，使 `PAIR` 的 nonce 错位、GCM 校验失败；已将 `HELLO` 改为握手前明文发送
- 点接收端再返回时闪退：解码器异步回调在 Surface 销毁后仍向已销毁 Surface 渲染；已加生命周期守卫
- 接收端无刷新入口：顶部新增 🔄 刷新按钮，并在免密直连等待超时时提示刷新重搜

* * *

## 🏗️ 架构

```
┌──────────┐   MediaProjection 录屏   ┌────────────┐
 │  手机    │ ───────────────────────▶ │ H264Encoder│
 │ (发送端) │                          └─────┬──────┘
 │          │ ◀───────────────────────      │ H.264 裸流
 │          │   TCP 45556 反向控制消息        ▼
 │          │                          ┌───────────┐  同一局域网
 │          │                          │ TCP 服务   │ ────────▶ ┌──────────┐
 │          │                          └───────────┘           │ 接收端   │
 │          │ ◀── QpmControlService 执行点击/滑动/输入 ◀──────── │(任意设备)│
 └──────────┘        （无障碍手势，免 root）                    └──────────┘
       ▲                                                              │
       └── UDP 45555 每秒广播 QPM1|端口|宽x高，接收端监听即自动发现 ──┘
```

**架构亮点**

- **协议层零依赖**：`protocol/` 纯 Kotlin 库（帧编解码 + 发现），无 Android 依赖——第三方实现电脑端只依赖这一层
- **Surface 输入编码**：VirtualDisplay 直接画进编码器 InputSurface，无 YUV 拷贝
- **异步 MediaCodec**：输入缓冲由回调分配入队，规避异步模式下 `dequeueInputBuffer` 返回 -1 导致的全帧丢弃
- **丢帧保延迟**：每客户端独立队列，深度 3 满则丢旧；控制 / 文件走写锁可靠通道，两类流量互不阻塞
- **门控清晰**：配对前视频 / 控制 / 文件全部门控；白名单在配对前拦截

* * *

## 🔌 接入自定义接收端

协议与平台无关，电脑端 / 第三方程序按以下步骤即可对接（帧格式均为小端序，body 上限 4MB）：

1. **发现**：UDP 监听 `45555`，收到 `QPM1|<tcpPort>|<w>x<h>` 即得到发送端 IP 与端口
2. **连接**：TCP 连接 `<ip>:45556`
3. **握手**：连接后双方先交换 `KEY_EXCHANGE`（`0x1E`，X.509 编码的 EC 公钥，**明文发送**）——接收端连上先发本方 KE，随后读取对端 KE 完成握手；握手完成后后续帧体 AES-GCM 加密（旧版本不交换则回落明文）。随后接收端发 `HELLO`（`0x1B`，UTF-8 设备名）；再发 `PAIR`（`0x14`）——空 body = 免密直连请求（仅当局域网只有一个发送端时被接受），非空 = 6 位配对码；等 `PAIR_RESULT`（`0x15`，1=成功）；被白名单拒绝会先收到 `REJECT`（`0x1C`）再被断开
4. **收视频**：`VIDEO_FRAME`（`0x01`）= `[pts:u64][flags:u8][h264裸流]`，flags bit0=关键帧、bit1=SPS/PPS；SPS/PPS 与关键帧同帧透传，原样喂解码器；首帧前先收 `SCREEN_INFO`（`0x20`）得知分辨率
5. **发控制**：`TAP` / `SWIPE` / `TEXT` / `KEY`（`0x10-0x13`）写回同一连接，仅配对成功后有效
6. **被扫描到**（可选）：向 `255.255.255.255:45555` 每秒广播 `QPMR|<设备名>`，发送端「连接管理」即出现本设备

### 协议帧表

| 类型 | 方向 | 格式 |
|---|---|---|
| `0x01` VIDEO_FRAME | 手机→接收端 | `[pts:u64][flags:u8][h264]`，flags bit0=关键帧 bit1=SPS/PPS |
| `0x20` SCREEN_INFO | 手机→接收端 | `[w:u32][h:u32][dpi:u32]` |
| `0x10` TAP | 接收端→手机 | `[x:f32][y:f32]` |
| `0x11` SWIPE | 接收端→手机 | `[x1][y1][x2][y2][durMs:u32]` |
| `0x12` TEXT | 接收端→手机 | UTF-8 字符串 |
| `0x13` KEY | 接收端→手机 | `[keycode:u32]`（4=返回 3=Home 187=多任务） |
| `0x14` PAIR | 接收端→手机 | UTF-8 配对码；空 body = 免密直连请求 |
| `0x15` PAIR_RESULT | 手机→接收端 | `[ok:u8]`（1=配对成功，开始推流；0=失败） |
| `0x16` FILE_LIST_REQ | 接收端→手机 | UTF-8 目录路径 |
| `0x17` FILE_LIST_RES | 手机→接收端 | UTF-8，条目 `\n` 分隔，字段 `\t` 分隔：name\isDir\size\mtime |
| `0x18` FILE_GET_REQ | 接收端→手机 | UTF-8 文件路径 |
| `0x19` FILE_DATA | 手机→接收端 | `[offset:u64][total:u64][eof:u8][chunk]`，64KB 分片 |
| `0x1A` FILE_ERROR | 手机→接收端 | UTF-8 错误信息（未授权/路径非法/读取失败） |
| `0x1B` HELLO | 接收端→手机 | UTF-8 设备名（连接建立后立即报送） |
| `0x1C` REJECT | 手机→接收端 | UTF-8 拒绝原因（如未在白名单），发送后即断开 |
| `0x1D` CLIPBOARD | 双向 | UTF-8 文本（手机→接收端=写入接收端剪贴板；接收端→手机=写入手机剪贴板） |
| `0x1E` KEY_EXCHANGE | 双向 | X.509 编码的 EC(secp256r1) 临时公钥；连接后最先发送，此后帧体全部 AES-GCM 加密 |
| `0x1F` STATS | 手机→接收端 | `[fps:u16][bitrateBps:u32][w:u16][h:u16][dropped:u16]`，每秒广播 |
| `0x21` FILE_PUSH_REQ | 接收端→手机 | `[total:u64][path UTF-8]`（手机侧目标路径） |
| `0x22` FILE_PUSH_DATA | 接收端→手机 | `[offset:u64][eof:u8][chunk]`，64KB 分片 |
| `0x23` FILE_PUSH_RES | 手机→接收端 | `[ok:u8][msg UTF-8]`，ok=1 时 msg 为手机侧保存路径 |

端口：TCP `45556`，UDP `45555`。参考实现见 `app/` 的 `MirrorClient`（约 150 行）与 `protocol/`（纯协议编解码）。

* * *

## 💻 技术栈

| 技术 | 说明 |
|---|---|
| Kotlin 1.9.24 | 主语言 |
| AGP 8.5.2 / Gradle 8.7 | 构建 |
| compileSdk 34 / minSdk 24 | Android 7.0+ |
| MediaProjection + VirtualDisplay | 录屏（Surface 输入，无 YUV 拷贝） |
| MediaCodec | H.264 硬编 / 硬解（异步模式） |
| AccessibilityService | 反向控制（`dispatchGesture`） |
| DatagramSocket 广播 | UDP 45555 发现（发送端 + 接收端双 beacon） |
| GitHub Actions | push `v*` tag 自动构建 debug + release 并挂 Release |

**模块划分**：`protocol/`（纯 Kotlin 协议库，零 Android 依赖）+ `app/`（单 App 双角色），无第三方业务依赖。

* * *

## 🔧 开发指南

### 前置条件

- Android Studio Hedgehog+ / JDK 17 / Android SDK 34

```bash
# 首次生成 wrapper（或直接用 Android Studio 打开，会自动处理）
gradle wrapper --gradle-version 8.7

# 构建 debug APK
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

### 项目结构

```
protocol/   共享协议与发现（纯 Kotlin 库，无 Android 依赖）
  Proto.kt       帧编解码（5 字节头 + body，小端序）
  Discovery.kt   双 beacon 广播 / 扫描（QPM1 发送端 + QPMR 接收端）
app/        单 App 双角色
  RoleActivity      角色选择入口 + 检查更新（GitHub Releases）
  SenderActivity    录屏授权、配对码、画质/防息屏、连接管理、无障碍引导
  CastService       录屏 + 编码 + TCP 服务 + 配对校验 + 白名单 + 控制分发
  QpmControlService 无障碍服务，执行点击/滑动/输入
  ReceiverActivity  SurfaceView + 配对输入 + 触摸映射 + 接收端宣告
  FileBrowserActivity 文件浏览/下载/预览（接收端）
  MirrorClient      发现 + 连接 + 配对 + 收发（约 150 行，第三方参考实现）
  H264Encoder       MediaCodec 硬编（Surface 输入 + CSD 三重保险）
  H264Decoder       MediaCodec 硬解（异步模式，直接渲染）
  TouchMapper       视图坐标 → 手机屏幕坐标
  UpdateChecker     GitHub Releases 查询 + 版本比较 + APK 资产挑选
  AppUpdater        DownloadManager 下载 + FileProvider 安装 + 权限引导
  QpmApp            全局未捕获异常捕获（崩溃堆栈落盘 crashes/）
  QpmLog            日志双通道：logcat + files/logs/qpm-日期.log（同步写、3 天保留、8MB 封顶）
  LogViewerActivity 诊断日志页（查看 / 分享 / 崩溃记录 / 清空）
.github/workflows/build-apk.yml   push tag v* 自动打包 debug + release APK 并挂 Release
```

### 版本发布

```bash
git tag v0.18        # 版本号唯一来源就是 git tag
git push origin v0.18
```

CI 在 push `v*` tag 时自动构建并挂 Release。**release 签名密钥经 GitHub Secrets 注入，不要写进 workflow 文件。**

* * *

## ❓ 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| Quest 里找不到 App | 侧载应用不在桌面，「资料库 → 筛选 → 未知来源」里找 |
| 接收端搜不到发送端 | 两端不在同一网段；路由器开了 AP 隔离；手机热点与接收端频段隔离 |
| 连上但只能看不能控 | 手机端没开「QPM 控制服务」无障碍权限；或没输入配对码 |
| 提示「发送端已拒绝本机连接」 | 手机端「连接管理」里取消了本机勾选；重新勾选即可恢复 |
| 画面花屏 / 绿屏 | 等一秒让关键帧到达；仍不行重进接收端 |
| 更新时提示「签名不一致」 | 旧版装的是 debug 签名包，卸载后装 Releases 里的 `app-release.apk` 即可（App 无持久数据，卸载无损失） |
| 画面卡 / 延迟高 | 画质切「流畅」；换 5G Wi-Fi；确认两端离路由器不远 |
| 横屏看视频 / 玩游戏时，接收端画面挤成中间一条 | 手机屏幕已经横过来、编码器却还在按竖屏尺寸出图，系统就把横屏画面缩进竖屏缓冲里（表现为上下大片黑边、视频只占中间 20% 高）。新版已同时监听显示配置变化与传感器角度，任一触发就按新尺寸重建编码器；旧版遇到时**在横屏状态下停止投屏再重新开始**即可（重连不会重建编码器） |
| 解码错误提示 | 返回重进接收端；系统版本过旧的设备可能缺 H.264 硬解 |
| 开启无障碍后，微信 / QQ 投屏像蒙了一层毛玻璃（关掉无障碍就正常） | 系统的「屏幕共享防护」（小米/澎湃OS 在 **设置 → 电诈防护 → 屏幕共享防护**，部分机型在 **开发者选项 → 停用屏幕防护共享**）检测到「屏幕共享 + 具备控制能力的无障碍服务」，就会把微信、QQ、相册、支付宝等敏感应用**模糊遮罩**。这不是投屏编码问题，也不是本 App 能绕过的。三种处理：① 关闭该系统开关；② 投屏时点屏幕左上角/顶部的提示，选择「继续共享」（单次放行）；③ 只在需要反向控制时才开「QPM 控制服务」，看完敏感应用就关掉 |
| Netflix 等黑屏 | DRM 硬件保护，无法镜像 |

## ⚠️ 已知限制

- **锁屏 / 熄屏投屏**：MediaProjection 的 AOSP 机制是熄屏即停，锁屏更会被当作安全内容冻结，**没有免 root 的可靠方案**；已提供「防自动息屏」作为最接近的保活手段
- **文件浏览权限**：Android 11+ 需在发送端页面点「授予文件访问权限」；Android 10 受 scoped storage 限制可能只能访问部分路径
- **单指针控制**：Quest 控制器激光 / 触摸屏 / 鼠标均按单指针处理，暂不支持双指缩放
- **安全边界**：帧体已 AES-256-GCM 加密（ECDH 密钥交换），防局域网被动嗅探；但配对码只防随手连接，不防同网主动中间人，仍仅适用于可信 Wi-Fi
- **DRM 内容**（Netflix 等）黑屏

* * *

## 📄 许可协议

[MIT License](https://github.com/parieses/QuestPhoneMirror/blob/main/LICENSE) © 2025–2026 王亮亮

## 🙏 致谢

- [Android MediaProjection](https://developer.android.com/reference/android/media/projection/MediaProjection) · [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec) · [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)

# QPM 手机镜像 Quest Phone Mirror

**手机投屏到任意设备的局域网投屏工具** —— 实时镜像 · 反向控制 · 文件浏览

*Quest / 手机 / 平板 / 电脑端，一个 APK 双角色，两端同一 Wi-Fi 即用*

[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B%20%2F%20Quest-4a9eff)](https://github.com/parieses/QuestPhoneMirror)
[![Version](https://img.shields.io/badge/Version-v0.10-4a9eff)](https://github.com/parieses/QuestPhoneMirror/releases)
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
| 文件浏览 | 目录列表、图片 / 文本应用内预览、其他文件 64KB 分片下载到本机 |
| 防自动息屏 | 投屏期间临时禁用系统熄屏（需「修改系统设置」权限），退出自动恢复 |
| 检查更新 | 入口页一键检查 GitHub Releases 最新版，下载 APK 并拉起系统安装 |
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

**🔒 其他**

- **防自动息屏**：投屏期间临时改 `SCREEN_OFF_TIMEOUT`，退出恢复原值（MediaProjection 熄屏即停，见「已知限制」）
- **两端常亮**：发送端 / 接收端 Activity 均加 `FLAG_KEEP_SCREEN_ON`
- **错误可达**：编解码 `onError`、端口占用、投影授权撤销、配对事件全部经状态通道显示在 UI 上，不再黑屏无日志

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
3. **握手**：连接后立即发 `HELLO`（`0x1B`，UTF-8 设备名）；再发 `PAIR`（`0x14`）——空 body = 免密直连请求（仅当局域网只有一个发送端时被接受），非空 = 6 位配对码；等 `PAIR_RESULT`（`0x15`，1=成功）；被白名单拒绝会先收到 `REJECT`（`0x1C`）再被断开
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
.github/workflows/build-apk.yml   push tag v* 自动打包 debug + release APK 并挂 Release
```

### 版本发布

```bash
git tag v0.10        # 版本号唯一来源就是 git tag
git push origin v0.10
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
| 解码错误提示 | 返回重进接收端；系统版本过旧的设备可能缺 H.264 硬解 |
| Netflix 等黑屏 | DRM 硬件保护，无法镜像 |

## ⚠️ 已知限制

- **锁屏 / 熄屏投屏**：MediaProjection 的 AOSP 机制是熄屏即停，锁屏更会被当作安全内容冻结，**没有免 root 的可靠方案**；已提供「防自动息屏」作为最接近的保活手段
- **文件浏览权限**：Android 11+ 需在发送端页面点「授予文件访问权限」；Android 10 受 scoped storage 限制可能只能访问部分路径
- **单指针控制**：Quest 控制器激光 / 触摸屏 / 鼠标均按单指针处理，暂不支持双指缩放
- **安全边界**：配对码只防随手连接，不防同网恶意主动攻击；控制通道零鉴权，仅适用于可信 Wi-Fi
- **DRM 内容**（Netflix 等）黑屏

* * *

## 📄 许可协议

[MIT License](https://github.com/parieses/QuestPhoneMirror/blob/main/LICENSE) © 2025–2026 王亮亮

## 🙏 致谢

- [Android MediaProjection](https://developer.android.com/reference/android/media/projection/MediaProjection) · [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec) · [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)

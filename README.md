# QPM 手机镜像（Quest Phone Mirror）

> 项目介绍页：[`docs/index.html`](docs/index.html)（启用 GitHub Pages 后可通过 `https://parieses.github.io/QuestPhoneMirror/` 访问）

一个 APK 双角色的局域网投屏工具：装在**手机**上是发送端，装在 **Quest 2 / 3 / 3S / Pro** 上是接收端。
手机画面实时镜像进头显，并能反过来在头显里点击、滑动、输入来操作手机。

- 免 PC、免公网、免账号、免 root——两端同一 Wi-Fi 即用
- 手机端 `MediaProjection` 录屏 → H.264 硬编 → TCP 推流
- 头显端监听手机 UDP 广播自动发现 → `MediaCodec` 硬解 → SurfaceView 渲染
- 控制走 `AccessibilityService.dispatchGesture`，全机型可用
- 深色 UI + 自适应启动图标，单 APK 覆盖两端

## 工作原理

```
┌──────────┐   MediaProjection 录屏   ┌────────────┐
 │  手机    │ ───────────────────────▶ │ H264Encoder│
 │ (发送端) │                          └─────┬──────┘
 │          │ ◀───────────────────────      │ H.264 裸流
 │          │   TCP 45556 反向控制消息        ▼
 │          │                          ┌───────────┐  同一局域网
 │          │                          │ TCP 服务   │ ────────▶ ┌──────────┐
 │          │                          └───────────┘           │  Quest   │
 │          │ ◀── ControlService 执行点击/滑动/输入 ◀────────── │ (接收端) │
 └──────────┘        （无障碍手势，免 root）                    └──────────┘
       ▲                                                              │
       └── UDP 45555 每秒广播 QPM1|端口|宽x高，头显监听即自动发现 ─────┘
```

## 功能特性

| 功能 | 说明 |
|---|---|
| 实时镜像 | 1080p30 H.264 硬编，默认 8.3Mbps，弱网可调低 |
| 自动发现 | 手机每秒 UDP 广播，头显打开即连，无需输入 IP |
| 反向控制 | 点击、拖动、文字输入、Home/返回/多任务键 |
| 画面自适应 | 解码后按手机宽高比居中缩放，不拉伸 |
| 新客户端接入 | 主动请求关键帧，避免花屏 |
| 丢帧策略 | 发送队列深度 3，满则丢旧帧，优先保延迟 |

## 环境要求

- **手机（发送端）**：Android 7.0+，arm64；需要录屏权限 + 无障碍权限
- **Quest（接收端）**：Quest 2 / 3 / 3S / Pro，需开开发者模式侧载
- **网络**：两端同一局域网（同一路由器/同一网段，注意 AP 隔离）
- **开发**：Android Studio Hedgehog+ / JDK 17 / Android SDK 34

## 安装

### 方式一：直接下载（推荐）

[Releases](https://github.com/parieses/QuestPhoneMirror/releases) 页面下载 `app-debug.apk`，手机和 Quest 各装一份。

### 方式二：自行构建

```bash
git clone git@github.com:parieses/QuestPhoneMirror.git
cd QuestPhoneMirror

# 首次生成 wrapper（或直接用 Android Studio 打开，会自动处理）
gradle wrapper --gradle-version 8.7

./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

### 装到 Quest

Quest 端只能侧载，需先开开发者模式：

1. 浏览器打开 `developer.oculus.com`，建一个组织（免费）
2. 手机 Meta Horizon app → 设备 → 你的 Quest → 打开「开发者模式」
3. USB 连接 Quest，头显内弹窗点「允许 USB 调试」
4. `adb install -r app-debug.apk`

> **注意**：Quest 的侧载应用**不会出现在主页桌面**，这是 Meta 的机制。
> 头显内进入「资料库 → 右上角筛选 → 未知来源」即可找到并启动。

## 使用

1. 手机和 Quest 连**同一个 Wi-Fi**
2. 手机：App →「我是发送端」→ 开始投屏 → 授予录屏权限
3. 手机：「开启控制权限（无障碍）」→ 在系统设置里启用「QPM 控制服务」
   —— **不开这项，对方只能看不能操作**
4. Quest：App →「我是接收端」，自动搜索连接，画面出现后用控制器激光点击操作

投屏期间请保持发送端 App 在前台，并在系统设置里允许其后台运行，否则可能被系统杀掉。

## 协议

单条 TCP 连接双向复用，统一帧格式 `[type:u8][length:u32 LE][body]`：

| type | 方向 | body |
|---|---|---|
| `0x01` VIDEO_FRAME | 手机→头显 | `[pts:u64][flags:u8][h264]`，flags bit0=关键帧 bit1=SPS/PPS |
| `0x20` SCREEN_INFO | 手机→头显 | `[w:u32][h:u32][dpi:u32]` |
| `0x10` TAP | 头显→手机 | `[x:f32][y:f32]` |
| `0x11` SWIPE | 头显→手机 | `[x1][y1][x2][y2][durMs:u32]` |
| `0x12` TEXT | 头显→手机 | UTF-8 字符串 |
| `0x13` KEY | 头显→手机 | `[keycode:u32]`（4=返回 3=Home 187=多任务） |

发现：手机每秒向 `255.255.255.255:45555` 广播 `QPM1|<tcpPort>|<w>x<h>`，头显监听 `45555`。
端口：TCP `45556`，UDP `45555`。

## 项目结构

```
protocol/   共享协议与发现（纯 Kotlin 库，无 Android 依赖）
app/        单 App 双角色
  RoleActivity      角色选择入口
  SenderActivity    申请录屏权限、引导无障碍
  CastService       录屏 + 编码 + TCP 服务 + 控制消息分发
  ControlService    无障碍服务，执行点击/滑动/输入
  ReceiverActivity  SurfaceView + 触摸映射
  MirrorClient      发现 + 连接 + 收发
  H264Encoder       MediaCodec 硬编（Surface 输入）
  H264Decoder       MediaCodec 硬解（直接渲染）
  TouchMapper       视图坐标 → 手机屏幕坐标
.github/workflows/build-apk.yml   push tag v* 自动打包 debug + release APK 并挂 Release
```

## 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| Quest 里找不到 App | 侧载应用不在桌面，「资料库 → 筛选 → 未知来源」里找 |
| 手机上找不到图标 | 检查抽屉/搜索应用名；部分桌面需重启或下拉刷新 |
| 连上但只能看不能控 | 手机端没开「QPM 控制服务」无障碍权限 |
| 头显搜不到发送端 | 两端不在同一网段；路由器开了 AP 隔离；手机热点与 Quest 频段隔离 |
| 画面花屏/绿屏 | 等一秒让关键帧到达；仍不行重进接收端 |
| 画面卡/延迟高 | 路由器 2.4G 干扰或手机性能不足，可下调码率 |
| Netflix 等黑屏 | DRM 硬件保护，无法镜像 |

## 已知限制

- 手机熄屏即断，需保持亮屏
- DRM 内容（Netflix 等）黑屏
- 头显控制器是单指针激光，暂不支持双指缩放
- 头显端是 2D 面板，不是 VR 空间里的原生悬浮面板
- 无鉴权，仅适用于可信局域网

## 版本发布

```bash
git tag -a v0.2 -m "说明"
git push origin v0.2
```

push tag 即触发 GitHub Actions 自动构建，产物挂到对应 Release。

## License

本项目采用 [MIT License](LICENSE) 开源许可证。

Copyright (c) 2025-2026 王亮亮

> 完整许可证文本见仓库根目录的 [LICENSE](LICENSE) 文件。

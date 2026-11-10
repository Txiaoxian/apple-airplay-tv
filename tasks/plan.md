# Implementation Plan: PureAir-TV (索尼电视专属 AirPlay 镜像接收端)

## Overview
为索尼 XR-65X91J 电视（Android TV 10，基于联发科 MT9950 芯片平台）构建一款独立纯净、零广告、免密直连的原生 AirPlay 屏幕镜像接收端应用。iPhone 无需安装任何额外软件，下拉 iOS 控制中心点击“屏幕镜像”即可自动发现“索尼电视”并进行高画质、低延迟硬件解码投屏。

## Architecture Decisions
1. **网络广播机制**：采用 `jmdns` 实现 mDNS/Bonjour 服务发现，在局域网内注册 `_airplay._tcp.local.` 与 `_raop._tcp.local.` 广播，携带 AirPlay 镜像所需 features 标志与设备名。
2. **信令与传输架构**：
   - RTSP 控制通道运行于 TCP 7000 端口，基于轻量异步 Socket/Kotlin 协程处理信令。
   - 视频数据通道支持 TCP/UDP 传输，接收 RTP 数据包。
3. **免密安全握手**：
   - 适配标准 Pair-Setup / Pair-Verify 协议，默认免密直接交换公钥。
   - 基于 ChaCha20-Poly1305 和 Curve25519 协商会话秘钥，提取 AES-128-CBC 密钥与初始向量 IV。
4. **硬件零拷贝解码管线**：
   - 剥离 H.264 Annex-B NALU 单元，SPS/PPS 动态初始化 Android `MediaCodec`。
   - 视频帧直接解码到 `SurfaceView`（GPU 零拷贝合成直出），延迟控制在 100ms 左右。
   - 音频通过 `AudioTrack` 保持低延迟播放。
5. **系统与大屏交互**：
   - 前台常驻 Service（带 Wi-Fi 锁与多播锁），支持开机自启。
   - TV 待机大屏展示 IP、设备名与操作引导，手机发起投屏时无缝全屏展示画面，手机挂断时自动返回待机屏。

## Task List

### Phase 1: Foundation (工程基础骨架)
- [ ] **Task 1**: Android TV 工程配置与构建脚手架 (Gradle 8, Kotlin, 清单文件, TV Leanback 特性)

### Checkpoint: Foundation
- [ ] 基础工程结构完备，Gradle 脚本与依赖配置就绪

### Phase 2: Core Protocols (网络发现与信令握手)
- [ ] **Task 2**: mDNS / Bonjour 局域网服务广播发布 (发布“索尼电视”)
- [ ] **Task 3**: RTSP 7000 信令服务器与状态机 (处理 OPTIONS, SETUP, RECORD, TEARDOWN)
- [ ] **Task 4**: AirPlay 认证配对与免密会话秘钥协商 (Pair-Setup / Pair-Verify)

### Checkpoint: Handshake Ready
- [ ] 广播能被 iPhone 正常识别并显示“索尼电视”，点击后完成免密信令握手

### Phase 3: Streaming & Player (数据解密与硬件播放)
- [ ] **Task 5**: RTP 码流解复用与 AES-128-CBC 实时解密 (NALU 剥离)
- [ ] **Task 6**: Android MediaCodec 硬件加速直解与 AudioTrack 播放管线

### Checkpoint: End-to-End Playback
- [ ] iPhone 屏幕画面与音频成功解码上屏渲染，帧率稳定，低延迟

### Phase 4: TV UI & Production Build (电视大屏与打包交付)
- [ ] **Task 7**: Android TV 大屏待机 UI、前台常驻服务与开机自启
- [ ] **Task 8**: APK 打包构建与索尼电视 U 盘安装操作指南

### Checkpoint: Complete Delivery
- [ ] APK 产物生成，完整用户使用说明书就绪

## Risks and Mitigations
| Risk | Impact | Mitigation |
| :--- | :--- | :--- |
| Android TV 息屏或后台杀进程 | 高 | 注册前台服务（`ForegroundService`），持有 `WifiLock` 和 `MulticastLock`，避免被系统休眠 |
| 索尼电视 MT9950 MediaCodec 格式兼容 | 中 | 严格校验 SPS/PPS 参数，以 Annex-B 模式配置 H.264 Baseline/Main Profile 解码器 |
| 局域网多播包被路由器拦截 | 中 | 在待机界面醒目提示用户检查路由器“AP隔离/组播转发”，提供电视 IP 显示辅助排查 |

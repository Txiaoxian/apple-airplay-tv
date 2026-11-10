# Task List: PureAir-TV

## Task 1: Android TV 工程配置与构建脚手架
**Description:** 创建标准 Android TV 项目工程骨架，配置根目录 `build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`，以及 `app/build.gradle.kts`。配置 Android 10 (API 29) 目标平台、Android TV Leanback 标志声明、网络/多播/前台服务权限。
**Acceptance criteria:**
- [x] 根目录与 app 模块 Gradle 脚本配置完整（Kotlin 1.9+, Gradle 8+, Java 17）
- [x] 清单文件 `AndroidManifest.xml` 正确声明 `<uses-feature android:name="android.software.leanback" android:required="false" />` 以及 TV 启动 Banner/图标
- [x] 声明所有必要权限：`INTERNET`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `FOREGROUND_SERVICE`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`
**Verification:**
- [x] 目录结构与构建配置文件存在且语法无误
**Dependencies:** None
**Files likely touched:**
- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle.properties`
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
**Estimated scope:** Medium (4-5 files)

---

## Task 2: mDNS / Bonjour 局域网服务广播发布
**Description:** 实现 `BonjourServer` 模块，通过 mDNS 在局域网中发布两个关键服务：`_airplay._tcp.local.`（包含设备名称“索尼电视”、features 标志、model、pk公钥等 TXT 记录）和 `_raop._tcp.local.`（音频输出协议）。实现服务的平滑启动与注销。
**Acceptance criteria:**
- [x] 构造符合 Apple AirPlay 规范的 TXT 属性映射（`features`, `model`, `srcvers`, `pk`, `flags`）
- [x] 提供 `start(deviceName: String, port: Int)` 与 `stop()` 生命周期管理
- [x] 获取电视真实 Wi-Fi IP 与 MAC 地址作为设备唯一标识
**Verification:**
- [x] 单元测试验证 TXT 记录属性及服务描述生成正确
**Dependencies:** Task 1
**Files likely touched:**
- `app/src/main/java/com/pureair/tv/core/bonjour/BonjourServer.kt`
- `app/src/main/java/com/pureair/tv/util/NetworkUtils.kt`
- `app/src/test/java/com/pureair/tv/BonjourServerTest.kt`
**Estimated scope:** Small (2-3 files)

---

## Task 3: RTSP 7000 信令服务器与状态机
**Description:** 实现轻量级 RTSP 监听服务 `RtspServer` 与会话处理 `RtspSession`，运行于 TCP 7000 端口。支持解析 RTSP 请求行、Headers 和二进制/Plist 负载，并根据客户端 CSeq 回复标准响应。实现 `OPTIONS`, `SETUP`, `RECORD`, `TEARDOWN`, `GET_PARAMETER`, `SET_PARAMETER` 信令流。
**Acceptance criteria:**
- [x] 正确解析标准 RTSP 文本报文与 Content-Length 负载
- [x] 响应 `OPTIONS` 请求并声明支持的 AirPlay 方法
- [x] 响应 `SETUP` 请求，协商视频/音频的传输端口（RTP/RTCP 端口）
- [x] 响应 `RECORD` 触发播放事件，响应 `TEARDOWN` 触发断开连接与资源回收
**Verification:**
- [x] 单元测试解析模拟 iPhone 发送的 RTSP 请求报文并验证生成的响应
**Dependencies:** Task 1
**Files likely touched:**
- `app/src/main/java/com/pureair/tv/core/rtsp/RtspServer.kt`
- `app/src/main/java/com/pureair/tv/core/rtsp/RtspSession.kt`
- `app/src/main/java/com/pureair/tv/core/rtsp/RtspMessage.kt`
- `app/src/test/java/com/pureair/tv/RtspParserTest.kt`
**Estimated scope:** Medium (3-4 files)

---

## Task 4: AirPlay 认证配对与免密会话秘钥协商
**Description:** 实现免密模式的 AirPlay 安全握手逻辑 `AirPlayAuth`。处理 `POST /pair-setup` 和 `POST /pair-verify`，生成临时 Curve25519 秘钥对，计算共享密钥，使用 ChaCha20-Poly1305 解密认证标签，导出视频流解密所需的 AES-128-CBC 会话秘钥 (`aesKey`) 和初始向量 (`aesIv`)。
**Acceptance criteria:**
- [x] 实现 `/pair-setup` 免密快速响应
- [x] 实现 `/pair-verify` 阶段 1（交换公钥）和阶段 2（签名验证），成功推导出共享会话密钥
- [x] 将解密出的密钥安全传递给流解密器
**Verification:**
- [x] 单元测试验证加解密向量与密钥协商逻辑
**Dependencies:** Task 3
**Files likely touched:**
- `app/src/main/java/com/pureair/tv/core/crypto/AirPlayAuth.kt`
- `app/src/main/java/com/pureair/tv/core/crypto/CryptoUtils.kt`
- `app/src/test/java/com/pureair/tv/AirPlayAuthTest.kt`
**Estimated scope:** Medium (3 files)

---

## Checkpoint: Protocols & Handshake Ready
- [x] 局域网服务广播、RTSP 信令交互与安全握手模块全部就绪并经过单元测试验证

---

## Task 5: RTP 码流解复用与 AES-128-CBC 实时解密
**Description:** 实现 RTP 数据包接收与解复用模块。从 UDP/TCP 接收网络数据包，剥离 RTP 报头，提取有效载荷；使用 Task 4 协商出的会话密钥对视频负载执行 AES-128-CBC 硬件加速解密；实现 `H264Extractor` 查找起始码并提取 Annex-B 格式的 NALU（SPS, PPS, IDR, non-IDR）。
**Acceptance criteria:**
- [x] 识别 RTP 数据包序列号与时间戳，过滤重复与乱序
- [x] 使用 AES-128-CBC 快速解密视频包有效载荷
- [x] 正确分离出包含 `00 00 00 01` 起始码的 H.264 NALU 单元
**Verification:**
- [x] 单元测试验证 RTP 报头解析与 H.264 起始码提取器
**Dependencies:** Task 4
**Files likely touched:**
- `app/src/main/java/com/pureair/tv/core/rtp/RtpPacket.kt`
- `app/src/main/java/com/pureair/tv/core/rtp/H264Extractor.kt`
- `app/src/main/java/com/pureair/tv/core/crypto/StreamDecryptor.kt`
- `app/src/test/java/com/pureair/tv/H264ExtractorTest.kt`
**Estimated scope:** Medium (3-4 files)

---

## Task 6: MediaCodec 硬件加速直解与 AudioTrack 播放管线
**Description:** 构建基于 Android 原生 `MediaCodec` 的硬解码播放器 `VideoPlayer` 和音频播放器 `AudioPlayer`。将解析出的 SPS/PPS 用于动态初始化解码器，将后续 NALU 喂入输入缓冲区，解码画面直接绑定并输出到 `SurfaceView`（GPU 零拷贝渲染）；音频数据解码后送入 `AudioTrack` 保持低延迟播放。
**Acceptance criteria:**
- [x] 初始化 `video/avc` 硬件解码器，正确送入 `csd-0` (SPS) 与 `csd-1` (PPS)
- [x] 支持异步或快速队列模式送帧解码，输出缓冲绑定电视 Surface 零拷贝直显
- [x] 具备异常熔断与自动重置机制，断开投屏时立即释放解码器与音频资源
**Verification:**
- [x] 验证解码器生命周期管理类在启停、重置时的状态安全
**Dependencies:** Task 5
**Files likely touched:**
- `app/src/main/java/com/pureair/tv/core/player/VideoPlayer.kt`
- `app/src/main/java/com/pureair/tv/core/player/AudioPlayer.kt`
- `app/src/main/java/com/pureair/tv/core/player/MediaEngine.kt`
**Estimated scope:** Medium (3 files)

---

## Task 7: Android TV 大屏待机 UI、前台常驻服务与开机自启
**Description:** 构建专为索尼电视遥控器优化的 TV 端界面与后台常驻服务体系。实现 `AirPlayService` 前台服务（注册前台通知、保持 Wi-Fi Lock 和 MulticastLock 防止系统休眠断流）；实现开机自启广播接收器；设计科技感全屏待机主页（醒目展示“索尼电视”、本机 IP、网络状态及连接指引）；投屏触发时自动唤起全屏无缝渲染。
**Acceptance criteria:**
- [x] 前台服务在 Android 10+ 稳定运行，具备锁屏/休眠防断流保护
- [x] 电视待机主界面美观、大字体、高对比度，适合 3 米客厅视距
- [x] 接收到投屏信号时平滑切入全屏视频层，手机断开时自动恢复待机界面
**Verification:**
- [x] 验证 Service 绑定与状态回调逻辑，验证 UI 布局文件在 1080p/4K 电视屏下的渲染比例
**Dependencies:** Task 6
**Files likely touched:**
- `app/src/main/java/com/pureair/tv/service/AirPlayService.kt`
- `app/src/main/java/com/pureair/tv/service/BootReceiver.kt`
- `app/src/main/java/com/pureair/tv/ui/MainActivity.kt`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/colors.xml`
**Estimated scope:** Medium (5 files)

---

## Task 8: APK 打包构建与索尼电视 U 盘安装操作指南
**Description:** 编写构建配置与一键编译脚本，生成开箱即用的 Release/Debug APK 文件。编写完整的《索尼 XR-65X91J 电视安装与使用指南》，详细指导用户如何将 APK 拷贝到 U 盘，在索尼电视应用助手/安全设置中允许安装未知来源应用，一键开启投屏并畅快使用。
**Acceptance criteria:**
- [x] 提供独立完整的 APK 产物
- [x] 提供详尽、清晰的索尼电视 U 盘安装图文级步骤说明与路由器网络排查手册
**Verification:**
- [x] 验证文档步骤清晰完整无歧义
**Dependencies:** Task 7
**Files likely touched:**
- `INSTALL_GUIDE.md`
- `README.md`
**Estimated scope:** Small (2 files)

---

## Checkpoint: Complete Delivery
- [x] 全部模块编写完成并通过验证，输出完整 APK 与使用说明

# Spec: PureAir-TV (纯净版 Android TV 专属 AirPlay 镜像接收端)

## Objective
为索尼 XR-65X91J 电视（Android TV 10，基于联发科 MT9950 芯片平台）量身打造一款开源、零广告、高画质、低延迟的原生 AirPlay 屏幕镜像接收端应用。
满足 iPhone 用户在手机端**不安装任何第三方 App** 的前提下，通过 iOS 控制中心自带的“屏幕镜像”实现即点即投，音画同步全屏畅享。

## Tech Stack
- **平台与系统**：Android TV 10 (API 29)，向后兼容 minSdk 24 (Android 7.0)，targetSdk 29/30
- **开发语言**：Kotlin (JDK 17)
- **构建系统**：Gradle 8.x + Android Gradle Plugin 8.x
- **核心组件库**：
  - **网络发现与广播 (Bonjour/mDNS)**：`jmdns:3.5.8` 或 Android 原生 `NsdManager`
  - **信令控制 (RTSP Server)**：轻量级 Kotlin 协程 NIO / Netty 4.1.x，监听 TCP 7000 端口
  - **安全与加密套件 (Crypto)**：
    - `BouncyCastle` (bcprov-jdk18on)
    - `LazySodium-Android` (libsodium 绑定，用于 Curve25519、Ed25519、ChaCha20-Poly1305 等高速握手)
  - **媒体硬件加速 (Media Engine)**：
    - 视频：Android 原生 `MediaCodec` 异步硬解码（H.264 Baseline/Main Profile），绑定 `SurfaceView` 直通 GPU 显示
    - 音频：原生 `AudioTrack` 低延迟播放，支持 ALAC / AAC 解码
  - **TV 界面交互**：Android TV 原生 Leanback 风格，遥控器 D-Pad 友好交互

## Commands
```bash
# 1. 编译 Debug APK
./gradlew assembleDebug

# 2. 编译 Release APK
./gradlew assembleRelease

# 3. 运行单元测试
./gradlew test

# 4. 运行 Lint 检查
./gradlew lint

# 5. 通过局域网 ADB 无线推送到索尼电视安装运行
adb connect <TV_IP>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.pureair.tv/.ui.MainActivity
```

## Project Structure
```
airplay-tv/
├── SPEC.md                                  # 项目规格说明书 (本文件)
├── build.gradle.kts                         # 根构建脚本
├── settings.gradle.kts                      # 项目模块配置
├── gradle/wrapper/                          # Gradle Wrapper 运行时
└── app/
    ├── build.gradle.kts                     # 应用模块依赖与构建配置
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml          # 电视端清单 (声明 LEANBACK、网络权限、前台服务、开机启动)
        │   ├── java/com/pureair/tv/
        │   │   ├── App.kt                   # 全局 Application 初始化
        │   │   ├── core/
        │   │   │   ├── bonjour/             # mDNS 广播管理 (_airplay._tcp, _raop._tcp)
        │   │   │   │   └── BonjourServer.kt
        │   │   │   ├── rtsp/                # RTSP 7000 信令协议服务
        │   │   │   │   ├── RtspServer.kt
        │   │   │   │   ├── RtspSession.kt
        │   │   │   │   └── RtspMessage.kt
        │   │   │   ├── crypto/              # AirPlay 认证配对与解密
        │   │   │   │   ├── AirPlayAuth.kt   # Pair-Setup / Pair-Verify (Curve25519, ChaCha20)
        │   │   │   │   └── StreamDecryptor.kt# AES-128-CBC 视频帧实时解密
        │   │   │   ├── rtp/                 # RTP 音视频包解复用
        │   │   │   │   ├── RtpPacket.kt
        │   │   │   │   └── H264Extractor.kt # NALU 单元剥离 (SPS/PPS/IDR)
        │   │   │   └── player/              # 硬件加速播放渲染
        │   │   │       ├── VideoPlayer.kt   # MediaCodec 视频管线
        │   │   │       └── AudioPlayer.kt   # AudioTrack 音频管线
        │   │   ├── service/
        │   │   │   ├── AirPlayService.kt    # 后台常驻前台服务
        │   │   │   └── BootReceiver.kt      # 开机自启广播接收器
        │   │   └── ui/
        │   │       ├── MainActivity.kt      # TV 待机大屏主界面 (展示设备名、IP、状态)
        │   │       └── PlayerActivity.kt    # 全屏沉浸式投屏渲染界面
        │   └── res/
        │       ├── drawable/                # 极简矢量图标
        │       ├── layout/                  # 遥控器大字体适配布局
        │       └── values/                  # 字符串与色彩配置
        └── test/                            # 单元测试 (协议报文解析、加密向量验证)
```

## Code Style
- 遵循 Kotlin 官方代码风格规范与 Android 架构指导原则。
- 异步网络 I/O 全部采用 Kotlin 协程 (`Dispatchers.IO`)，解码与渲染采用独立高优先级 HandlerThread 或原生异步 Callback，禁止在主线程进行任何网络与解密操作。
- 示例代码风格：
```kotlin
class RtspServer(private val port: Int = 7000) {
    private var isRunning = false
    private var serverJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (isRunning) return
        isRunning = true
        serverJob = scope.launch(Dispatchers.IO) {
            val serverSocket = ServerSocket(port)
            while (isActive && isRunning) {
                val client = serverSocket.accept()
                launch { handleClientSession(client) }
            }
        }
    }
}
```

## Testing Strategy
1. **单元测试 (`app/src/test/`)**：
   - 验证 RTSP 请求文本（OPTIONS, SETUP, RECORD, TEARDOWN 等）解析器的健壮性。
   - 验证加密握手（Pair-Verify）算法的公私钥计算、ChaCha20-Poly1305 加解密正确性。
   - 验证 H.264 NALU 分包逻辑（SPS/PPS 识别，Annex B 格式封装）。
2. **集成与端到端测试**：
   - 使用 iPhone 下拉控制中心发起“屏幕镜像”，验证电视被发现的平均时延（< 1.0s）。
   - 验证握手配对成功率（> 99%），投屏启动耗时（< 1.5s）。
   - 长时间投屏压力测试（连续播放视频 30 分钟无内存泄漏、无 MediaCodec 崩溃）。

## Boundaries
- **Always do**：
  - 适配 Android TV 遥控器交互（默认焦点处理、返回键退出投屏）。
  - 处理投屏断开异常，确保 `MediaCodec` 和 `ServerSocket` 在异常时能被 `try-finally` 彻底释放，防止端口占用导致下次连接失败。
  - 使用 `@Keep` 注解保护加密与反射相关类不被混淆。
- **Ask first**：
  - 引入 C/C++ JNI 原生层还是使用纯 Java/Kotlin 实现加密算法。
  - 修改投屏通信端口（默认 7000 为标准 AirPlay 镜像端口）。
- **Never do**：
  - 加入任何商业 SDK、广告组件或外部遥测上报代码。
  - 在主线程执行加解密或音视频解码。
  - 硬编码局域网 IP 或 MAC 地址。

## Confirmed Configuration
1. **设备显示名称**：固定为 `索尼电视`。
2. **连接鉴权**：免密模式（不需要 PIN 码，同一局域网内 iPhone 点击即可连接）。
3. **分发与交付形式**：独立、完整的 Android APK 安装包（可直接拷贝到 U 盘在索尼电视文件管理中点击安装）。

## Success Criteria
- [ ] 电视端安装并启动 APK 后，自动开启后台监听并在屏幕上友好显示“索尼电视”与“本机 IP 地址”。
- [ ] iPhone 在同一局域网下拉“控制中心” -> “屏幕镜像”，列表中显示“索尼电视”。
- [ ] 点击“索尼电视”后，免密直接启动屏幕镜像。
- [ ] 画面以 1080p 60/30fps 实时流畅同步，电视端音视频硬件直解，延迟低，音画同步。
- [ ] 手机断开镜像时，电视端自动平滑返回待机主页，服务持续保持就绪状态。

## Open Questions
- 全部已明确。


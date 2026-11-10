# Apple投屏 (PureAir-TV) - Android TV & 大屏设备 AirPlay 镜像接收端

专为 **Android TV / Google TV 大屏设备（索尼、小米、TCL、海信、创维及各类智能投影仪/安卓手机）** 打造的开源、零广告、高画质、低延迟 AirPlay 屏幕镜像接收端应用。

## 核心特性
- **手机零安装**：iPhone / iPad / Mac 无需安装任何第三方客户端或扫码，下拉 iOS 控制中心「屏幕镜像」直接发现并投屏。
- **免密秒连**：客厅大屏免密直连，即点即投，告别繁琐的 PIN 码配对。
- **动态设备识别**：自动根据设备硬件识别品牌与型号（如 `Sony XR-65X91J`、`Xiaomi MI 6` 等）并在 iOS 投屏列表中优雅展示。
- **硬件直解输出**：深度结合 Android 原生 `MediaCodec` H.264 硬件解码与 `Surface` 直出渲染，高帧率低延迟。
- **纯净无广**：无开屏广告、无弹窗骚扰、无强制更新、无任何云端隐私收集。
- **后台常驻与自动唤醒**：配备前台 Service 与多播锁（MulticastLock），支持开机自启；支持退到后台或电视主页时自动拉起投屏（适配原生 Android TV 与小米 MIUI/HyperOS 专有权限）。

## 项目架构
- `core/bonjour/`：mDNS / Bonjour 广播服务 (`_airplay._tcp.local.`, `_raop._tcp.local.`)
- `core/rtsp/`：RTSP 7000 控制信令服务器与状态机 (OPTIONS, SETUP, RECORD, TEARDOWN)
- `core/crypto/`：Ed25519/X25519 握手与会话密钥协商、FairPlay v3 认证、AES-128 码流解密
- `core/rtp/`：RTP 音视频解复用与 H.264 Annex-B NALU 实时提取
- `core/player/`：MediaCodec 硬件直解 + AudioTrack 低延迟音频播放管线
- `service/`：前台保活常驻服务与开机启动广播接收器
- `ui/`：专为电视大屏及遥控器设计的高清科技感待机界面与权限管理

## 快速编译与运行
项目自带一键环境配置与构建脚本：
```bash
./setup_and_build.sh
```
执行后将自动检测 Android SDK 与 Gradle 环境，编译完成后在根目录生成 `Apple投屏-v1.2.2.apk`。

## 详细安装指南
请阅读 [INSTALL_GUIDE.md](INSTALL_GUIDE.md) 获取 U 盘安装、遥控器使用与局域网路由器网络排查说明。

## 开源协议 (License)
本项目基于 [MIT License](LICENSE) 开源。

引用及复用的第三方库版权与声明：
- ALAC 解码核心（BSD 许可证）
- FairPlay 密钥算法与查找表（MIT 许可证）

详细来源与第三方声明参见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。本项目仅供技术交流与家庭大屏投屏体验优化使用。

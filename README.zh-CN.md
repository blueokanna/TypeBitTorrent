# TypeBitTorrent

面向 **Windows 桌面**与 **Android** 的 BitTorrent 客户端，Kotlin 编写，
UI 采用 **Jetpack Compose Multiplatform（Material 3）**，核心是
**typebit** Rust 引擎。

`typebit` 是一个没有界面的引擎核心（BitTorrent v1/v2、DHT、PEX、web
seeds、效用分块调度器、磁盘缓存、可验证下载凭证）。这个仓库是它的外壳：
一套 Kotlin 代码，两套界面（类 qBittorrent 的桌面窗口 + Material 3 的
Android 应用），中间隔着一层极薄的 JNI 桥。

```
┌──────────────────────────────┐        ┌─────────────────────────────┐
│  Kotlin / Compose (common)   │  JNI   │  Rust cdylib (native/)      │
│  UI · AppStore · 设置        │◄─────►│  引擎线程 · Host I/O         │
│  仓库 · RSS · 搜索           │        │  JSON 协议 · 元数据镜像      │
└──────────────────────────────┘        └──────────────┬──────────────┘
                                                       │ 静态链接
                                                ┌──────▼──────┐
                                                │ typebit 0.1 │ (AGPL-3.0)
                                                └─────────────┘
```

## 为什么要做这个

qBittorrent 很好，BitComet 也很好，但它们都不是能嵌入的库，而且都是
庞大的 C++ 代码库。`typebit` 正好相反：一个 `no_std` 的 Rust 核心，有
干净的 `Host` 接口，却完全没有 UI。本仓库就是那缺失的界面——同一份代码
跑在 Windows 和 Android 上，平台 UI 代码一行不写。

## 目录结构

```
composeApp/
  src/commonMain/    共享 UI、状态存储、模型、设置、引擎门面
                     （+ ui/monet 纯 Kotlin HCT/CAM16 引擎、ui/wallpaper 壁纸引擎）
  src/jvmShared/     JNI `actual external` 声明 + JVM 助手
  src/androidMain/   Android 入口、平台实现、Manifest
  src/desktopMain/   桌面入口、平台实现（AWT、资源加载）
native/              Rust JNI 桥接 crate（Host 实现 + 工作线程）
scripts/             build-desktop.ps1 · build-android.ps1
docs/                architecture.md · settings.md
```

## 构建

### 前置条件

- JDK 17
- Android SDK（安卓目标）+ NDK `26.2.11394342`（编译 `.so`）
- Rust stable（1.95+）并安装安卓目标：
  `rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android`
- 本地 `typebit` 检出（`native/Cargo.toml` 里已用路径引用）

### 1. 编译原生桥

```powershell
# Windows 桌面 → composeApp/src/desktopMain/resources/native/typebit_native.dll
.\scripts\build-desktop.ps1

# Android → composeApp/src/androidMain/jniLibs/<abi>/libtypebit_native.so
$env:ANDROID_NDK_HOME = "C:\Users\你\AppData\Local\Android\Sdk\ndk\26.2.11394342"
.\scripts\build-android.ps1
```

### 2. 编译 Kotlin

```powershell
# 桌面
gradlew.bat :composeApp:run

# Android APK
gradlew.bat :composeApp:assembleDebug
```

## 已实现的功能

- 通过 **.torrent 文件**（桌面文件对话框 / Android SAF）与**磁力链接**
  添加种子（也支持 `ed2k://`、`thunder://`、`qqdl://`——引擎会解析，
  但 0.1.0 真正下载的只有 BitTorrent）。
- 开始 / 暂停 / 继续 / 删除，且恢复数据持久化（已校验分块位图 + DHT
  路由表，重启后不丢进度）。
- qBittorrent 式界面：状态筛选、分类、标签、传输表格（进度/分享率/
  剩余时间/速度），详情面板含 信息 / 文件 / Tracker / Peers / 分块
  五个标签（分块热力图取自真实位图）。
- 完整设置对话框，分类与 qBittorrent 一致
  （行为 / 下载 / 连接 / 速度 / BitTorrent / WebUI / 高级 / RSS），
  哪些选项是真实生效、哪些仅存储，见 `docs/settings.md`。
- 全局下载/上传限速，**在传输层执行**（Rust `Host` 内的令牌桶），支持
  定时切换备用限速。
- DHT 节点数、引擎日志环形缓冲、RSS 阅读器（真实 HTTP + XML 解析）、
  种子搜索（本机过滤 + 外站浏览器搜索）。
- **Material You 主题系统**（外观设置）：从零手写的纯 Kotlin
  HCT/CAM16 色彩引擎（对照官方 material-color-utilities 向量验证，
  6/6 测试通过）从**壁纸**（高斯模糊 + DIM 遮罩 + 可读性纱罩，带实时
  预览对话框）、或手动种子色生成整套 MD3 配色；支持亮色（黑色文字）/暗色
  （亮色文字）/ **AMOLED 纯黑**模式，以及 MD3 Expressive 的形状、字体
  与动效。

## 诚实的限制（报 bug 前请先读）

`typebit 0.1.0` 的公开 API 刻意收得很窄，本应用不会伪造引擎报告不了
的东西：

- **引擎不暴露每种子的上传字节/速率**，UI 显示 `—`；全局线速来自桥接
  层自己的计数器。
- **Peer 列表同样不暴露**。Peers 标签页显示的是由引擎事件推导出的
  连接数，不是编造的表格。
- **磁力元数据**：引擎取到元数据后会发 `MetadataComplete`，但不暴露
  info 字典，所以磁力种子只显示 `dn` 名称、文件列表未知（进度照常）。
- **加密模式、UPnP/NAT-PMP、uTP、LSD** 是带 qBittorrent 外观的存储项；
  0.1.0 的 wire 协议是明文，桥接层目前一个都没实现。
- **每种子限速与监听端口改动在下次启动引擎时生效**——0.1.0 API 没有
  运行时 setter。
- 内置 **WebUI 服务是路线图项目**；其设置会持久化但暂不提供服务。

本项目的原则是「正确优先于功能面积」。凡是用 0.1.0 引擎无法诚实实现的
功能，都会在 UI 和本 README 里明确标注，而不是模拟出来。

## JNI 桥

`native/` 把 `typebit` 和一个完整的 `std::net`/`std::fs` `Host` 编进一个
`cdylib`。引擎跑在专用 Rust 线程上；Kotlin 通过 mpsc 通道提交命令、轮询
事件。桥是独立的进程边界（JNI），所以 Kotlin 应用从不直接碰 socket、
文件或 DNS——完整协议与线程模型见 `docs/architecture.md`。

## 许可证

两层代码、两份许可证，理由见 `NOTICE.md`：

| 层 | 许可证 |
| --- | --- |
| Kotlin 应用（`composeApp/`） | **PolyForm Perimeter 1.0.0**（见 `LICENSE`） |
| Rust 桥（`native/`） | **AGPL-3.0-or-later**（静态链接了 AGPL 的 `typebit` 核心） |

`typebit` 引擎本身是 AGPL-3.0-or-later，© blueokanna / HyphenTeam。

---

*做成配得上 typebit 的应用。与 qBittorrent、BitComet、libtorrent 无任何
关联。*

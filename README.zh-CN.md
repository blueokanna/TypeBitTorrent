# TypeBitTorrent

> 壳是 Kotlin/Compose，脑子是 `no_std + alloc` 的 Rust 引擎。Windows 桌面 +
> Android，一套代码，两个前端。UI 线程一辈子没见过 socket，引擎线程也从不
> 碰 Compose。

```mermaid
flowchart LR
    subgraph UI["Kotlin / Compose Multiplatform"]
        A[AppRoot · 路由] --> B[AppStore]
        B --> C[AppState]
        D[页面：主页 · 详情 · 设置 · 搜索 · RSS · 添加 · 制作]
        E[设置 UI · 仓库持久化]
    end
    subgraph BRIDGE["native/ · JNI cdylib"]
        G[命令通道 mpsc]
        H[事件通道]
        I[快照 JSON · 每 tick 一次]
    end
    subgraph ENGINE["typebit 0.1.8 引擎"]
        J[tick 主循环]
        K[TorrentSession · Swarm]
        L[DHT · LSD · PEX]
        M[Tracker · WebSeed]
        N[DiskCache · VerifyPool]
    end
    subgraph HOST["NativeHost (std)"]
        O[TCP/UDP socket]
        P[.part 暂存 · 文件系统]
        Q[HTTP 工作线程 · DNS 解析]
        R[防火墙 · UPnP · NAT-PMP]
    end
    A --> G; I --> B
    G --> J; J --> K; J --> L; J --> M; J --> N
    K --> O; N --> P; M --> Q; L --> O; R --> J
```

## 目录

- [为什么](#为什么)
- [代码结构](#代码结构)
- [使用手册](#使用手册) —— 这东西到底怎么用
- [NAS 与 WebUI](#nas-与-webui) —— 飞牛 / Unraid / Docker，不需要显示器
- [引擎深潜](#引擎深潜) —— 里面是怎么转的
- [踩过的坑](#踩过的坑)
- [老实交代](#老实交代)
- [从源码构建](#从源码构建)
- [文档与许可证](#文档与许可证)

## 为什么

qBittorrent 很好用，但它是一坨没法嵌进你自己程序里的 C++ 巨兽；libtorrent
是库，可那学习曲线是一面悬崖。我想要的引擎得满足三条：（a）真能嵌进去，
（b）小到一下午能读完，（c）干了什么就老实说什么。所以有了 `typebit`：
一个带干净 `Host` 的 Rust 引擎。

## 代码结构

```
composeApp/
  src/commonMain/    共享 UI、状态、模型、设置、引擎门面
  src/jvmShared/     JNI 声明 + JVM 助手
  src/androidMain/   Android 入口、平台实现、Manifest
  src/desktopMain/   桌面入口、AWT、资源加载
native/              Rust JNI 桥（Host 实现 + 工作线程）
scripts/             build-desktop.ps1 · build-android.ps1
docs/                architecture.md · settings.md
```

---

## 使用手册

这一节是说明书。读一遍，然后直接用。

### 首次启动

| 步骤 | 桌面 | Android |
|------|------|---------|
| 1 | 解压便携版（或装安装器）启动 `TypeBitTorrent.exe` | 安装 APK，按提示授予网络权限 |
| 2 | 引擎在后台 Rust 线程自动拉起——状态栏能看到实时 DHT/Tracker 计数 | 引擎随进程启动；顶栏显示 DHT / Tracker / ↓↑ 速率 |
| 3 | **设置 → 连接**确认监听端口（默认 `6881`），路由器做了端口转发才能收入站连接 | 同样端口；Android 会自动持有 WiFi 组播锁做局域网发现 |
| 4 | 添加第一个种子（见下） | 添加第一个种子（见下） |

> Windows 上请先跑一次 **设置 → BitTorrent → Windows 防火墙与共享 →
> 自动配置**（需要管理员）。它会放行监听端口的入站 TCP+UDP，**以及 UDP
> 6771**——LSD（BEP-14）局域网发现在同一路由器下能不能互相看见，就看这条
> 规则。

### 添加种子

点 **＋**（桌面工具栏 / Android 底栏 **添加**）。添加对话框是两阶段的，
长得像 qBittorrent：

```mermaid
flowchart TD
    A[添加对话框] --> B{来源？}
    B -->|.torrent 文件| C[引擎解析 → 预览文件树]
    B -->|磁力链接| D[addMagnetResolve → 引擎后台抓元数据<br/>hold 门开启 → 一个字节都不下载]
    C --> E[文件树：三态勾选<br/>逐文件优先级 跳过 / 普通 / 高]
    D --> E
    E --> F[保存路径 · 分类 · 标签 · 添加后开始下载]
    F --> G{开始？}
    G -->|是| H[立即开始]
    G -->|否| I[以暂停加入]
```

- **`.torrent` 文件** —— 选中即出文件列表（引擎解析，不是猜的）。
- **磁力链接** —— 粘贴 `magnet:?xt=urn:btih:…`。任务**立即**创建（它是
  真任务了，关掉对话框也不会死），后台抓元数据，info 一到文件树就出现。
  **hold 门保证在你选好文件之前一个字节都不会下。**
- **文件树** —— 目录可折叠，三态勾选框（不勾 = **跳过**，该文件连
  `.part` 都不会出现在磁盘上），逐文件优先级下拉。不想要的全取消，只下
  你选的。
- **保存路径 / 分类 / 标签 / 添加后开始下载** —— 常规操作。开了自动
  Torrent 管理后分类映射到文件夹。
- 磁力两阶段：元数据没到就关对话框，任务保留；元数据到了自动开跑。也可以
  直接放手让它全量下载（qBittorrent 行为）。

### 主界面

**桌面** —— 左侧固定侧边栏 13 个筛选 + 搜索框，工具栏（搜索 / 统计 /
列表-网格切换 / 设置 / **制作种子** / **添加**），下面是种子表格。选中
种子后底部滑出详情面板。

**Android** —— 底部导航：**种子 / 添加 / 搜索 / RSS / 设置**。种子列表
是卡片（视图切换按钮可换网格）；长按卡片弹操作面板。

**筛选**（侧边栏 / 芯片）：全部 · 下载中 · 做种中 · 已完成 · 正在执行 ·
已暂停 · 活跃 · 不活跃 · 已停止 · 停止上传 · 停止下载 · 正在检查 · 出错。

**排序** —— 名称 / 可用性 / 下载速度 / 上传速度 / 分享率（桌面移动都有
排序菜单）。

**行操作**（表格行右键菜单 / 长按）：**重命名**，**分享**（磁力 / info
hash），**暂停 / 继续**，**删除**（有确认，连 `.part` 暂存文件一起清）。

**批量** —— 桌面列表上方有 **全部开始 / 全部暂停**。

### 详情面板（5 个标签页）

| 标签 | 内容 |
|------|------|
| **信息** | 大小、进度、分块统计、创建者/时间、备注、保存目录、分享率、速度。未完成文件老实写"暂存为 .part，校验通过后自动重命名" |
| **文件** | 完整文件树；逐文件优先级；**下载中重命名**（完成时 `.part` 提升为新名）；视频文件有 **▶ 播放** 按钮 |
| **Tracker** | 每个 announce URL 带层级和实时状态（工作/更新/异常）、种子与下载者数；运行时可增删 tracker |
| **Peers** | **真实**已连接 peer——地址、客户端指纹、国家国旗、进度、↑↓ 速率、在途请求；每 2 秒刷新 |
| **分块** | 分块位图实时渲染——哪块校验过了看得清清楚楚 |

### 边下边播

视频文件（ts / avi / rmvb / wmv / mp4 / mkv / …）按**文件头优先、随后严格
文件顺序**下载——分带顺序选块，稀有度永远不覆盖顺序。在**文件**页点视频
的 **▶**：

- **Android** —— 通过 FileProvider 临时授权把正在写的 `.part` 交给系统
  播放器。
- **桌面** —— 对 `.part` 调 `Desktop.open()`；头一落地默认播放器就开播，
  正片在身后追着你。
- 尾部（比如 MP4 的 `moov`）最后取，落盘后拖进度条到结尾也流畅。

### 搜索

**搜索**在后台用真人节奏轮询六个引擎并返回**真实磁力链接**（不开浏览器
标签页）：Nyaa、1337x（带镜像回退）、The Pirate Bay（带镜像回退），外加
国内磁力索引 黑马磁力 / 磁力多 / 搜番。每个引擎有实时状态
（RUNNING / DONE / BLOCKED / FAILED）。每条结果一键 **添加**，走
`addMagnetEx` 直接进添加流程。

> 搜索客户端带浏览器 UA、保留 Cookie、请求有人为节奏——别让站点（或你）
> 被限流。在线视频/网盘标题会被过滤掉。

### RSS

**RSS** 是阅读器，不是自动下载器：粘贴任意 RSS/Atom 地址，点 **订阅**，
抓取并解析（标题、链接、描述），刷新后条目点开进浏览器，垃圾桶图标取消
订阅。源跨重启持久化。

### 制作种子

**制作**（桌面工具栏 / Android 顶栏图标）→ 选**文件**或**整个文件夹**
（递归遍历，跳过空文件/零字节，重复路径直接拒绝），然后填分块大小、名称、
announce 地址、备注、**来源标签**（BEP-10 风格的 source，私人站点上传要用）
和**私有**标记（BEP-27）。

分块大小默认取「约 2000 块」对应的档位，这是私人站的常见要求；16 KiB …
256 MiB 全部可选。引擎以 1 MiB 为单位跨文件边界流式算 SHA-1，界面显示
**实时进度**（块数、字节、百分比）并支持**取消**，完成后给出真实 infohash，
以及「保存 .torrent」「加入并做种」两个按钮。infohash 就是对最终写出字节
计算的，所以你复制的和 swarm 上认的是同一个。

WebUI 的 `POST /api/create` 走的是同一条制作路径，NAS 版本产出的种子
字节级一致。

### 统计对话框

**柱状图按钮**打开实时统计（1 秒刷新）：

- **用户统计** —— 全局 ↓↑ 总量、分享率、缓存读命中率
- **缓存统计** —— 已用 / 预算、clean + dirty 条目、丢弃字节
- **性能统计** —— 写/读过载指示
- **网络统计** —— DHT 节点、活跃 tracker、**外网 IP:port**（BEP-42）、
  **LSD 计数器**：`lsd_sent` / `lsd_recv` / `lsd_peers`

> **怎么看 LSD 计数器**（BEP-14 局域网发现）：
> - `sent` 在涨 → 你在广播。
> - `sent` 涨但同路由下 `recv` = 0 → AP 隔离，或防火墙挡了入站 UDP 6771
>   （桌面：重跑一次防火墙按钮）。
> - `recv` > 0 但 `peers` = 0 → announce 到了但双方没有共同种子，或回包
>   路径被挡。

### Android 专属

- **锁屏后继续下载**（设置 → 行为）：开启后前台服务 + wake lock 让引擎在
  锁屏下继续跑。**忽略电池优化**按钮会引导你去系统豁免——某些 OEM ROM
  不做豁免就不给后台联网。
- **返回手势**：子页面返回主界面而不是退出；详情面板内返回关闭详情。
- **下载存哪**：默认保存路径是
  `Android/data/com.typebit.app/files/Download/TypeBitTorrent`——应用私有的
  外置目录，**不需要** `MANAGE_EXTERNAL_STORAGE` 就能写，USB/MTP 能看见，
  也已包含在 FileProvider 里，所以「边下边播」拿得到真实 content URI。
  想放共享目录就用文件夹选择器挑一个（前提是你已授权存储访问）。
- **边下边播**：文件标签页里点媒体行会解析出真实 content URI（`.part` 按
  扩展名 + 已声明 MIME 识别），以 `FLAG_GRANT_READ_URI_PERMISSION` 打开；
  只有系统里确实没有应用能处理时才弹选择器。
- **WiFi 组播锁**在 `Application.onCreate` 里、引擎创建任何 socket **之前**
  就获取——OEM ROM 不会事后补开组播，所以 LSD 从第一次 announce 就能收。

### NAS 与 WebUI

同一个二进制可以直接跑无界面模式，这正是 Rust/Kotlin 分层的意义：NAS 没有
显示器，但它有引擎。

```bash
typebittorrent --headless --bind=0.0.0.0 --port=8080 \
               --data=/config --downloads=/downloads --password='change-me'
```

`--headless`（或环境变量 `TYPEBIT_HEADLESS=1`）启动引擎并对外提供内置
WebUI；`--port` / `--data` / `--downloads` / `--username` / `--password`
在启动时覆盖已存设置，容器里更常用 `TYPEBIT_PASSWORD`。如果哪里都没设密码，
程序会随机生成一个并在启动时打印一次——**永远不会**裸奔一个无密码的客户端。

浏览器界面是**完整客户端**，不是状态页：传输、添加（磁力 / `.torrent` /
URL）、单文件优先级与改名、tracker、peer 列表、分块图、全局与单任务限速、
设置、引擎统计、搜索、RSS、**制作种子**，走的都是桌面窗口用的同一批
`AppStore` 调用。

打包产物在 [`packaging/`](./packaging)：

| 目标平台 | 路径 | 说明 |
|----------|------|------|
| Docker（amd64/arm64） | `packaging/docker` | 多阶段构建、精简 JRE、非 root 运行、`/config` + `/downloads` 卷 |
| Unraid | `packaging/unraid/typebittorrent.xml` | Container v2 模板；配 `/mnt/user/appdata/typebittorrent` + 你的下载共享 |
| 飞牛 fnOS | `packaging/fnos` | `build-fpk.sh` 产出 x86_64 与 arm64 两个 `.fpk`；`cmd/main` 实现 start/stop/status，安装向导设置 WebUI 密码 |
| 任意 Linux | `scripts/build-linux.sh` | 生成上面两个平台要用的 app image |

完整说明——包括反向代理/TLS 的做法、以及这些包**故意不做**什么——见
[`docs/nas.md`](./docs/nas.md)。

### 网络层

下面四件事不是"大家都这么做"，每一件都对应着一个曾经丢 Peer 或写坏数据的
真实问题。

**一个真正的解析器，而不是一个 DoH 客户端。** 引擎通过系统解析器解析域名，而
它恰恰是被污染/被劫持网络里最脆弱的一环：一个投毒答案就能让所有 `udp://`
tracker 和 BEP-5 引导路由彻底失联，而一个被墙的域名能把整个引导流程堵在超时里。
解析本身交给 [RecurseX](https://github.com/blueokanna/RecurseX)：它从根服务器
迭代下推（RFC 9156 QNAME 最小化 + 0x20 大小写随机化）、在存在签名链时校验
DNSSEC、维护带 serve-stale（RFC 8767）的多级缓存，指定上游时还能走 DoT / DoH。
这套东西自己再写一遍只会更差。

[`native/src/dns.rs`](native/src/dns.rs) 补上的是解析器**不可能**有的部分——
因为它属于这个引擎，而不属于 DNS：

- **一个绝不阻塞的边沿**：`Resolver::resolve` 按设计会阻塞，而引擎线程调用的
  同步钩子（`resolve_host` / `resolve_host_all`）不能。它们从一份"解析器答案的
  备忘录"里取答案（TTL 夹在 30 s – 1 h），取不到就立刻回退系统解析器，同时让
  后台线程去预热解析器——**下一次**查询拿到的是权威答案，tick 循环永远不等域名；
- **跨调用者 single-flight**：六个 BEP-5 引导路由同时开跑，每个域名也只会查一次；
- **与越权防护共用同一个答案**：防护层检查的地址就是引擎随后要拨的地址；
- **上游归一化**：转发上游按 **IP** 寻址、TLS 名称写在 `#` 后面（解析器没法解析
  自己的域名），常见服务商域名内置映射，其它域名在启动时用系统解析器解析一次；
  用不了的条目会带着你自己的原文写进日志，不会被静默丢弃、也不会被静默当成
  别的东西。

默认开启（「设置 → 连接 → 域名解析」）。关掉它等于：解析器不向任何第三方发问，
直接从根服务器迭代下推——这是这里最私密的模式。列表里 HTTPS、DoT、明文 UDP 都
可以写；加密上游只在**读到平台信任库**时才会启用，因为关掉校验的 DoT/DoH 就是
一个可以改写任何答案的未认证服务器，比它替换掉的系统解析器更糟。读不到信任库
时，加密条目会被丢弃、原因写进日志，解析继续以迭代方式进行。

**针对种子自带 URL 的越权防护。** 这个客户端抓取的每个 HTTP URL 都来自不可信
来源——tracker 列表、Web 种子列表，或者 `.torrent` 文件本身。照着字符串去抓
的客户端就是一台瞄准你自己机器的 SSRF 机器：`http://127.0.0.1:8080/admin`、
`http://[::1]:9200/`、`http://169.254.169.254/latest/meta-data/`。防护层同时
检查 URL 与**域名实际解析出的地址**，拒绝回环、云元数据服务与特殊用途网段；
内网地址默认放行，因为"用旁边那台 NAS 做种"是功能而不是攻击（不想让种子碰
本地网络的可以在同一个设置块里关掉）。重定向上限 2 跳，SSRF 想靠 302 绕道的
后半段也无处可去。

**真正可用的 IPv6。** 共享 UDP 套接字改成双栈（`::` + `IPV6_V6ONLY=0`），
接收时把 v4-mapped 地址还原成纯 v4，所以 IPv6 的 DHT 节点与 UDP tracker 终于
可达——此前每个 `NetAddr::V6` 发送都以 `EAFNOSUPPORT` 失败，这也正是解析器
当初被迫优先返回 IPv4 的原因。默认网关也真的能查到了（Linux 读
`/proc/net/route`，Windows 走 `GetBestRoute`），这是 NAT-PMP 必需的；加上
UPnP SOAP 的 `http_post`，自动端口映射在两类路由器上都开始工作，而不是都不工作。

**不跟自己打架的 HTTP。** Tracker 通告与 Web 种子范围请求共用一个连接池：

- **`https://` 走 HTTP/2**（一条连接、多路复用范围请求、每条流带 RFC 9218
  优先级），`http://` 走 HTTP/1.1——明文 h2 不是对端一定支持的协议，用了它
  等于静默弄坏所有明文 tracker；
- **两个优先级队列**：通告只有几百字节却决定 Peer 能不能出现，它会插到一整队
  范围请求前面；
- **真正生效的截止时间**（引擎传来的 `timeout_ms`）、keep-alive 连接池、
  诚实的 `User-Agent`、传输失败重试一次；
- **不会写坏数据的范围校验**：`206` 必须正好是请求的窗口；`200`（服务端无视
  `Range`）只在**对齐**时接受——请求从偏移 0 开始且声明长度等于窗口。其余一律
  拒绝，而不是把文件开头写进文件中间。

### 设置参考

每个分类都对齐 qBittorrent 的选项对话框。设置**立即持久化**（没有保存
按钮），能驱动引擎的改动会实时推到引擎。

#### 外观

| 设置 | 默认 | 含义 |
|------|------|------|
| 主题模式 | SYSTEM | 跟随系统 / 亮 / 暗 / AMOLED（纯黑） |
| 字体 | DEFAULT | Inter+Noto Sans SC / Roboto / Open Sans / Noto Sans SC / 系统 |
| 壁纸 | 关 | 全 UI 背后显示模糊+压暗的壁纸 |
| 模糊半径 | 24 px | 壁纸高斯模糊半径（预模糊一次、缓存） |
| 遮罩浓度 | 0.45 | 黑/白可读性压暗层，0..0.85 |
| 显示模式 | 裁剪 | 裁剪填充 或 完整适配（留黑边） |
| 垂直偏移 | 0 | 裁剪模式下壁纸上下平移，−1..1 |
| 种子色 | 自动 | 手动指定 Material You 种子色（优先于壁纸取色） |

#### 行为

| 设置 | 默认 | 含义 |
|------|------|------|
| 语言 | system | UI 语言 |
| 退出/删除确认 | 开 | 确认对话框 |
| 启动最小化 / 最小化到托盘 / 关闭到托盘 | 关 | 桌面窗口行为 |
| 通知 | 开 | 添加 / 完成 / 新版本通知 |
| **后台下载** | 开 | Android 前台服务 + wake lock（有活动任务时） |
| 刷新间隔 | 500 ms | UI 刷新节奏（同时驱动引擎轮询） |

#### 下载

| 设置 | 默认 | 含义 |
|------|------|------|
| 默认保存路径 | 空 | 种子落地位置 |
| 临时路径 | 关 | 先暂存别处，完成再移 |
| 预分配磁盘 | 关 | （真正的三模式预分配在 BitTorrent → 磁盘分配） |
| 添加后暂停 | 关 | 新种子一律暂停加入 |
| 最大活跃 下载/上传/总数 | 3 / 3 / 5 | 活跃任务数上限 |
| 自动 Torrent 管理 | 关 | 分类 → 保存路径映射 |
| 分享率 / 时间限制 | 关 | 达到比例/时间自动停止 |

#### 连接

| 设置 | 默认 | 含义 |
|------|------|------|
| 监听端口 | 6881 | BT 监听端口（支持随机端口） |
| 最大连接数 | 500 | 全局连接上限 |
| 每任务最大连接 | 100 | 单任务上限 |
| 上传槽 | 8 / 4 | 全局 / 每任务 unchoke 槽 |
| 协议 | TCP+UDP | TCP / 仅UDP |
| 代理 | 无 | **只支持 SOCKS5**（老实说：SOCKS4/HTTP 设置会存但永远不会启用代理——引擎只实现 SOCKS5） |

#### 域名解析

| 设置 | 默认 | 含义 |
|------|------|------|
| 使用内置解析器 | 开 | 用内置递归解析器（从根迭代、QNAME 最小化、0x20、有签名链时校验 DNSSEC）解析 Tracker / DHT 引导域名，不再无条件相信系统解析器 |
| 上游解析器 | Cloudflare / AliDNS / DNSPod | 每行一个 `协议://IP[/路径][#TLS名称]`；`https://1.1.1.1/dns-query#cloudflare-dns.com`（DoH）、`tls://1.1.1.1#one.one.one.one`（DoT）、或裸 `223.5.5.5`（明文）。留空＝从根迭代，不向任何第三方发问 |
| 启用 IPv6 | 开 | 查询 AAAA，并让双栈 UDP 套接字承载 IPv6 的 DHT 与 UDP Tracker |
| 允许局域网 Web 种子 | 开 | 允许解析到 RFC1918 / ULA 的抓取（NAS 场景）；回环与云元数据地址在两种设置下都拒绝 |

加密上游的证书按平台信任库校验，且只在能读到信任库时启用——`courierust`
没有隐式信任库，否则就只剩"不校验"这一条路。各平台的具体差异见
[`docs/architecture.md`](./docs/architecture.md)。

#### 速度

| 设置 | 默认 | 含义 |
|------|------|------|
| 全局 下载/上传 限速 | 0（不限） | KiB/s 硬顶（容差表见引擎深潜） |
| 备用限速 | 10 MiB / 3 MiB | 备用限速档 |
| 定时切换 | 关 | 按星期 + 时间段自动切备用限速 |

#### BitTorrent

| 设置 | 默认 | 含义 |
|------|------|------|
| DHT / PEX / LSD | 开 | 发现机制（BEP-5 / BEP-14；**PEX 开关仅保存**——引擎没有 PEX 配置项） |
| UPnP / NAT-PMP | 开 | 双协议端口映射（两种协议都真映射） |
| 每任务最大 Peers | 80 | 单任务 swarm 上限 |
| **请求管线** | 32 | 每 peer 在途 16 KiB 块数（≤512 KiB/peer） |
| **请求超时** | 20 s | 单请求超时，管线感知 |
| **最大连续超时** | 8 | 连续 8 个空窗口才封禁 |
| 冲突分块 | 32 | endgame 重复请求数 |
| 使用默认 Tracker | 开 | 每个种子追加社区兜底 tracker |
| 反吸血 | 开 | 指纹 + 信誉 + 硬封禁 |
| **屏蔽吸血客户端** | 开 | 迅雷/闪电/FlashGet 等永远不会被 unchoke |
| 额外 Tracker | 空 | 每行一个 URL，变更时推给运行中种子 |
| **Tracker 订阅** | 内置社区列表 | 每行一个 trackerslist 地址 + 更新间隔（小时，0 = 只手动）；取回的地址写入 `subscribedTrackers`，新地址自动加入所有旧任务与新任务 |
| 磁盘缓存 | 256 MiB | 写回缓存预算 |
| **磁盘分配** | 稀疏 | 关闭（随写增长）/ **稀疏**（预留，推荐）/ 完整（零填充） |
| 做种/下载槽 | 8 / 8 | unchoke 槽 |
| 优化间隔 / 冷落超时 / 重新choke | 30 s / 60 s / 10 s | leech 调度 |
| 调度权重 α/β/γ/δ | 8/2/1/64 | 效用调度器（typebit 专属） |
| 内容布局 | 原始 | 原始 / 子目录 / 无子目录 |

#### WebUI

| 设置 | 默认 | 含义 |
|------|------|------|
| 全部 | — | **路线图。** 设置会持久化，但内置 WebUI 服务器还没上线。不装。 |

#### 高级

| 设置 | 默认 | 含义 |
|------|------|------|
| 磁盘缓存 | 256 MiB | BitTorrent 缓存的别名 |
| 保存续传间隔 | 60 s | 续传数据写盘节奏 |
| OS 缓存 | 开 | 系统级缓冲 |
| 套接字积压 / 发送缓冲水位 | 30 / 512 KiB | socket 调优 |
| bdecode 深度/令牌上限 | 100 / 50M | 元数据解析护栏 |
| 最大并发 HTTP announce | 50 | tracker 工作线程上限 |
| Tracker 失败阈值 / 重试间隔 / 次数 | 3 / 30 s / 5 | tracker 退避策略 |
| Peer 周转间隔 | 5 s | 换人节奏 |

#### RSS

| 设置 | 默认 | 含义 |
|------|------|------|
| 刷新间隔 | 30 min | 源刷新节奏 |
| 每源最大条目 | 50 | 每源保留条数 |
| 智能剧集过滤 | 开 | 剧集感知过滤辅助 |

---

## 引擎深潜

下面是真正有意思的部分。以下全部是 `typebit` 0.1.8 真的在做的事——没有
PPT 数字，只有实际行为。

### 限速是硬顶，不是建议

全局 + 单任务限速由引擎侧令牌桶执行。上传路径的规格长得像一张税率表：

| 上限      | 允许超出 |
|-----------|----------|
| 100 KiB/s | 10%      |
| 200 KiB/s | 9%（之后每 +50 KiB −0.5%） |
| 1 MiB/s   | 1%（下限） |

突发容量由容差推导（`burst = rate × tol / 200`，钳制在 [4 KiB, 1 MiB]），
于是**任意一秒的窗口都卡在 `上限 × (1 + tol)` 以内**。

```mermaid
flowchart LR
    A[上传 tick] --> B{"global_up 桶<br/>available(now)?"}
    B -->|"有，且取 min(单会话配额)"| C[发出最多 N 字节]
    C --> D[从 global_up 和会话配额同时扣]
    B -->|无| E[等着——不突发不装死]
    F[global_down 每 tick 排干] --> G[DiskCache 共享 tick_down_budget]
    G --> H[fill_pipeline 从共享预算扣]
    H --> I[单个下载者吃满整条管道<br/>空闲任务一分钱不浪费]
```

旧代码把每 tick 配额按活动任务数均分——只有一个下载者时永远跑不满上限，
还动不动先冲个 10 倍尖峰再装死。现在全局桶是唯一权威，空闲任务一分钱都
不浪费。

### 下载不再自尽

"连上做种者下得飞快，过一会儿突然断；重启就好"——这个经典故障引擎侧有
三个根因：

1. **过严的块校验在误杀健康做种者。** `on_piece` 原来强制 16 KiB 整块对齐；
   不合规的客户端一碰就触发协议违规 → 断开 → 封禁。封禁在内存里，重启即清
   ——*这*就是"重启就好"的真相。现在校验放宽（落在分块内、≤16 KiB 即可），
   组装走字节级精确。
2. **扁平超时烧死深管线。** 一个 peer 有 32 个在途请求时，最后一个合法地
   要等前面的先被服务。超时改成管线感知（`timeout + 在途数 × 250 ms`），
   封禁门槛也够高：一个 peer 必须连续 8 个完整窗口**什么都不交付**才挨封。
3. **群耗尽后干等。** 失去最后一个 Ready peer，现在 5 秒内强制刷新
   （tracker announce + DHT lookup）。

### 16 MiB 分块终于能过完整性检查

组装是字节级精确的——会话按分块精确追踪已收到的字节区间：

```mermaid
stateDiagram-v2
    [*] --> Assembling: on_piece（字节区间）
    Assembling --> Assembling: 追加新字节（恶意 peer 改不了别人的字节）
    Assembling --> DataComplete: total() >= 分块长度
    DataComplete --> Verified: SHA-1/SHA-256 匹配
    DataComplete --> Assembling: 哈希失败 → 区间重置，重下
    Verified --> [*]: 置 have 位，落盘
```

短块、错位块、重复块永远留不下"零填充的洞"——那正是大分块（16 MiB）任务
以前卡 99% 的根因。断点续传只信已校验的 `have` 位，残缺分块一律重下。

### Tracker 有教养

- **BEP-12 层级**保留（`TrackerState.tier`）并按序 announce：种子自带的
  tracker 优先，配置附加与社区兜底靠后。
- **BEP-15 UDP** 快速重传——丢一个 connect 包每 3 秒重试（最多 3 次）才
  轮换端点并计失败：

```mermaid
sequenceDiagram
    participant S as Session
    participant T as UDP tracker
    S->>T: Connect（事务 id）
    Note over S,T: 包丢了
    S->>T: Connect 重试（3 s）
    S->>T: Connect 重试（3 s）
    S->>T: Connect 重试（3 s）
    Note over S,T: 3 次 → 轮换端点 + 计一次失败
    S->>T: Announce
    T-->>S: 响应 / 错误（ACTION_ERROR 解析）
    S->>S: 应用 peers，重置 attempts
```

  一个丢包不再白等 15 秒。v2/hybrid 种子按截断的 20 字节 hash announce。
- **BEP-27 UTF-8** 文件名（`name.utf-8` / `path.utf-8`）+ **BEP-10** 扩展
  握手（ut_metadata / ut_pex）都通。

### 多线程 + 多镜像

- **多线程**：每个 peer 一条独立管线（32 × 16 KiB 在途），由磁盘缓存预算
  推导的**全局**在途窗口兜底（`预算 / 16 KiB`，≥ 64 块）。swarm 给多少
  peer 就拉多少。
- **多镜像**：`url-list` 镜像同时用——最多 4 个块并行在途，跨镜像轮询，
  每个镜像独立失败账本：

```mermaid
flowchart LR
    A[drive_webseed] --> B{槽位 < 4?}
    B --> C[轮询选下一个镜像<br/>跳过 fails >= 上限的]
    C --> D[发异步 HTTP range 任务]
    D --> E[on_range_job_done → 应用块]
    E --> F{全部镜像都失败？}
    F -->|否| B
    F -->|是| G[退避 retry_at = now + backoff]
    G --> B
```

  镜像 A 不等镜像 B，死镜像退避而不是卡死分块。（早期版本每 tick 重试死
  镜像，DNS 打到每秒两次。修了，测了，完了。）

### 发现机制，全都要

- **DHT（BEP-5）** 带自举韧性：首启无引导节点也不死，按真实节点数重自举
  （占位节点不算数），3 节点扇出刷新路由表，持久化最多 160 个节点。
- **PEX** —— 与 peer 交换 `added` / `added.f` / `dropped` / `added6` /
  `p` / `p6`；外网端口需 ≥2 个不同 peer 见证确认，然后通告出去让别人
  能连到你。
- **LSD（BEP-14）** —— 从**专用 6771 端口**收发（协议这么写是有原因的），
  带防放大（同一源 10 秒内只单播回一次）和活跃集合变化时的突发广播：

```mermaid
flowchart TD
    A[局域网邻居] -->|BT-SEARCH 组播 :6771| B[专用 6771 socket]
    B --> C{解析 OK 且不是自己 cookie 且端口非 0？}
    C -->|否| D[丢弃]
    C -->|是| E{源被限流？}
    E -->|是| D
    E -->|否| F[单播回包 + enqueue_peer<br/>DiscoverySource::Lsd]
    F --> G[lsd_peers++]
```

  同路由下 `sent` 涨而 `recv` 是 0？那是 AP 隔离或防火墙，不是代码。
  Android 在进程启动时、任何 socket 存在之前就抢 WiFi 组播锁——OEM 的
  ROM 不会事后补开组播。
- **uTP（BEP-29）+ LEDBAT** 引擎里有，但现实中 peer 基本都协商明文 TCP，
  线上就是明文。实话实说。

### 反吸血，真的咬人

指纹识别（`-XX####-` BEP-20 解析）+ 信誉 + 硬封禁。已知吸血客户端
（迅雷、闪电、FlashGet…）**永远不会被 unchoke**——它们还能给我们上传，
但永远别想从我们这里下载。三道护栏保证公平：**LAN/LSD 邻居永不身份阻断**
（人家就在你家路由器底下）、新连接有 probation 宽限窗口、贡献超过
free-ride 地板的一律不按指纹阻断。

### 引擎底下的其余东西

- 选择下载：`Vec<Option<DiskId>>` 文件表——跳过的文件**从不打开**，没有
  `.part`、不预分配；运行时重新勾选下个 tick 补开；新跳过分块的已发请求
  立即发 `Cancel` 收回。
- 磁力 **hold 门**：在优先级提交前会话拒绝选任何分块——你还在选文件的时候
  磁力不可能偷偷囤几个 GB。
- 并行分块校验（`VerifyPool`）、每 poll tick 一次批量原生快照（而不是 N
  次 JNI 调用）、`catch_unwind` 包裹的引擎 tick——panic 会向 UI 上报恢复，
  而不是默默死掉。
- Windows 防火墙规则（含 UDP 6771 给 LSD）+ ICS 共享控制、UPnP + NAT-PMP
  双协议端口映射。

---

## 踩过的坑

- **"重启就好"** 其实是一张内存封禁名单。修它的过程比我读过的任何协议
  文档都更能教会我 BitTorrent 客户端行为。
- **16 MiB 分块卡 99%** 不是哈希问题——是一个半块被标成"已收"，而那个洞
  永远没人去填。字节级区间修好了它。
- **跑几分钟后 DHT 显示 0** 是引擎线程 panic 死了，UI 还在轮询一具尸体。
  现在引擎每个 tick 都包在 `catch_unwind` 里，恢复后会向 UI 上报。
- **同路由下 LSD 互相看不见**：三个原因叠在一起——Windows 防火墙没放行
  UDP 6771、announce 走了错误的 socket、Android 组播锁拿得太晚。全修了，
  「统计 → 网络统计」里的计数器是活证据。

## 老实交代

- 不是每个 qBittorrent 计数器都有。引擎拿不到真实值的字段，UI 显示 `—`，
  绝不编一个出来。
- uTP 实现了但多数 peer 选 TCP；"加密模式"设置只是存着——线上是明文。
- WebUI 走的是**明文 HTTP**：它的定位是局域网内使用，或交给反向代理终止
  TLS。没有内建证书管理，`启用 HTTPS` 只是把 Cookie 标成 `Secure`。
- Unraid 的 Community Applications 要求 OSI 认可的开源许可证，而本项目是
  PolyForm Perimeter 1.0.0，所以模板只提供手动安装（`packaging/unraid`），
  没有往 CA 提交。
- 搜索引擎是抓页面不是调 API——站点改 HTML 后某个引擎可能一直 BLOCKED，
  直到正则跟上。

## 从源码构建

前置：JDK 17、Android SDK + NDK（编 `.so`）、Rust stable（1.95+，含
`aarch64-linux-android` / `armv7-linux-androideabi` / `x86_64-linux-android`
targets。x86 也编，因为模拟器）。

```powershell
# 1. 桌面 DLL → composeApp/src/desktopMain/resources/native/typebit_native.dll
.\scripts\build-desktop.ps1

# 2. Android → composeApp/src/androidMain/jniLibs/<abi>/libtypebit_native.so
$env:ANDROID_NDK_HOME = "C:\Users\you\AppData\Local\Android\Sdk\ndk\..."
.\scripts\build-android.ps1

# 3. Kotlin
gradlew.bat :composeApp:run             # 桌面
gradlew.bat :composeApp:assembleDebug   # Android APK
gradlew.bat :composeApp:createDistributable
```

**发版之前**，先证明原生库和 Kotlin 桥完全对得上：

```powershell
.\scripts\verify-native.ps1
```

它会比对 `JNI_ABI`（Rust）和 `EXPECTED_BRIDGE_ABI`（Kotlin），并检查
`NativeBridge.kt` 里每一个 `expect fun native*` 是否真的由 DLL 和四个
`.so` 全部导出。省掉这一步的后果不是编译失败，而是：**装上去能开，一调用
引擎就整进程消失，连异常都没有**——JNI 不带类型信息，签名漂移不是链接错误
而是 SIGSEGV。任何原生签名或 JSON 契约的改动，必须在同一次提交里把
`JNI_ABI` 加一。

`build-android.ps1` 还会通过 `scripts/lib/gradle-env.ps1` 调 Gradle：如果
`%USERPROFILE%\.gradle\gradle.properties` 里留着指向 **本机回环地址** 的代理
（Clash/V2Ray 的遗留配置，代理一关，Gradle 每次构建都会 "Connection refused"），
它会临时清掉、构建完再逐字节还原。代理确实在跑的话，设
`TYPEBIT_KEEP_GRADLE_PROXY=1` 保留原样。

桌面 DLL 打在 app jar 里（`native/typebit_native.dll`）；Android 的 `.so`
在 `jniLibs`。你在发行目录里 `dir /s /b | findstr dll` 找不到 DLL？它在
jar 里面，正常。这个坑每个版本都会坑一个人。每个版本。

要往下改的话，这几处值得先看：

| 路径 | 是什么 |
|------|--------|
| `native/src/make_torrent.rs` | 制作种子实现（BEP-3/12/27），带进度、取消和自测 |
| `native/src/jni_glue.rs` | JNI 表面层，保持薄：只做解析和默认值 |
| `composeApp/src/commonMain/kotlin/com/typebit/engine/TorrentEngine.kt` | 崩溃安全门面：调用失败退化成默认值，而不是把异常扔进协程 |
| `composeApp/src/desktopMain/kotlin/com/typebit/webui/WebUiServer.kt` | NAS 端的产品面（同一个 store，换成 HTTP） |
| `packaging/` | Docker / Unraid / fnOS 打包，基于 `scripts/build-linux.sh` 产物（fnOS 的 arm64 载荷由 `packaging/fnos/build-fpk.sh` 交叉组装） |

## 文档与许可证

- `docs/architecture.md` —— 完整 JNI 协议、线程模型、tick 主循环。
- `docs/settings.md` —— 每个设置：驱动什么、哪些只是存储型。
- `NOTICE.md` —— 第三方声明。

**许可证**：应用 + 桥 + 引擎——**PolyForm Perimeter 1.0.0**（见 `LICENSE`、
`NOTICE.md`）。与 qBittorrent、BitComet、libtorrent 无关——他们干他们的，
我们干我们的。

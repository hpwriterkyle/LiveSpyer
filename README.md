# LiveSpyer

Java 21 直播桌面客户端。支持 Bilibili 多房间观看、二维码登录和发送后断开的弹幕连接，也支持直接粘贴网页获得的 m3u8 地址。

## 播放引擎

当前使用 **Edge WebView2 + hls.js 1.5.15**，视频显示在 Java 软件窗口内，**不再依赖或加载 VLC**。

Java 负责房间解析、账号会话、网络请求、播放列表转发和界面。一个小型 Windows 窗口宿主负责将 WebView2 嵌入 Swing Canvas；该宿主不实现平台业务、不登录账号，也不打开直播间网页。hls.js 随应用分发，播放时不从外部脚本 CDN 加载。

当前浏览器播放器支持 **Windows x64、JDK 21、.NET Framework 4.8 和 Microsoft Edge WebView2 Runtime**。本机已安装 WebView2 并完成真实播放验证。其他系统尚未提供浏览器窗口宿主。

## 运行与分发

Windows PowerShell：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\gradlew.bat run
```

首次构建会自动从 NuGet 下载固定版本的 WebView2 SDK（1.0.2903.40），校验 SHA-256，并使用 Windows 自带的 .NET Framework 编译器生成窗口宿主。无需安装 .NET SDK 或 VLC。SDK 和生成文件只放在忽略的 `build/` 内。

如通过 IDE 直接运行 `Main`，首次先执行：

```powershell
.\gradlew.bat buildBrowserHost
```

生成分发目录：

```powershell
.\gradlew.bat installDist
.\build\install\LiveSpyer\bin\LiveSpyer.bat
```

分发包含 Java 依赖、hls.js、浏览器窗口宿主、WebView2 SDK DLL 和相应许可证；不捆绑 JDK 或系统 WebView2 Runtime。宿主基于应用 JAR 的位置查找，不依赖终端当前目录。

若没有 WebView2，可从 [微软 WebView2 下载页面](https://developer.microsoft.com/microsoft-edge/webview2/) 安装 Evergreen Runtime。旧版的 VLC 便携目录可以保留，但当前程序不会使用它。

## 两种播放方式

**按房间号观看**

1. 选择哔哩哔哩，输入数字房间号或直播间 URL，点击“添加直播间”。
2. 可先扫码登录，也可尝试播放允许游客访问的公开直播流。
3. 每个标签页独立播放；切换标签不会停止其他房间。支持音量、静音、停止、全屏以及重新取流。
4. 登录后发送弹幕。收到服务端成功响应才清空输入，失败保留输入；保守限制为 20 个字符，同一账号两次请求至少间隔 3 秒。

**直接播放 m3u8**

1. 点击“粘贴 m3u8”，粘贴完整 HTTP/HTTPS 地址，保留原有签名参数。
2. Bilibili 来源默认 Referer 为 `https://live.bilibili.com/`；其他来源可按实际需要修改或清空。
3. 点击确定后在独立标签页播放。支持音量、静音、停止和全屏。
4. 直链可能过期，届时需要重新复制。直链没有绑定直播间号，因此不提供二维码登录或弹幕发送操作，也无法自动重新解析原网站的播放地址。

当前直链转发仅使用必要的 Referer 和 User-Agent，不支持需要额外账号 Cookie 或 DRM 的媒体。

## 播放与互动相互独立

```text
房间号 → 解析 HLS 地址 ─┐
粘贴 m3u8 ────────────┴→ Java 本地媒体转发 → hls.js → WebView2 视频画面

房间弹幕发送：
disconnected → connecting → 服务端鉴权成功 → connected
             → HTTP 提交弹幕 → 成功或失败均关闭连接 → disconnected
```

播放器只加载本地播放页面，不加载 Bilibili 直播间网页，不建立弹幕 WebSocket，不运行房间互动心跳。账号凭据仅保存在 Java 内存中，不传入浏览器或 CDN。退出登录清除本地会话，已经取得的媒体地址继续播放。

`disconnected` 表示本客户端互动连接已关闭。平台在线名单、入场事件和缓存由服务端决定，关闭连接不保证账号立即从名单消失，也不撤销已产生的记录。带签名的媒体 URL 不等于匿名访问。

直链标签和房间标签都通过随机令牌保护的 127.0.0.1 端口获取媒体。Java 转发层处理相对地址、主/子播放列表、初始化分片、密钥 URL、Range 和 LL-HLS 的重载参数，原样转发媒体字节，**不转码**。它不接受任意代理目标参数，不将账号 Cookie 转发到媒体源，也不允许浏览器导航到直播间或其他网页。

关闭标签会停止媒体转发、结束该标签的浏览器宿主，并尝试清理其独立临时浏览器目录。该目录不复用用户的 Edge 登录资料。

## 低延迟策略

- 优先选择 H.264 fMP4 HLS，无兼容流时回退 TS。分片时长由平台决定。
- hls.js 启用 `lowLatencyMode`，目标同步距离为 1 个分片，超过 3 个分片的距离时追赶；允许最高 1.1 倍速追赶。
- 前向缓冲目标 4 秒、最大 8 秒，已播历史缓冲不保留。前向缓冲上限不等于额外延迟。
- 去掉了旧 VLC 自适应流模块的内部缓冲限制，但普通 HLS 仍受完整分片发布时间、CDN 和网络影响，不能承诺总延迟低于 300ms。
- 平台实际提供 LL-HLS 的部分分片时，播放器与转发层保留相应能力；普通 m3u8 不会因打开低延迟开关自动变成 LL-HLS。
- 较激进的同步距离更容易受网络抖动影响。播放错误最多自动重试两次，之后手动重新取流或重新粘贴地址。弹幕发送不自动重试。

## 扩展接口

| 接口/类 | 职责 |
| --- | --- |
| `LivePlatform` | 平台标识、房间解析、取流、登录及互动连接工厂 |
| `QrLogin` | 二维码生成及单次轮询 |
| `RoomInteraction` | 等待鉴权的 connect、发送及幂等 close |
| `LivePlayer` | 播放、停止、音量、静音及释放 |
| `BrowserPlayer` | 浏览器宿主与媒体转发服务的生命周期 |
| `BrowserMediaServer` | 本地静态资源、播放状态和媒体转发 |
| `RoomSession` | 每个房间的串行任务与互动连接生命周期 |
| `PlatformRegistry` | 注册及加载平台扩展 |
| `DirectStreamPlatform` | 不关联账号或房间互动的用户直链 |

新增平台通过 `PlatformRegistry.register()` 注册，也可在扩展 JAR 中提供：

```text
META-INF/services/top.vrilhyc.applications.platform.LivePlatform
```

ServiceLoader 实现需要公开无参构造函数和唯一平台 ID。取流不得创建常驻互动连接；connect 返回前须收到鉴权成功；close 必须幂等并处理连接中的取消；网络调用应有超时；日志不得包含 Cookie、Token 或完整带签名 URL。

## 验证

```powershell
.\gradlew.bat build
.\gradlew.bat test
# 可选只读联网检查，不登录真实账号或发送弹幕
.\gradlew.bat liveSmoke -PliveRoom=6
```

默认测试包括平台接口、房间生命周期、HLS 重写、媒体字节转发、Range、来源请求头、凭据隔离、过期播放请求隔离和直链输入校验。联网测试检查公开 HLS、二维码生成及未扫码轮询。

本次已验证 Windows 内嵌浏览器的真实房间画面、通过“粘贴 m3u8”对话框播放、全屏切换、静音命令和停止。实际音量听感、扫码确认成功及真实弹幕发送仍需人工/账号联调；未测量与主播时钟对齐的绝对延迟。

Bilibili 风控返回 `-352`/`-412` 时会显示错误并关闭临时连接，签名或扫码登录不保证解除风控。

架构参考本机 `E:\save\webs\Demo Projects\Demo Project\demo` 的 Swing、扫码服务和 WebSocket 实现；未读取或复用其账号配置、浏览器会话和凭据。

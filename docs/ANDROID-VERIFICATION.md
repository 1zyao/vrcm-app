# 安卓端验证记录（真机 / MuMu x86_64）

这份文档记录在安卓设备上**实测**到的东西，不是设计意图。每条都带时间戳或命令，便于以后复查。
场景：手机端改为 VRCX 前端之后，核心（Rust）在应用进程内跑，Kotlin 通过回环 HTTP 与它通信。

设备：MuMu 虚拟机 x86_64，包名 `io.github.vrcmteam.vrcm.debug`，adb `127.0.0.1:16384`。

---

## 1. 实时 WebSocket 曾经是死的，但没人发现

### 症状只在 iOS 暴露

安装含核心的 iOS 包后，应用启动约 4 秒即崩溃。三个 `.ips` 的崩溃栈完全一致（06:31:19 / 06:31:22 / 06:31:27）：

```
exception  EXC_CRASH / SIGABRT   "abort() called"
faultingThread 51（tokio worker）

abort
std::panicking::panic_handler
core::panicking::panic
core::option::expect              ← 某个 .expect() 拿到 None
rustls::client::...
tokio_tungstenite::...            ← 实时 WebSocket 的 TLS 握手
vrcx_0_outbound_...               ← 出站适配器（实时连接）
tokio runtime worker
```

### 根因：两个 crypto provider 同时开启

```
workspace Cargo.toml   rustls = { default-features = false, features = ["ring"] }
vrchat-client          tokio-tungstenite = { features = ["rustls-tls-webpki-roots"] }
                       → 把 rustls 以默认特性拉进来（aws_lc_rs）
```

`cargo tree -e features -i rustls` 对**安卓和 iOS 两个目标**的结果完全一致，都是：

```
ring, aws_lc_rs, default, logging, std, tls12 ...
```

rustls 0.23.45 的 `get_default_or_install_from_crate_features()`（`src/crypto/mod.rs:243`）：

```rust
let provider = Self::from_crate_features().expect(
  "Could not automatically determine the process-level CryptoProvider from Rustls crate features. ...");
```

而 `from_crate_features()` 只在**恰好启用一个** provider 时返回 `Some`。两个都开 → `None` → panic。

`crates/headless/src/main.rs:202` 早就有对应修复（`init_tls_crypto_provider()`），手机核心入口没有。

### 为什么安卓没崩：panic 策略不同

```toml
# Cargo.toml
[profile.release]
panic = "abort"    ← panic 直接终止进程

[profile.dev]
# 未覆盖，默认 unwind
```

```powershell
# scripts/build-core.ps1（安卓用 dev）
cargo build --target $target -p vrcx-0-mobile-android
# .github/workflows/ios-build.yml（iOS 用 release）
cargo build --release --target aarch64-apple-ios -p vrcx-0-mobile-core --lib
```

```
arm64-v8a\libvrcx_0_mobile.so    110.3 MB   ← dev
x86_64\libvrcx_0_mobile.so       146.2 MB   ← dev
```

于是同一个 bug：

| | 策略 | 结果 |
| --- | --- | --- |
| 安卓 | unwind | panic 只杀掉那个 tokio 任务，进程活着，**实时连接静默失效** |
| iOS | abort | 整个 app 终止，一眼看见 |

也就是说：**安卓上实时推送一直是坏的**，只是界面数据另有来源（REST 轮询 + 本地库），看起来正常。

### 修复与验证

修复在 `crates/mobile-core/src/host.rs` 的 `build_host` 开头：

```rust
fn install_tls_crypto_provider() {
    let _ = rustls::crypto::ring::default_provider().install_default();
}
```

选 `ring` 而不是 `aws_lc_rs`：`mobile_tls_client_config()` 的 HTTPS 用的就是它，而且避开 aws-lc-sys 在 Apple 平台上的构建问题。

修复后安卓实测（`adb logcat`）：

```
06:49:48  VrcxCore: event connected
06:50:05  VrcxCore: event backendRuntimeTelemetry: {"detail":"connected","kind":"wsStatus",...}
06:51:50  VrcxCore: event realtimeWsStatus: {"at":"2026-11-18T22:51:50Z",...}
```

`detail: "connected"` 就是证据：修复前这条永远不会出现。

---

## 2. 核心推送从来没进过 UI

`CoreEvents`（`GET /api/events`）在改造前**只在 `MainActivity` 里被 collect 到 logcat**：

```kotlin
// 改造前
CoreEvents(core.baseUrl).stream().collect { event ->
    Log.i("VrcxCore", "event ${event.name}: ...")
}
```

而 `CoreEvents` 自己的注释写着代价：

> Without a subscriber the phone has no live updates at all: commands only ever answer requests,
> they never announce changes.

结果是：核心算好的好友在线、房间变化、通知全部被丢弃。动态页只在「切账号 / 换筛选 / 手动刷新」时重查，
打开之后就不再动。

### 改造

新增 `composeApp/src/commonMain/kotlin/mobilecore/CoreEventBridge.kt`：

```
核心事件流 → SharedFlowCentre.coreEvents（MutableSharedFlow<String>，只转发事件名）
```

订阅方：

| 位置 | 响应的事件名 |
| --- | --- |
| `FriendActivityTimelineModel` | `realtimeProjectionSync`、`realtimeFriendProjection`、`realtimeUserProjection`、`realtimeCurrentUserProjection`、`favoritesChanged` |
| `NotificationCenterModel` | `notification`、`notification-v2`、`notification-v2-update`、`response-notification`、`see-notification`、`hide-notification`、`clear-notification` |

**只传名字、不传 payload**：核心推的是 projection（已算好的状态），与 `AccountWebSocketEvent` 那套
pipeline 事件结构不同，硬套会得到语义不对的事件。订阅者把名字当「有变化」的信号，自己去拉。

另有 60 秒保底轮询（`REFRESH_INTERVAL_MS`），只查本地库，不发网络请求：
推送丢失、重连、后台期间的变化都靠它兜住。

### 实测（`adb logcat`，3.5 小时窗口）

```
[Feed] periodic reload                                    214 次   （≈1 次/分钟，无漏拍）
[Feed] reload by core event                               520 次
   ├── realtimeFriendProjection                            425
   ├── realtimeCurrentUserProjection                        45
   ├── realtimeUserProjection                               44
   └── realtimeProjectionSync                                6
```

时间交错的样子（同一秒内先轮询再推送）：

```
08:23:38  [Feed] periodic reload
08:23:39  [Feed] reload by core event: realtimeFriendProjection
```

核心侧 `baselineRevision` 一路递增（2 → 98），页面跟着刷新，最新一条从 06:47 追到 08:13 无需手动下拉。

9.5 小时连续运行（同一 pid 47992）：

```
FATAL EXCEPTION / ANR / panic / abort    0 条
WS 断开重连                               15:19:38 error → 15:19:45 connected
                                          15:59:34 error → 15:59:41 connected
```

重连都在 5–7 秒内恢复，说明实时链路的自愈是通的。

---

## 3. 设置页的「清除缓存」不含核心数据

`SettingsScreen.kt` 的 `PrivacyDataSections`：

```kotlin
val diskCache = imageLoader.diskCache                       // Coil 的磁盘缓存
value = diskCache?.let { "${size.bytesToMb()}/${it.maxSize.bytesToMb()}MB" }   // 只显示这个

onClick = {
    diskCache?.clear()
    accountCacheManager.clearAll(includeStatistics = false)
    groupProfileCacheStore.clearAll()
    worldProfileCacheStore.clearAll()
    decorationTemplateCacheDao.clearAll()
    meetupCardAssetStore.clearDecorationCache()
}
```

六项**全是 Kotlin 侧的**，没有调用任何核心命令。文案也说清了范围：

```
settingsCacheDescription = "清理图片和资料缓存，保留身份牌配置与相册照片。"
```

### 核心的数据在哪（设备实测）

```
files/VRCX-0/                          562.8 MB
├── VRCX-0.sqlite3                     557.6 MB   ← 数据库
├── VRCX-0.sqlite3-wal                   4.8 MB
├── diagnostics                          0.3 MB   （只有 user-api-fields.jsonl，无 error-log.txt）
├── secrets.key / runtime.lock            ~0
└── ImageCache                            0 MB    ← 空
```

布局来自 `crates/platform/src/app_paths.rs::from_app_data`：

```rust
db_file:           app_data.join(PROFILE_DB_FILE),
config_file:       app_data.join(PROFILE_CONFIG_FILE),
image_cache:       app_data.join("ImageCache"),
screenshot_thumbs: app_data.join("ScreenshotThumbs"),
```

`ImageCache` 为空是因为手机上的图片走**应用自己的 Coil**（`KtorNetworkFetcherFactory`），核心从不下载图片。
所以「图片缓存」这一项统计 Coil 是准确的。

而那 557 MB 是**数据不是缓存**：好友动态历史、通知、世界/用户缓存表、同步状态、feed 游标。
删掉等于清空历史，不应该混进「清除缓存」按钮。

### 桌面端有、手机端没有的命令

```
app__database_maintenance_table_sizes_get    ← 查各表占用
app__user_tables_ensure                      ← 确保用户表存在
```

两个都没在 `crates/mobile-host/src/lib.rs` 注册，所以手机上**看不到** 557 MB 花在哪，也无法针对性清理。

---

## 4. 进程生命周期（安卓）

内核跟着应用进程活。进程被划掉，核心一起结束：

```
$ adb shell pidof io.github.vrcmteam.vrcm.debug
（空）→ 核心不在跑，行为回落到「进入页面才拉一次」
```

需要常驻得做前台服务，目前没有。9.5 小时不重启的前提是进程一直活着（前台或后台存活）。

---

## 记录这些的原因

三处都是「看起来正常、其实降级」的问题，而且都用了同一种排查方式定位：

1. **不假设**：iOS 崩溃靠符号化 Rust 栈，安卓「没崩」靠 profile 与实际 `.so` 大小对照；
2. **看证据**：`detail:"connected"`、`[Feed]` 计数、`du -sk` 的字节数，都是可复现的输出；
3. **区分缓存与数据**：557 MB 不是「缓存」，把它当缓存清掉是数据丢失，不是优化。

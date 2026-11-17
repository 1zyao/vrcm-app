# FORK-DELTA — 手机端 fork 相对 VRCX 主线的全部差异

这份清单的存在理由只有一个：**让每次跟进上游变成照着单子核对，而不是重新回忆。**

基线：`D:\vrcx0\mobile` 的 `mobile` 分支 vs 主线 `sync-main`。
数据抓取方式见文末「怎么重新生成」。

---

## 一、fork 独有的 crate（主线没有，合并时自动保留，不冲突）

```
crates/command-server     447 行   HTTP 命令总线（POST /api/invoke + GET /api/events）
crates/mobile-host       2291 行   72 条命令的注册与实现
crates/mobile-core        320 行   JNI 入口 + 宿主装配 + cdylib + 探针二进制
crates/social-runtime     194 行   社交实时运行时
```

这四块是手机端存在的全部理由，**改它们不需要顾虑主线**。

## 二、命令面

```
72 条命令
70 条与主线同名（97%）
 2 条自造：app__debug_emit_event（调试）、app__web_execute（透传）
```

命令名与入参形状一律照上游 `bindings.ts` 的定义，不自己发明。新增命令时先查主线有没有同名命令。

## 三、修改了的主线自有文件（合并时唯一要关心的地方）

共 18 个文件 / +397 行。每一条都有理由，分三类。

### A. 移动端平台适配（主线不可能有）

| 文件 | 行 | 为什么 |
|---|---|---|
| `platform/src/machine_key.rs` | +83 | `machine-uid` 在 Android/iOS 无实现；改用随机密钥文件（0600，存应用私有目录）。不是硬件绑定，但确实是「秘密」。 |
| `vrchat-client/src/web_client.rs` | +22 | reqwest 0.13 在 Android 依赖 `rustls-platform-verifier`，需要 JNI `Env`+`Context`；改用 webpki-roots 内置根证书。代价：不信任用户安装的 CA。 |
| `host-desktop/src/clipboard.rs` | +19 | `arboard` 在移动端不编译，加 cfg 门 + stub。 |
| `host-desktop/src/shell_actions.rs` | +11 | `trash` crate 同上。 |
| `platform/src/app_paths.rs` | +18 | 把「读 `/proc/self/cmdline` 猜包名」换成 `set_app_data_dir_override()`：目录由平台（Kotlin 的 `filesDir`）提供，不再猜。 |
| 5 个 `Cargo.toml` | +33 | 移动端依赖声明（`jni`、`getrandom`、specta 等）。 |

### B. 宿主图装配（必须在建图的地方，搬走会造出第二个 realtime 引擎）

| 文件 | 行 | 为什么 |
|---|---|---|
| `composition/src/state/runtime_host_state.rs` | +131 | `social` / `vrchat_remote` 两个字段的装配 + 4 个访问器。facade 需要 `realtime_runtime`，而它由 `realtime_stores`/`realtime_transport`/`backend_status`/`local_game_context` 等私有件构成；搬到手机端只能复制一份 → **两条 WebSocket 连接**。 |
| `composition/src/context.rs` | +26 | 只保留 `saved_auth_snapshot()`（1 条命令用）。原有 5 个登录方法已删——它们是主线 `auth_session.rs` 的错误副本（传空 transition sink，登录成功也不会激活 scope）。 |
| `application/src/remote/vrchat_facade.rs` | +8 | 只剩一个 `pub`（`execute`）；9 个 VRChat 读取方法已移到 `mobile-host/src/vrchat_calls.rs`。 |

### C. 测试与其它

| 文件 | 行 | 为什么 |
|---|---|---|
| `persistence/.../recent_lookup_tests.rs` | +31 | 我们加的一个真实测试（最近访问的数据来源）。 |
| `application-activity/activity_warmup.rs` | +24 | `try_update` → CAS 循环。**待确认**：如果主线工具链支持 `try_update`，这条可以还原。 |
| `composition/{lib,state/mod}.rs`、`tests/...` | +4 | 模块声明与一处测试修正。 |

## 四、留在 composition 的 4 个访问器（不是遗漏，是权衡）

| 访问器 | 用它的命令 | 为什么不搬 |
|---|---|---|
| `vrchat_remote()` | 27 条 | 见 B 类：构造依赖私有 realtime 件 |
| `social()` | 2 条（收藏基线、好友名单基线） | 同上（依赖 authenticated runtime + realtime） |
| `saved_auth_snapshot()` | 1 条 | context 上的方法，无公开路径 |
| `remote_sync_restart()` | 1 条（`sync__configure`） | 持有私有 `remote_sync_host` |

桌面上这四个都有等价物（在 `runtime-host-desktop` 的 state 上），所以它们**不是发明，是把桌面已有的东西在 fork 里复现**。

## 五、跟进上游的标准流程

```bash
cd D:\vrcx0\mobile
git fetch mainline sync-main
git branch -f mobile-backup-$(date +%Y%m%d) HEAD
git merge mainline/sync-main

# 冲突预期：只在「三」列出的 18 个文件里
# 处理规则：
#   A 类（平台适配）→ 保留我们的，因为上游不会有
#   B 类（宿主编排）→ 逐处看 diff，通常「上游 + 我们的访问器」
#   其余 → 取上游
```

合并后必查：

```bash
# 1) 命令数是否还是 72（少一条就是漏注册）
cargo test -p vrcx-0-mobile-host        # 里面有 assert_eq!(registry.len(), 72)

# 2) 契约字段有没有改名（手机端靠 JSON 字段名）
#    sync__status / sync__bootstrap_progress / app__instance_history_query 的字段
cargo test -p vrcx-0-contracts

# 3) 编译 + 全量测试
cargo check --workspace
cargo test -p vrcx-0-persistence -p vrcx-0-composition -p vrcx-0-mobile-host

# 4) 真机
powershell -File scripts\build-core.ps1 -Abi x86_64 -CoreRepo D:\vrcx0\mobile
```

## 六、怎么重新生成这份清单

```bash
cd D:\vrcx0\mobile

# 总量
git diff --stat mainline/sync-main HEAD -- crates/ \
    ':!crates/command-server' ':!crates/mobile-host' \
    ':!crates/mobile-core' ':!crates/social-runtime'

# 逐文件状态（A=新增 M=修改 D=删除）
git diff --name-status mainline/sync-main HEAD -- crates/ \
    ':!crates/command-server' ':!crates/mobile-host' \
    ':!crates/mobile-core' ':!crates/social-runtime'

# fork 独有 crate 的行数
Get-ChildItem crates\mobile-host -Recurse -Include *.rs | Get-Content | Measure-Object -Line
```

## 七、历史（这轮削减的轨迹）

```
刚合并完            20 文件 / +1087
换命令 + 删适配      19 文件 /  +697
facade + 探针移除    17 文件 /  +473
删 5 个死登录方法     17 文件 /  +399
现在                16 文件 /  +381   （追平主线 54dec0076 后不变）
```

削减过程中被移走或删除的东西：

- remote-sync 功能整块（约 9000 行）→ 改由上游提供
- `app__game_log_sessions_query` → 改用上游的 `app__instance_history_query`
- 15 个域封装 → `mobile-host/src/state_ext.rs`（扩展 trait）
- 9 个 VRChat 读取 → `mobile-host/src/vrchat_calls.rs`
- 探针命令行 → `crates/mobile-core/src/bin/probe.rs`
- 5 个登录方法（错误的副本）→ 用主线 `auth_session.rs`
- `/proc/self/cmdline` 猜包名 → `set_app_data_dir_override()`

---

## 八、iOS：现状、待办，以及一个已排除的误解

### 现状

```
composeApp/build.gradle.kts   iosArm64() / iosSimulatorArm64()   已声明
composeApp/src/iosMain/       21 个 .kt                            有源集
iosApp/                       完整的 Xcode 工程 + Info.plist        有

但：
  mobilecore/VrcxCoreBridge.kt (iosMain)   = error("The VRCX-0 core runs inside the phone app only")
  isVrcxCoreReady()                        = false
  crates/mobile-core 的 FFI                 只有 JNI，没有 C ABI
  crate-type                                cdylib（iOS 需要 staticlib）
  build-core.ps1                            只认 Android 两个 ABI
```

结论：**iOS 上跑的是没有核心的形态**，所有数据入口直接抛错。这不是遗漏，是还没做。

### 为什么原版 VRCM 不需要这些

`5b30797`（加载 core）之前的 iOS 侧没有任何桥接代码，因为原版**根本没有原生核心**：所有平台共用
`commonMain/kotlin/network/` 里的 Ktor 客户端直接打 `api.vrchat.cloud`。iOS 编的是同一套 Kotlin，
不需要 JNI、也不需要 C ABI。

改造后数据来自进程内的 Rust 核心（本地库 + 回环命令总线），才有"怎么在 iOS 上启动它"这个问题。

### 需要做的六件事，以及各自的进度

```
Rust 侧
  1. mobile-core 加一个 extern "C" 入口（职责等同 Android 的 JNI 那个）   ✅ 已完成
  2. crate-type 加 staticlib                                              ✅ 已完成
  3. 在 macOS 上编 aarch64-apple-ios → vrcx_core.a                        ⬜ 必须 macOS + Xcode

Kotlin 侧
  4. iosMain 的 VrcxCoreBridge 换成真实实现（cinterop 声明已就位）          ⬜ 等第 3 步
  5. Info.plist 加 NSAllowsLocalNetworking                                 ✅ 已完成

iOS 工程
  6. app 进后台/回前台的重连处理（iOS 没有前台服务保活）                    ⬜ 等第 3 步
```

**只需要一个 C 函数**，因为 core 的协议是回环 HTTP：启动一次之后，`VrcxCoreClient` 里所有命令
调用都走 `127.0.0.1`，`commonMain` 一行都不用改。

#### 已完成的部分

Rust（`crates/mobile-core/src/lib.rs`）：

```rust
#[no_mangle]
pub unsafe extern "C" fn vrcx_core_start(
    data_dir: *const c_char,
    device_name: *const c_char,
) -> *mut c_char        // {"port":<u16>,"token":"…"} 或 {"error":"…"}

#[no_mangle]
pub unsafe extern "C" fn vrcx_core_free(value: *mut c_char)
```

`crate-type = ["cdylib", "staticlib", "rlib"]`。

Kotlin 侧的准备（`composeApp/src/nativeInterop/cinterop/`）：

```
vrcx_core.h     手写的 C 头（cargo 不产头文件），与 Rust 侧签名必须一致
vrcx_core.def   cinterop 定义，含 iOS 需要的系统 framework 链接参数
build.gradle.kts 里加了 cinterop 块，但**只在 vrcx_core.a 存在时才注册**，
                 所以 Android/桌面构建、以及还没有静态库的 iOS 构建都不会因此失败
```

iOS 工程（`iosApp/iosApp/Info.plist`）：

```xml
<key>NSAppTransportSecurity</key>
<dict>
    <key>NSAllowsLocalNetworking</key>
    <true/>
</dict>
```

#### 在 Windows 上已经验证到哪一步

```
cargo check -p vrcx-0-mobile-core                                    ✅
cargo test  -p vrcx-0-mobile-core --lib                               ✅ 5 个测试
    the_c_entry_point_starts_a_reachable_core    调 C 入口，拿到 port/token，
                                                 并真的连上那个端口
    the_c_entry_point_reports_a_null_data_dir    null 输入返回 {"error":…} 而不是崩溃
脚本构建 Android 核心 build-core.ps1 -Abi x86_64                       ✅
    → libvrcx_0_mobile.so 142.1 MB（cdylib 未受 staticlib 影响）
    → libvrcx_0_mobile.a（static，533 MB，iOS 会用同一条路径）
gradle 配置阶段 + Android 编译                                          ✅（cinterop 守卫有效）
```

也就是说：**除了"在 macOS 上把静态库编出来"这一步，其余已经做完并验证。**

#### 剩下的 macOS 步骤（照抄即可）

```bash
# 1. 装 target
rustup target add aarch64-apple-ios aarch64-apple-ios-sim

# 2. 编静态库（在 D:\vrcx0\mobile 的等价克隆上）
cargo build --target aarch64-apple-ios --release -p vrcx-0-mobile-core
cargo build --target aarch64-apple-ios-sim --release -p vrcx-0-mobile-core   # 模拟器

# 3. 放进 KMP 工程（cinterop 会自动生效，因为守卫检测的就是这个文件）
cp target/aarch64-apple-ios/release/libvrcx_0_mobile.a \
   composeApp/src/nativeInterop/cinterop/vrcx_core.a

# 4. 换掉 iosMain 的 stub（composeApp/src/iosMain/kotlin/mobilecore/VrcxCoreBridge.kt）
#    现在是 error("The VRCX-0 core runs inside the phone app only")
```

第 4 步要写的实现大致是：

```kotlin
// 用 cinterop 生成的包名：io.github.vrcmteam.vrcm.core.cinterop
@OptIn(ExperimentalForeignApi::class)
private fun startCore(dataDir: String, deviceName: String): String =
    memScoped {
        val json = vrcx_core_start(dataDir.cstr.ptr, deviceName.cstr.ptr)
        try { json?.toKString() ?: error("core returned null") }
        finally { vrcx_core_free(json) }
    }
// dataDir = NSSearchPathForDirectoriesInDomains(.applicationSupportDirectory, ...).first
```

### 已排除的误解：iOS 会不会"不能回环"

不会。我们的拓扑是**进程内自连**：

```
VRCM.app（一个进程）
  ├─ Rust 核心 bind 127.0.0.1:<随机端口>
  └─ Kotlin 客户端连同一个端口
```

既没跨 app，也没碰系统服务，沙箱不拦。

容易被拿来类比的 SideStore 是**另一回事**。它的 README 写得很明确：

> SideStore resolves apps with your personal development certificate, and then uses a
> **specially designed VPN** in order to trick iOS into installing them.
>
> By leveraging a custom-built App Store app with additional entitlements (**LocalDevVPN**) to create
> the VPN tunnel, it allows SideStore to take advantage of **Jitterbug**'s **loopback method** without
> requiring a paid developer account.
>
> **Minimuxer** is a lockdown muxer that can run inside iOS's sandbox. It replicates Apple's **usbmuxd**
> protocol to "discover" devices to interface with LocalDevVPN on-device.

关键差别：

| | SideStore | 我们 |
|---|---|---|
| 连的是什么 | 设备自己的 **usbmuxd / lockdown 系统服务** | 自己进程里刚起的 listener |
| 沙箱是否禁止 | 是 → 需要 VPN + 特殊 entitlements | 否 |
| 需要的组件 | NetworkExtension、LocalDevVPN、minimuxer | 无 |

所以 iOS 上**不需要** VPN、NetworkExtension 或付费开发者账号，只需要 ATS 放行本地网络
（`NSAllowsLocalNetworking`，比 `NSAllowsArbitraryLoads` 安全，只放行本地）。

### 需要留意的一个真风险

如果将来把核心放进 **App Extension**（分享扩展、后台刷新），那就变成跨进程，会和 SideStore 撞同一类
沙箱限制。届时才需要考虑 App Groups（只解决数据）或 NetworkExtension（解决 socket）。

### 验收顺序（有 macOS 之后）

1. 在 iOS app 里起一个 http server 监听 `127.0.0.1`，再自己请求它 —— 先证明回环成立
2. 编出 `libvrcx_0_mobile.a`，xcframework 进 Xcode
3. `vrcx_core_start(dataDir, deviceName)` 返回 `{port, token}`，Kotlin 侧能连通
4. 调一条命令（`sync__status`）确认整条链路
5. 再补 iOS 特有权限（相册、剪贴板、Keychain）

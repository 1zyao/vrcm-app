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
现在                18 文件 /  +397   （多出的 1 个文件是 composition/context.rs 重新计入）
```

削减过程中被移走或删除的东西：

- remote-sync 功能整块（约 9000 行）→ 改由上游提供
- `app__game_log_sessions_query` → 改用上游的 `app__instance_history_query`
- 15 个域封装 → `mobile-host/src/state_ext.rs`（扩展 trait）
- 9 个 VRChat 读取 → `mobile-host/src/vrchat_calls.rs`
- 探针命令行 → `crates/mobile-core/src/bin/probe.rs`
- 5 个登录方法（错误的副本）→ 用主线 `auth_session.rs`
- `/proc/self/cmdline` 猜包名 → `set_app_data_dir_override()`

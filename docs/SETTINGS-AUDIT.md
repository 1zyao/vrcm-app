# 设置面审计：哪些主线设置能移植到手机，哪些不能

判据（2）要求"主线已通过后端命令承载、而手机端仍是本地或只读的设置项，逐个接上"。
这份文档记录**逐个查证的结果**，包括结论是不该接的那些 —— 否则下次还要重查一遍。

## 查证方法

1. 读主线 `src-tauri/src/commands/**` 得到命令签名与命令体。
2. 看命令体里调的是 `runtime_host().<方法>` 还是直接调某个 crate 的函数。
3. 去 `crates/` 里找那个方法的**定义位置**：`composition`（手机也构建，可移植）还是
   `runtime-host-desktop`（桌面专属）。
4. 再问一句：手机端**有没有这个表面**。

第 3 步和第 4 步必须分开。我第一版把这些混在一起，把两个 composition 里的项误标成 desktop，
下面这张表是逐条核实后的结果。

---

## 已接上（可移植 + 手机有对应界面）

| 设置 | 命令 | 依据 |
|---|---|---|
| 同步目标 / 开关 / 间隔 / 采集优先级 / 测试连接 | `sync__configure`、`sync__status`、`sync__get_connection`、`sync__test_connection`、`sync__trigger_now` | 主线 `commands/local/sync.rs`；字段集照 `SettingsSyncTab` |
| 每表保留行数 / 搜索扫描上限 | `app__config_list_values` + `app__config_set_values` + `app__config_remove_value` | 主线 `TableLimitsDialog` → `setTableLimitsPreference` → 同一组命令 |

`app__config_remove_value` 本轮补齐并验证：设 `probeTempKey` → remove 返回 **1**；再删不存在的键
返回 **0**。至此主线那组 config 三件套在手机上齐了。

## 不该接

| 主线命令 | 后端落点 | 手机为什么不该接 |
|---|---|---|
| `app__notification_activity_filters_get/set` | **composition**（`notification_activity_filter_profiles`，可移植） | 它管的是 7 个**表面**：wrist / vr / hmd / desktop / webhook / tts / widget。电话上没有 VR 手腕、HMD、TTS、桌面小组件 —— 技术上能读写，但摆出来是一排影响不到任何东西的开关 |
| `app__profile_backup_set_settings` 等 | **composition**（`profile_backup` 句柄，可移植） | 备份目标与保留策略服务于桌面文件系统；手机没有备份落点 |
| `app__background_image_configure` / `app__community_theme_configure` | **application**（`community_theme::configure_*`，可移植） | 是桌面窗口的背景图与社区主题。手机有自己的主题色体系，接过来会是两套并行且互不相干的主题 |
| `app__proxy_settings_test` | `runtime_host_desktop::test_proxy_connectivity` | 测的是**桌面进程**的出网。手机的出网在 Ktor / 系统层，改这里不影响手机 |
| `app__feed_logger_settings_get/save/clear` | `runtime-host-desktop::feed_logger` | 整套是桌面录制服务（设备登录 / webhook / 回填历史） |
| `app__vrchat_auth_config_get/refresh` | `runtime-host-desktop`（`vrchat_config()`） | 桌面持有的 API 配置缓存 |
| `app__chatbox_config_set` | `runtime-host-desktop`（`chatbox()`） | 桌面聊天框 |
| `app__notification_do_not_disturb_mode_set` | `runtime-host-desktop` | 勿扰作用于桌面的通知投递面 |

## 手机端设置为什么大多是本地 DAO

`读取剪贴板` / `语言` / `主题色` / `通知开关` / `后台监测` 是**设备自身的偏好**：
手机的语言不该改桌面的语言，手机的剪贴板权限对桌面无意义。留在本地是正确设计，不是"没接"。

判断标准只有一句：**改这个值，是否应该影响另一台设备上的行为**。是 → 走 config 命令；否 → 留在本地。

## 通用键的边界

主线 `ConfigKeys` 有约 380 个键，绝大多数是桌面 UI 状态（侧栏折叠、托盘、覆盖层位置、QR 面板）。
手机现在三件套齐全，**任何**键都能读写 —— 但只应暴露"跨设备该一致"的键，否则设置页会变成
一串对手机无意义的开关。

---

## 结论

按"可移植 + 手机有对应界面"这条线，设置面接完了：

```
同步：连接五输入 / TLS / 明文 / 间隔 / 采集优先级 / 测试连接 / 启用开关
数据表上限：每表保留行数 / 搜索扫描上限
配置读写：list / set / remove（通用底座）
```

其余主线设置命令都服务于桌面专属表面。再往下接只会产出**看着像设置、实际不起作用**的条目，
那正是本目标要避免的"自造形状"。

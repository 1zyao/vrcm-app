# 命令迁移台账（network/api → 核心语义命令）

这份表记录手机端把「Kotlin 自己发 VRChat 请求」换成「调核心命令」的进度，以及**每一处没有迁移的原因**。
没有原因的那几行才是待办；写了原因的是结论。

统计口径：`network/api/**` 下每个 Api 类里 `client.get/post/put/delete/submitFormWithBinaryData` 的出现次数
（"原始调用"），与 `vrcxCoreClient()` 的出现次数（"核心调用"）。方法内分页/序列化代码不算。

## 当前数字

| 指标 | 值 |
| --- | --- |
| 核心侧已注册命令 | 110 |
| 原始调用 | 46 |
| 核心调用 | 74 |
| 零原始调用的 Api 类 | `AvatarModerationApi`、`EconomyApi`、`GroupsApi`、`InventoryApi` |

会话开始时核心只有 75 条命令、原始调用 110+ 处。

## 逐类状态

| Api 类 | 原始 | 核心 | 未迁移的方法 |
| --- | --- | --- | --- |
| `UsersApi` | 10 | 9 | `updateUserInfo` `addTags` `removeTags` `getMutualFriends` `getPlayerInteractionSnapshot` `removePlayerInteractionOverride` `createPlayerInteractionOverride` `isUserBlocked` `blockUser` `unblockUser` |
| `FileApi` | 6 | 2 | `findImageFileLocal` `getAvatarGalleryFiles` `uploadFile` `uploadImageFile` `uploadAvatarGalleryImage` `getFileInfo` |
| `AuthApi` | 5 | 1 | `deviceAuthInfo` `approveDevice` `userRes` `verify` `isAuthed` |
| `FriendsApi` | 3 | 1 | `sendFriendRequest` `deleteFriendRequest` `unfriend` |
| `PlayerChatboxModerationApi` | 3 | 0 | `getForTarget` `moderate` `remove` |
| `PlayerModerationApi` | 3 | 1 | `moderate` `remove` |
| `WorldsApi` | 2 | 8 | `getWorldPublishStatus` `getRecentWorlds` |
| `AvatarsApi` | 2 | 9 | `updateAvatar` `getImpostorQueueStats` |
| `FeedbackApi` | 2 | 0 | `reportConfig` `submitReport` |
| `NotificationApi` | 2 | 4 | `acceptFriendRequest` `clearAllNotifications` |
| `ProfileAppearanceApi` | 1 | 2 | `update` |
| `PrintsApi` | 1 | 3 | `uploadPrint` |
| `InstancesApi` | 1 | 3 | `closeStatus` |
| `FavoriteApi` | 1 | 7 | `getFavoriteGroupsByType`（列表页仍在用，baseline 已覆盖同一数据） |
| `InviteApi` | 1 | 7 | `resetInviteMessage` |
| `VrchatStatusApi` | 1 | 0 | `fetchStatus` |
| `GitHubApi` | 1 | 0 | `latestRelease` |
| `AppStoreApi` | 1 | 0 | `lookup` |

## 未迁移的五个原因（按类归档）

### A. 上游没有这个命令，且不该自造

| 位置 | 为什么 |
| --- | --- |
| `PlayerModerationApi` 3 处、`PlayerChatboxModerationApi` 3 处、`UsersApi` 的 5 处 player-moderation | 桌面端只暴露 `app__get_vrchat_user_moderation` / `app__set_vrchat_user_moderation`，而它们是**本地数据库**读写（`local_player_moderations`），不代理 VRChat 的 `playermoderations` API。自己造 `app__vrchat_player_moderation_*` 就是发明命令名。 |
| `NotificationApi.clearAllNotifications` | 桌面端没有「全部清空」命令。 |
| `InviteApi.resetInviteMessage` | 桌面端只有 `app__vrchat_tools_invite_message_edit`，没有 reset（DELETE）。 |
| `WorldsApi.getWorldPublishStatus` | 桌面端没有查询发布状态的命令。 |
| `AvatarsApi.getImpostorQueueStats` | 桌面端没有 impostor 队列统计命令。 |
| `InstancesApi.closeStatus` | 桌面端没有 `GET instances/{location}` 只读 `closed` 标志的命令。 |

### B. 依赖 realtime 运行时，迁移会侵入共享 crate

| 位置 | 为什么 |
| --- | --- |
| `FriendsApi` 3 处 | 桌面端是 `app__social_friend_request_send` / `_cancel` / `app__social_unfriend`，走 `SocialMutationDeps`，其中含 `&Arc<RealtimeHostRuntime>`。手机端没有暴露它（这也是 `composition` 里保留 `social()` 的原因之一）。 |
| `NotificationApi.acceptFriendRequest` | 同上（`app__social_friend_request_notification_accept`）。 |
| `UsersApi.getMutualFriends` | `app__user_mutual_friends_list_get` 走 `runtime-host-desktop` 的 `local_data()`，内部同样需要 realtime。 |

### C. 形状不兼容 —— 迁过去会被核心拒绝或改变行为

| 位置 | 为什么 |
| --- | --- |
| `AvatarsApi.updateAvatar` | 核心 `AvatarUpdateRequest` 是 `deny_unknown_fields` 且没有 `imageUrl`；App 的 `AvatarUpdateData` 带 `imageUrl`。 |
| `ProfileAppearanceApi.update` | 核心 `CurrentUserProfileUpdateRequest` 是 `deny_unknown_fields` 且没有 `languages`；App 的 `UpdateProfileData` 带 `languages`。 |
| `FeedbackApi.submitReport` | 桌面端 `app__vrchat_tools_user_report` 硬编码 `type: "user"`；这个调用点按 `target.type` 上报（user / group / world / avatar），迁过去会缩窄功能。 |
| `UsersApi.updateUserInfo` / `addTags` / `removeTags` | 上游命令是**当前用户专用**（`app__vrchat_current_user_tags_*`），而这些方法接受任意 `userId`。 |
| `FileApi.getAvatarGalleryFiles` | 需要 `galleryId`，核心的 `MediaFileListParams` 没有这个字段。 |

### D. 不是 VRChat API

| 位置 | 为什么 |
| --- | --- |
| `GitHubApi.latestRelease` | 版本检查，打的是 GitHub。 |
| `AppStoreApi.lookup` | 应用商店查询。 |
| `VrchatStatusApi.fetchStatus` | VRChat 公开状态页。 |
| `AuthApi` 5 处 | 登录流程本身（device auth、校验、`userRes`）；核心内部持有一套，但手机端的登录 UI 仍走这些端点。 |

### E. multipart 上传

| 位置 | 为什么 |
| --- | --- |
| `FileApi.uploadFile` / `uploadImageFile` / `uploadAvatarGalleryImage` | multipart 请求体，核心侧要专门的媒体上传管线（桌面有 `LocalMediaUploadAdapter`）。桌面端有 `app__vrchat_media_asset_upload` / `_gallery_image_upload` / `_avatar_gallery_image_upload` 等命令，但形状未逐一核对，尚未接入。 |
| `PrintsApi.uploadPrint` | **已核对，形状不兼容**：核心的 `print_upload_input` 只把 `PrintUploadParams { note, timestamp }` 作为 post data，而手机端这一处还发 `worldId` / `worldName`。迁过去会让拍立得失去世界关联。要接入需先给 `PrintUploadParams` 补这两个字段（改共享 crate）。 |
| `FileApi.findImageFileLocal` / `getFileInfo` | 文件元信息读取，桌面端对应的是 `app__file_metadata_get`（本地文件），形状不同。 |

## 关于"最终删除 network/api/*"

盘点后的结论：**这个终点需要调整**。上面 A/B/D 三类共 34 处不是一个能"做完"的清单——

- A 类是上游没有命令，删掉这一层就得在别处重新发明它们；
- B 类要暴露 realtime 运行时，与"最小化侵入共享 crate"的目标直接冲突；
- D 类与 VRChat 无关，留在这里或搬走都不改变"核心所有权"的问题。

可行的终点是：**`network/api/*` 保留为 DTO + 命令调用层，但原始 HTTP 调用降到 0（或只剩 E 类的 multipart）**。
换句话说，`HttpClient` 从这些类里消失，而不是这些类消失。

## 过程教训（都是这轮踩出来的）

1. **Kotlin 多行结构不要用脚本整段重写。** 一次 `GroupsApi` 的整体替换把大括号结构弄坏，71 个编译错误、文件末尾 `brace depth = -1`；回滚后用单次 `edit` 重写一个方法，一次通过。
2. **探针二进制必须重建。** 旧探针会对新注册的命令回 "not available on this transport"，看起来像注册失败。
3. **会校验的核心命令比只转发的值钱。** `world_save` 的 `params.id must match worldId`、`instance_create` 缺 `canRequestInvite`、`group_member_props_set` 的 `unknown field`，都在编译期之外的第一次调用就把形状问题暴露出来——其中 `instance_create` 那一条如果没被探针撞到，用户点"创建房间"就会每次都失败。

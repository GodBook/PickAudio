# v1.5.2 验证记录

验证日期：2026-11-12。版本 1.5.2/code 21，数据库 v5。使用电脑连接的 vivo V2452A（Android 16/API 36），未使用本机模拟器。

## 问题证据与修复

- 原手机仍选用 `builtin_aggregate`，没有切换到新版 QQ 解析器。升级后实际数据库选择为 `builtin_qq`。
- 原扫描记录《夜曲》《晴天》均为 60,029 ms、960,887 字节，且没有在线身份；对应 MediaStore 文件已删除。刷新实际移除 38 首已确认失效的本地曲库条目，46 首变为 8 首。
- 其余 7 个备份待关联条目仍有在线来源，另有 1 首在线歌曲，均保留。刷新保留歌曲记录、收藏和歌单，不删除音频文件。后续将重新下载的完整《夜曲》加入曲库。
- QQ 标准线路在初查时返回上游 110001，而同一 MID 的高品质线路能提供完整录音。修复增加有时限的同曲高品质回退，并按独立曲目时长检查 QQ 固定码率 MP3 的完整资源大小。

## 本地构建与自动检查

- JVM 单元测试：96 项，0 失败、0 错误，包括同 MID 高品质回退、有效 MP3 文件头的 60 秒片段拦截。
- Debug、validation、Release 和对应测试包构建成功。Release lint：0 错误、69 警告。Room schema 2–5 校验通过。
- 实机 validation：`LibraryRefreshTest`、`LibraryRefreshUiTest`、`BuiltinStellarWaveTest`、`QqAutomaticRepairTest`、`QqPlaybackCompletenessTest`、`QqDownloadCompletenessTest`、`AutomaticDownloadRepairUiTest`，36/36 通过，65.916 秒。
- 实机 Debug：刷新 UI、自动修复 UI、播放完整性和自动重下共 18/18 通过，32.715 秒。
- 另一次 validation 核心清理、迁移和下载完整性检查 18/18 通过，3.092 秒。
- 清理用例覆盖已删除/仍存在文件、离线目录、授权失败、备份提示、在线来源、保留收藏、幂等刷新，以及真实 MediaStore 删除行；UI 用例覆盖 onResume 和刷新按钮。

初轮测试保留了失败记录：vivo 在测试清理阶段对同一已删除 URI 再次 delete 抛出 SecurityException，已修复测试夹具；UI 测试改用协程兼容的 awaitIdle。初轮部分测试被系统冻结而中断，不能作为通过证据。后续仅对独立测试进程临时解除冻结并通过标准 MAIN/LAUNCHER Intent 启动页面，未修改正式应用行为或测试断言。全部最终用例完成。

## 实际 QQ 音源与解码验证

使用独立测试包、原 QQ MID 和独立曲目时长，通过实际新版 `builtin_qq` 解析、生产 PlaybackCoordinator/DownloadCoordinator 和真实音频解码验证。每首均检查从 55 秒连续播放到 66 秒、跳转至 75%、播放至末尾，以及下载后完整数据帧和时长校验。

| 原曲 | QQ MID | 播放时长 | 后半段位置 | 下载结果 |
| --- | --- | --- | --- | --- |
| 周杰伦《夜曲》 | 001zMQr71F1Qo8 | 226,873 ms | 169,809 ms | 9,075,745 字节，MP3/320 kbps，PACKETS |
| 周杰伦《晴天》 | 0039MnYb0qxYhV | 269,792 ms | 202,083 ms | 6,519,764 字节，AAC/约 191 kbps，269,816 ms，PACKETS |
| 周杰伦《兰亭序》 | 00128N3r2SYKMF | 254,026 ms | 190,093 ms | 6,133,907 字节，AAC/约 191 kbps，PACKETS |

三首真实网络用例均通过。上游在初查时《兰亭序》所有已测线路均返回 110001，实机最终测试时已恢复；可用性是当时的结果，不能保证服务以后始终可用。实际音质根据解码结果展示，不将请求音质当作实际音质。

## 正式 APK 与原应用升级

- 沿用 v1.5.1 的新签名覆盖升级原 `com.pickaudio`，没有卸载、清空数据或对正式数据库运行破坏性的测试夹具。
- 实际搜索 QQ 原版《夜曲》，正式混淆 Release 在线播放到 180,701 ms，MediaSession 为 PLAYING，完整缓冲至约 226,905 ms。
- 在正式版界面选择高品质下载完成，并加入曲库。文件 `Music/PickAudio/周杰伦 - 夜曲 [320k].mp3`：9,075,745 字节，ffprobe 226.827506 秒、320,000 bps；ffmpeg 全文件解码无错误。下载文件 SHA-256：`526e673bbeb84d32d86f166781bea68c483cfa22137d8eb7ff5c2fc41b543260`。
- 正式版下载后的本地播放亦跳转到约 168,821 ms 并保持 PLAYING，播放器显示 MP3/320 kbps、3:46。
- `verify-release.ps1` 通过包名、递增版本号、生产证书、双 ABI、ZIP/ELF 16 KiB 对齐校验。

产物：`PickAudio-v1.5.2-release.apk`，8,340,765 字节。

- APK SHA-256：`ace28614ef7451c0739187d8e3c1a3ef5043718ce041afc4eac78461603ba4e3`
- 证书 SHA-256：`c8728b534a21f89310b8edddfccc1b1f2f1a77dac0104e53871f2e4ed9a8a5ea`

手机收尾已移除两个独立测试包和两个中断测试遗留的夹具文件，恢复充电常亮设置为原值 0；保留正式版及重新下载的完整《夜曲》。

原手机备份、数据库快照、完整命令日志和临时网络用例保存在本地忽略的 `app/build/qq-reproduction`；不发布私人备份、音频或凭据。最终发布地址：[v1.5.2](https://github.com/GodBook/PickAudio/releases/tag/v1.5.2)。

# QQ 搜索播放修复记录

日期：2026-10-08。源码版本：1.4.2/code 15。

## 已确认的问题

内置星澜 QQ 官方线路原先使用歌曲 MID 拼接音频文件名，没有使用已由搜索结果保存并由 LX 兼容层提供的媒体 MID。两个编号并不总是相同。实际 QQ 搜索样本 Faded / Alan Walker 的歌曲 MID 为 `002NkERn2LNVI4`，媒体 MID 为 `002GPT5r262kk1`。

在旧版 Debug APK 上运行实际内置脚本的编号回归，预期 `M500media_mid.mp3`，实际得到 `M500original_mid.mp3`，测试失败。正式 v1.4.1 在专用 API 36 模拟器的实际搜索流程中也显示 QQ 解析失败和全部 23 个后端的长错误。

此次旧版搜索复验未捕获进程闪退，不能据此认定编号错误就是用户手机闪退的唯一原因。修复针对已证实的地址错误，并补齐失败、服务退出、重试、切歌及完整前台启动时限的回归。实体手机、具体歌曲权限及厂商后台策略不属于模拟器验证范围。

## 修改

- 星澜 QQ 官方请求将媒体 MID 用于 `filename`，保留原歌曲 MID 用于 `songmid`，不更换录音。
- 音源错误仅在界面展示首行、最多 160 字的原因及重试建议；完整异常链仍由播放诊断日志记录。播放器再限制错误提示为三行，将错误及重试操作置于固定控制区，小屏和大字体下无需滚动封面即可重试。
- 服务销毁时保留 `ERROR` 和 `CHOOSE_VERSION`，不将它们覆盖为无错误的暂停状态；普通暂停仍保留音质信息。
- 内置脚本摘要发生变化时更新脚本，继续保留平台绑定、是否启用及创建时间。原作者与 MIT 注释完整保留，离线许可材料明确说明局部修改。

## 验证证据

旧版证据保存在 `app/build/qq-crash-1.4.2/`：`baseline-qq-id-test.txt` 为编号差异的失败回归，`baseline-results.xml`、`baseline-error.xml` 和 `baseline-logcat.txt` 为实际搜索及解析失败证据。

`baseline-recovery-tests.txt` 另记录旧版两项失败：320×600 dp、1.5 倍字体下重试按钮不可见，停止播放服务后 `ERROR` 被覆盖为 `PAUSED`。

修复后本地 71 项 JVM 测试通过。14 项 QQ、脚本生命周期、升级绑定及小屏控制专项全部通过（37.863 秒），覆盖实际内置脚本的媒体编号、全部后端失败后再次解析、取消和迟到回调、解析超时、服务退出后重试、完整 11 秒前台启动期限及后续本地歌曲播放。设备崩溃日志为空。Debug/Release 构建、Lint 和 Room schema 检查通过。

[GitHub 完整 CI](https://github.com/GodBook/PickAudio/actions/runs/37733064291) 对源码提交 `16d159565305ca9961a5e262b4288199f40c6644` 验证通过：Debug 与 R8 裁剪验证变体各 79 项 API 36 设备测试，失败和跳过均为零。报告保存在 `app/build/release-1.4.2/ci-debug/` 与 `ci-validation/`。验证变体保留测试需要的公开接口，不等同于实际生产包的全部重命名行为。

原签名 1.4.1/code 14 → 1.4.2/code 15 覆盖升级通过，UID、偏好文件字节、歌曲与平台标识、歌单、收藏、歌词校准、重复队列、五秒暂停进度、自定义音源、明确停用状态、本地音频和 1024 字节暂停下载均保留。数据库仍为 v5，完整性正常，无外键错误。升级后实际内置脚本摘要更新为 `be401b4fb325443001a5903b303f61541c8e265a116fb017fddff5d4a0bb3fd6`。自有夹具已清理，报告为 `app/build/release-1.4.2/official-upgrade-verification.json`。

实际原签名生产裁剪包另通过五秒暂停进度冷恢复、真实播放控件、前台服务、后台播放、重复队列自然切歌及媒体键暂停复验，报告为 `app/build/release-1.4.2/production-smoke.json`。

真实 QQ 官方接口在本次 Faded 样本的标准匿名 GET/POST 请求中均返回请求码 1000、没有可用地址；修正编号不等于解除平台权限限制。QQ 解码链路使用保持原曲身份的可控 WAV 服务验证，不宣称该真实 QQ 样本已获匿名播放权限。

## 正式包

包名 `com.pickaudio`，原证书 SHA-256 为 `1e564b554f48b6a69d80e6d76c704e8b16e9a600d2511205754839c729d4a281`。版本、单调版本号、签名、arm64-v8a/x86_64 双 ABI、ZIP 与 ELF 16 KiB 对齐检查通过。

安装包为 `PickAudio-v1.4.2-release.apk`，8,226,077 字节（约 7.85 MiB），SHA-256 为 `e88aac535edbf9bd8cf8b497916b96b818d09942ade6271ce4593aad28eb5a4c`。

代码通过 [PR #2](https://github.com/GodBook/PickAudio/pull/2) 合入 main，[v1.4.2 Release](https://github.com/GodBook/PickAudio/releases/tag/v1.4.2) 已公开发布，含原签名 APK、SHA256SUMS.txt 和版本元数据。正式资产回下载与本地签名 APK 的大小、SHA-256 及 Release 版本元数据一致。

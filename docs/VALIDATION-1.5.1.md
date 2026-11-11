# v1.5.1 QQ 音频完整性修复与验证

日期：2026-11-11（Asia/Shanghai）。目标：修复 QQ 播放和下载可能接受半首音频的问题，测试后更新 GitHub 和正式 Release。

状态：QQ 完整性校验和自动修复已实现，最终源码 `498727674c1a3da70fa6d2c0cfd3cd5836b4f9ea` 已通过本地构建、USB 真机定向回归与 CI 四组设备矩阵；正式发布等待原签名凭据。工作分支 `codex/fix-qq-complete-audio`，PR [#7](https://github.com/GodBook/PickAudio/pull/7)；原有未提交文档整理不属于本次提交。

## 已确认的行为

- 原 QQ 直连只拒绝特定试听文件名并检查文件头，没有将返回音频时长与原曲时长比较。
- 播放器将短文件正常结束视为整首结束；下载会接受内部结构自洽的短文件，并用实测短时长覆盖曲目时长。
- QQ 搜索原始元数据的 `interval` 仍可作为独立的完整时长依据；旧下载文件不删除，只在确认明显过短后标记不可用并尝试在线解析。
- 2026-11-11 当前接口标准档返回《夜曲》（MID `001zMQr71F1Qo8`）AAC/M4A，5,499,068 字节，容器声明 226.905 秒。FFmpeg 完整解码到 226.82 秒，120 秒后的 106.82 秒也可解码且有音频信号。当前接口并未重现该歌曲半首返回，不能将历史故障归因于当前这份文件。
- 同一歌曲高品质/FLAC 请求当前返回上游账号风控错误，属于音源可用性限制，不降低音质或替换其他录音来掩盖。

## 验收与证据

本地设备为 USB 小米 9 SE（`1944b301`，Android 15/API 35）。最终源码 `4987276` 的验证如下，原始日志均在被 Git 忽略的 `app/build/qq-reproduction/`。

| 验证 | 结果与证据 |
| --- | --- |
| JVM、构建与静态检查 | 94 项通过；Debug、validation 主/测试 APK、Release 构建通过；Lint 0 错误/77 警告；Room schema 2..5 通过 |
| 真机 Debug 服务与 QQ 播放回归 | 14/14，133.122 秒；`phone-service-lifecycle-debug.log` |
| 真机 validation 服务、播放、自动修复与界面 | 25/25，132.742 秒；`phone-final-validation-regression.log` |
| 真机 validation 网络中断与播放稳定性整组 | 8/8，270.797 秒；`phone-final-transport-stability.log` |
| 最终源码 CI | [run 43010220260](https://github.com/GodBook/PickAudio/actions/runs/43010220260) attempt 2：构建与 API 35/36 Debug/validation 四组均通过，各 130/130；API 35 Debug 为同源码同断言复跑通过，日志 `ci-final-debug35-rerun.log` |

在最终服务身份保护之前，含自动修复的 `613d69a` 真机完整 Debug 128/128 通过（526.293 秒，`phone-full-auto-debug-rerun.log`）；`9449638` 真机完整 validation 129/129 通过（474.767 秒，`phone-full-validation-final.log`）。这些是前序基线，不能替代上表最终源码的回归。

自动修复专项覆盖完整替换、检查指纹复用/文件变化/强制检查、原文件保留、旧状态与并发暂停/取消、失败后不循环、未知时长与个人导入排除。进入前台和播放异常通知接入真实界面；只修复应用拥有、可信原曲时长明确的已完成下载，沿用原任务、原音质、Wi-Fi 偏好与并发限制。暂停、取消和已失败任务不会自动复活，读取异常或持续变化的文件不会被武断判为不完整。

《夜曲》真实完整 AAC 样本（MID `001zMQr71F1Qo8`，5,499,068 字节，226.905 秒）经生产 PlaybackCoordinator/PCM sink 与 QQ 缓存路径，在真机通过 75% 定位、末尾定位、自然结束、完整下载与逐字节比对。Debug 用例耗时 7.800 秒（`phone-real-yequ-coordinator.log`），validation 用例 4.825 秒（`phone-real-yequ-validation.log`）。样本 SHA-256 为 `6b59c09ede0cdcc93185ccc356562f36b5737604b11dc297721b689440447e1b`，不进入源码库或发布资产。

## 失败记录与限制

- 早期服务停止/重试专项多次超时；`9449638` 的诊断捕获重试后 `PAUSED/message=null/services=[]`。`4987276` 用服务实例身份隔离创建、就绪和迟到销毁，并为旧包装器释放保留服务所有权，增加确定性回归；未放宽 20 秒断言。
- CI `613d69a` 发现 MediaProvider 生成号延迟更新导致检查前后指纹变化，现同次扫描有一次有界重查，配有确定性竞态用例；持续变化仍跳过。测试诊断中错误读取主线程 ActivityScenario.state 的字段已删除。
- 最终源码 CI 首轮 API 35 Debug 的 `PlaybackStabilityTest.exhaustedTransportRetriesReResolveAndResumeFromSavedPosition` 超时（129/130）；其他三组均通过。只重跑失败任务，同源码同断言 130/130 通过，真机对应稳定性整组 8/8 通过。报告未给出具体等待阶段，测试存在短暂恢复状态的观测窗口；不将复跑通过等同于已定位修复首轮超时。
- 真机早期完整 Debug 出现一次 Compose `performMeasureAndLayout called during measure layout` 崩溃。同机主题专项随后连续两次各 2/2，完整重跑 128/128；未宣称已修复该间歇崩溃。另一次锁屏/Dozing 导致的无可见 UI 失败不计为通过。
- 真机直接联网默认 QQ 音源的临时专项失败：初次没有默认网络/DNS；临时开启 Wi-Fi 后接口仍不可用，电脑同时间请求返回 `110001` / “cookie异常：账号被风控无法获取”。该失败与已通过的完整真实样本明确区分，上游可用性仍不受本次修复控制。

## 设备与候选包收尾

`-PisolatedTests=true` 让 Debug/validation 使用 `com.pickaudio.qqtest` 及独立测试包、Provider 和升级夹具，保护原正式版与数据。2026-11-11 已卸载两个临时测试包，确认公共测试音乐目录为空，恢复 Wi-Fi 关闭和 `stay_on_while_plugged_in=0`，重新打开原正式版。手机仅保留原 `com.pickaudio` v1.5.0；原 APK 前后 SHA-256 一致：`fe40c85bfeeda6403f162d1be31f25b4935738846a40c4b120d1a55c7b01f281`。

最终未签名 Release 候选为 `app/build/outputs/apk/release/app-release-unsigned.apk`，1.5.1/code 20，8,277,538 字节，SHA-256 `25a2904e86b017560f12379fc9f81e48cbca60f6c6cfa30ff39eeb667ccf648c`。包信息及 ZIP/ELF 16 KiB 对齐核查通过；`publishable=false`，不能作为正式升级包发布。

## 下一步

按用户要求全部改用 USB 手机继续回归。本轮新建的模拟器 `PickAudio_QQ_API36_20261111` 已关闭，其虚拟设备文件夹、注册 INI 和 SDK 中的 emulator 程序目录均送入 Windows 回收站，并查询确认可恢复；不再使用电脑模拟器。

GitHub 源码更新通过 PR [#7](https://github.com/GodBook/PickAudio/pull/7) 交付，最终补充提交仅修改本验证记录与发布说明，`app` 源码及版本配置与已测试的 `4987276` 完全相同。v1.5.1/code 20 Release 草稿 ID 为 `441512226`，尚未发布且没有安装包资产。

正式发布仍缺原签名凭据。原证书 SHA-256 为 `1e564b554f48b6a69d80e6d76c704e8b16e9a600d2511205754839c729d4a281`。`release.jks` 仍在，但旧 `app/build/release-1.5.0/sign-local.ps1` 依赖的 `app/build/release-1.4.5/sign-release.ps1` 已缺失，现有环境、凭据管理器及历史辅助脚本未找回密码。已准备签名与证书核验脚本；恢复凭据后需验证原签名覆盖升级、上传 APK/校验文件、验证公开下载。根目录 `version.json` 在实际公开前保持 1.5.0，不用新证书或未签名 APK 代替正式包。

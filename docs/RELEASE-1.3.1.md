# 拾音 PickAudio v1.3.1

发布日期：2026-10-07。内部版本号：11。安装要求：Android 16（API 36）及以上。

修复首次初始化音乐源时，后台默认配置偶尔覆盖刚选好的自定义源的问题。默认源及导入后的自动绑定现在只填充尚未配置的平台，保留用户选择和明确禁用的状态，避免解析意外走到其他线路。

沿用 v1.3.0 的播放、下载、备份、性能与安装包优化，继续使用原项目签名覆盖升级。数据库保持 v4，原有歌曲、歌单、收藏、歌词、队列及下载记录无需额外迁移。

## 验证记录

本地 61 项 JVM 单测、Debug/Release/测试 APK 构建与 Lint 已通过，Lint 为 0 错误/61 警告。新增确定性回归验证晚到默认配置不覆盖自定义选择或明确禁用，并正确填充未配置的平台。

[GitHub 完整 CI](https://github.com/GodBook/PickAudio/actions/runs/37507715988) 已通过：61 项 JVM 单测、构建、Lint、Room schema，以及 Debug/裁剪验证变体各 52 项 API 36 设备测试，均无失败或跳过。依赖锁定和严格校验保持开启。

使用原签名直接覆盖 v1.3.0，应用 UID、偏好文件字节、歌曲/歌单/队列/下载记录、自定义源与明确禁用绑定均保留；Room 保持 v4，数据库完整性正常。双 ABI、16 KiB ZIP/ELF 对齐和原证书核查通过。完整记录见 [优化执行记录](https://github.com/GodBook/PickAudio/blob/main/docs/OPTIMIZATION-EXECUTION.md)。

## 安装包与校验

- 下载：[`PickAudio-v1.3.1-release.apk`](https://github.com/GodBook/PickAudio/releases/download/v1.3.1/PickAudio-v1.3.1-release.apk)
- 版本：1.3.1（内部版本号 11），沿用原项目签名。
- 大小：8,156,279 字节（约 7.78 MiB），相对 v1.2.0 缩小 85.01%。
- SHA-256：`a66659446b049c7d5018ee38ea5c2d8213ccfd47844b3565313cc13d49b9ca8d`
- 签名证书 SHA-256：`1e564b554f48b6a69d80e6d76c704e8b16e9a600d2511205754839c729d4a281`

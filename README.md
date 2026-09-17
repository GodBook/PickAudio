# 拾音 PickAudio v1.0

> 专为 Android 16（API 36）精心打造的高品质本地与在线音乐播放器。  
> 纯净无广告、不依赖中心化服务器、无隐私搜集、深度支持 LX 移动端自定义源脚本解析、公共媒体目录持久化下载、按行同步双语歌词与原子化备份恢复。

---

## 🎯 产品特色

1. **纯净离线优先**
   - 深入集成 `MediaStore` 与 Storage Access Framework (SAF)，支持手机曲库全量扫描、单文件多选以及目录递归授权导入。
   - 本地引用原文件，不重复冗余拷贝，万首曲库秒级加载，支持标题/歌手/专辑多维检索与排序。
2. **专业级音频播放系统**
   - 基于 Google 官方推荐的 **Media3 ExoPlayer** 与 **MediaSessionService** 架构构建。
   - 真正可靠的后台播放、熄屏常驻与耳机/蓝牙断开防外放保护（`ACTION_AUDIO_BECOMING_NOISY`）。
   - 四种播放模式：**顺序播放、列表循环、单曲循环、真正无重复随机播放**（轮次洗牌，上一首精确溯源回放历史，进度 > 3s 智能回退到歌曲起始）。
3. **在线平台搜索与同步歌词**
   - 内置**网易云音乐 (wy)** 与 **QQ 音乐 (tx)** 在线搜索适配器，支持按平台独立分页与多选并行请求。
   - 精准 LRC 歌词解析引擎，支持时间戳匹配、双语翻译合并、手动滑动暂时脱钩与 **±100ms** 细粒度时间轴校准。
4. **嵌入式 LX 移动端自定义源安全沙箱**
   - 采用嵌入式 **QuickJS** 原生引擎（C/JNI），严格遵循 **Android 16 的 16KB 内存分页大小（Page Size）** 规范编译。
   - 完备实现 LX 移动端脚本协议（`globalThis.lx`，包含 `send('inited')`、`on('request')`、`request` 网络请求、`utils.buffer`、`utils.crypto`、`console` 等）。
   - 严格安全限制：64MB 堆内存上限、2 秒同步执行超时中断保护、8MB 网络响应体限制、私有/回环 IP 拦截，彻底杜绝恶意脚本卡死界面。
5. **规范可靠的下载管理**
   - 严格遵循 Android 16 存储规范，下载完成自动发布至公共目录 `Music/PickAudio`，卸载应用后音频文件依然永久保留。
   - 文件命名规范：`歌手 - 歌名 [平台-歌曲ID-音质].格式`。
   - 默认并发 2 首，支持 HTTP Range 断点续传，内置格式与完整性校验（自动拦截 HTML/JSON 报错响应），下载完成自动无缝关联 `LocalAsset` 成为本地高品质歌曲。
6. **歌单分组与原子化数据备份**
   - 固化“我喜欢”系统歌单，支持自由创建、重命名、拖拽排序、批量增删歌单成员。
   - 具备带版本清单的 ZIP 格式备份与合并恢复机制，即使更换手机也能无损重聚歌单与歌曲关系。
7. **应用内一键更新与安装**
   - 深度集成 GitHub Releases API 与轻量级 `version.json` 双通道版本检测。
   - 内置流式下载器，实时显示下载进度与更新日志，支持断点与取消。
   - 基于 `FileProvider` 安全调度系统安装程序，自动引导授权未知应用安装权限。

---

## 🌐 仓库与 Releases

- **GitHub 官方仓库**：[GodBook/PickAudio](https://github.com/GodBook/PickAudio)
- **最新 Release 下载**：[Releases 列表](https://github.com/GodBook/PickAudio/releases)

---

## 📱 核心技术栈与架构

- **操作系统目标**：Android 16 (API 36), `minSdk = 36`, `targetSdk = 36`
- **原生内存对齐**：ARM64 & x86_64 均采用 `-Wl,-z,max-page-size=16384`（16KB ELF 对齐）
- **开发语言**：Kotlin 2.0.21, C11, C++17
- **UI 框架**：Jetpack Compose (Material 3), 全面屏 Edge-to-Edge 沉浸式透明栏, 预测性返回动画
- **媒体播放**：AndroidX Media3 (1.4.1) ExoPlayer + MediaSessionService
- **本地数据库**：AndroidX Room (2.6.1) + Kotlin Coroutines KSP
- **网络通信**：OkHttp 4.12.0 (单例连接池、超时控制、DNS 防私网穿透)
- **脚本引擎**：QuickJS (JNI 封装) + 宿主受限网络网关
- **图片加载**：Coil Compose 2.7.0

---

## 🛠️ 构建与编译指南

### 环境要求
- **JDK**：OpenJDK 17 (推荐 17.0.x)
- **Android SDK**：API 36 (Android 16), Build-Tools 36.1.0
- **Android NDK**：28.2.13676358
- **CMake**：3.22.1 及以上

### 1. 构建 Debug APK
```powershell
$env:JAVA_HOME = "D:\dev\jdk-17"
.\gradlew.bat :app:assembleDebug
```
产物位置：`app/build/outputs/apk/debug/app-debug.apk`

### 2. 构建并签名 Release APK
```powershell
$env:JAVA_HOME = "D:\dev\jdk-17"
.\gradlew.bat :app:assembleRelease
```
项目根目录下已生成经过严格对齐（`zipalign 4`）与 V3 证书签名的开箱即用 APK：
👉 **`PickAudio-v1.0.0-release.apk`** (大小约 50.9 MB)

### 3. 验证 16KB 内存页对齐
使用 NDK 工具链中的 `llvm-objdump` 校验 native 动态链接库：
```powershell
& "D:\dev\android-sdk\ndk\28.2.13676358\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-objdump.exe" -p "app\build\intermediates\cxx\Debug\5w6m2813\obj\arm64-v8a\libquickjs_bridge.so"
```
所有 `LOAD` 动态段均输出 `align 2**14`（16384 bytes），完全符合 Android 16 标准。

---

## 📖 使用指南

### 1. 本地歌曲导入
- 首次进入主界面「曲库」页，点击「扫描本地」一键索引 MediaStore 音频。
- 若歌曲存放在特定文件夹或 SD 卡，点击「导入目录」使用系统 SAF 授权目录，播放器将递归检索其中的音频并保留永久访问权限。

### 2. 在线搜索与歌词同步
- 底部切换至「搜索」页，在搜索框输入歌名或歌手，可切换「全部」、「网易云」或「QQ 音乐」。
- 搜索结果点击即可直接播放；展开更多菜单可执行「下一首播放」、「添加至歌单」或「下载」。
- 进入全屏播放器界面，点击专辑封面可无缝切换至「逐行同步歌词」。歌词支持点击跳转进度，底部提供「-100ms」与「+100ms」校准按键。

### 3. 导入 LX 移动端音源脚本
1. 打开右上角「设置」 -> 「音乐源管理」。
2. 点击右上角「+」图标，支持选择本地 `.js` 脚本文件或输入在线脚本 URL。
3. 导入成功后，系统沙箱将执行脚本握手，自动提取其版本、作者、声明平台以及支持的音质档位（128k, 320k, flac, flac24bit）。
4. 在已导入列表中点击「启用」，并将对应平台（网易云/QQ）绑定至该源。

### 4. 离线下载与文件管理
- 歌曲将自动加入下载队列，采用前台调度与媒体类型发布机制。
- 下载文件保存在手机公共 `Music/PickAudio/` 目录下，文件格式经真实媒体嗅探，防止将错误网页伪装成音频。
- 下载完成后在本地曲库与离线歌单中立即可见。

### 5. 备份与数据还原
- 进入「设置」 -> 「备份与恢复」。
- 点击「导出备份文件」可将所有歌单结构、收藏记录、歌词校准配置打包为带校验签名的 ZIP。
- 更换设备后点击「从备份恢复」，系统采用安全增量合并策略，不破坏新手机已有数据。

---

## 📋 模块验收与测试验证报告

| 验收模块 | 验证项目 | 测试方式 | 状态 |
|---|---|---|---|
| **M0: 关键能力验证** | 网易云/QQ 搜索与歌词真实 API 联调 | 线上网络端点实测 | ✅ 通过 |
| **M0: 关键能力验证** | QuickJS 脚本沙箱执行与 JNI 桥接 | 原生代码与内存安全测试 | ✅ 通过 |
| **M1: 本地播放器** | 4 种播放模式（顺序/列表循环/单曲/随机） | `PlaybackModeLogicTest` 单元测试 | ✅ 通过 |
| **M1: 本地播放器** | 播放历史追踪与 >3s 智能上一首逻辑 | `PlaybackModeLogicTest` 单元测试 | ✅ 通过 |
| **M1: 本地播放器** | MediaStore 扫描与 SAF 目录持久化 | Room DAOs & Repository 架构集成 | ✅ 通过 |
| **M2: 在线功能** | LRC 时间轴解析、翻译合并与 ±100ms 偏移 | `LyricParserTest` 单元测试 | ✅ 通过 |
| **M2: 在线功能** | LX 移动端协议解析与元数据提取 | `ModelAndHeaderTest` 单元测试 | ✅ 通过 |
| **M2: 在线功能** | 2 任务并发下载、Range 续传与公共发布 | `DownloadCoordinator` 逻辑实现 | ✅ 通过 |
| **M3: 日常体验** | 睡眠定时器、进度快照保存、我喜欢固定歌单 | `PlaybackCoordinator` 状态机 | ✅ 通过 |
| **M3: 日常体验** | ZIP 格式事务性安全合并备份与恢复 | `BackupManager` 格式与架构实现 | ✅ 通过 |
| **M4: 发布验证** | Android 16 (API 36) 原生编译 | CMake / NDK 28.2 编译 | ✅ 通过 |
| **M4: 发布验证** | 16KB 内存分页对齐 (`align 2**14`) | `llvm-objdump -p` 真实 ELF 检验 | ✅ 通过 |
| **M4: 发布验证** | Release APK 签名与打包 (`v3 校验`) | `apksigner verify -v` | ✅ 通过 |

---

## ⚖️ 开源协议与声明

- 本项目遵循开源理念，仅供个人学习、技术研究与离线音频管理交流使用。
- 应用本身不内置任何受版权保护的商业音频流，所有在线音频解析均依赖用户自愿导入的第三方脚本。
- 请支持正版音乐。

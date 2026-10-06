# 优化计划执行记录

更新：2026-10-07（Asia/Shanghai）。

## 范围和授权

用户要求执行[优化审查计划](OPTIMIZATION-REVIEW-2026-10-06.md)，随后于2026-10-07要求“更新GITHUB和release”，授权本轮提交、推送、版本标签、正式签名APK上传及发布。审查文档保留需求、代码基线和验收标准，本文件为本轮唯一执行状态。历史正式发布资料保留于UX-UPGRADE.md和RELEASE-1.2.0.md。

完成O01–O24的实现、验收和正式发布；O25/O26按条件评估，不自动扩展系统支持范围。本轮工作目录D:/CHATGPT/PickAudio拾音，分支codex/optimization-plan，基线4e5da8e4d3d8938a6df0fff4b0a77da0871cac53。优化阶段以1.2.0/code 9完成，正式发布为1.3.0/code 10；原正式APK、release.jks及用户已有改动保留。

## 当前发布检查点

1.3.1/code 11补丁已于2026-10-07 02:10:42（Asia/Shanghai）[正式发布](https://github.com/GodBook/PickAudio/releases/tag/v1.3.1)，为GitHub latest，保留1.3.0历史附件。修复默认及导入后自动源绑定的先查后写竞态，默认写入改为INSERT OR IGNORE，不覆盖用户选择或明确禁用。标签指向生产代码提交5e1f5a1702ca4611dd12aa445b381af14765f6e7，[完整CI 37507715988](https://github.com/GodBook/PickAudio/actions/runs/37507715988)三项job均success，61项JVM、两组各52项设备测试无失败或跳过，Lint/schema通过。

本地补丁构建和61项JVM通过，Lint 0错误/61警告，正式包8,156,279字节，SHA-256 a66659446b049c7d5018ee38ea5c2d8213ccfd47844b3565313cc13d49b9ca8d；沿用原证书，双ABI/16KiB ZIP及ELF核验通过。专用设备原签名v1.3.0→v1.3.1覆盖升级保留UID、偏好字节、歌曲/歌单/队列/下载记录、自定义wy绑定与明确禁用tx绑定，Room v4及integrity=ok；自有夹具已清理并恢复原绑定。证据app/build/release-1.3.1/patch-upgrade-verification.json与patch-upgrade-ui.xml。公开APK完整下载HTTP 200，摘要与本地及GitHub服务端digest一致；version.json与README随本次提交同步1.3.1。本轮发布已完成，O25/O26及实体硬件验证保持独立后续范围。

以下为已发布v1.3.0的基线及历史验证。

v1.3.0/code 10已于2026-10-07 01:41:29（Asia/Shanghai）[正式发布](https://github.com/GodBook/PickAudio/releases/tag/v1.3.0)，draft=false、prerelease=false，GitHub latest已指向该版本。注释标签v1.3.0指向b88c9beeba93fa22e3d4c49ad2ebfd2fccd3c908，main已包含优化代码与CI修复。原签名证书已再次核对一致；签名沿用已有本地流程，凭据不写入源码或发布记录。

本地发布构建已完成：61项JVM全部通过，Lint为0错误/61警告。最终正式签名APK为8,156,279字节（7.78MiB），SHA-256 863b976f99f4076eb479be4a3d22824d9ab8910ba0214da1e5b209a654f5417c，证书与原正式版一致，双ABI/16KiB ZIP和ELF检查通过；相对原54,403,842字节减少85.01%。APK位于项目根PickAudio-v1.3.0-release.apk；构建与签名核查原始记录位于app/build/release-1.3.0。APK内源码标识为4172f6e；签名前已比对全部代码/资源ZIP条目与覆盖升级验收包一致，只更新源码标识及签名元数据。

正式签名覆盖升级已通过：专用emulator-5560安装原正式v1.2.0，写入v2夹具，直接以install -r升级v1.3.0。UID保留，Room为v4；歌曲、资源、在线引用、歌单、收藏时间、重复队列条目701/702及3500ms暂停快照、手动歌词/300ms校准、自定义源与绑定、设置文件字节完全保留；1024字节暂停下载保留，integrity_check=ok且无外键错误。脚本scripts/verify-release-upgrade.ps1，结果app/build/release-1.3.0/upgrade-verification.json。替换测试签名之前，原专用设备调试数据已归档至pre-upgrade-debug-data.tar，未操作个人设备。

v1.3.0发布提交的[GitHub CI 37504251297](https://github.com/GodBook/PickAudio/actions/runs/37504251297)已完整通过：61项JVM单测、Debug/Release构建、Lint、Room schema，以及Debug/validation各51项API36设备测试，均无失败或跳过。三项job均为success；设备原始日志为ci-37504251297-debug-log.txt、ci-37504251297-validation-log.txt。该版本发布前的CI、校验元数据和测试修正未改动4172f6e以来的生产代码、构建版本或裁剪规则；随后1.3.1修复另有新生产代码及验收。

首次CI发现的环境问题均已修复：明确SDK包以替代已下线的tools；托管CI使用官方仓库，避免镜像502；逐件核对官方SHA1、坐标与版本约束后补齐插件marker、父POM及BOM/module摘要，严格校验和锁定始终开启。transform-api两份JAR只有相同的25字节MANIFEST，逐条核对ZIP内容一致，POM均无依赖。官方文件保存在official-poms，13组UTP配置单独严格解析通过，记录official-utp-check.txt。

第六轮设备回归曾因较小屏幕下LazyColumn尚未组合重试按钮而失败，其余50项各自通过；仅将测试定位改为列表performScrollToNode，保留按钮assertIsDisplayed。最终两组完整51项重新运行均通过，不跳过或弱化验收。

正式Release包含PickAudio-v1.3.0-release.apk与SHA256SUMS.txt。已从公开下载链接完整取得APK，HTTP 200，8,156,279字节，SHA-256与本地正式包及GitHub服务端digest完全一致；下载副本为published-PickAudio-v1.3.0-release.apk。公开下载验证通过后，才将根version.json与README的安装入口更新为1.3.0。

version.json与README已同步main（eb715e391e0ba5ce3cc1f562e6675aad9d1aab42），GitHub API、raw主分支与备用CDN均核对版本、下载URL、大小及SHA-256一致。专用设备上的原签名v1.3.0实际点击“检查更新”后显示“已是最新版本”，原始记录release-130-update-ui.xml。

2026-10-07发布后复验37505972054：build和validation通过，Debug 51项中只有thrownNetworkCallbackFailsResolutionImmediately失败，assert收到空错误，解析意外成功。结合ensureBuiltinSources的先读后REPLACE及构造器后台初始化，确认默认源写入与自定义绑定存在竞态；晚到默认写入可能覆盖用户选择。已改为数据库INSERT OR IGNORE默认绑定，并新增确定性覆盖“默认源先读空→用户写入→晚到默认插入”、明确禁用及真正未配置平台。生产代码发生变化，另发1.3.1/code 11，保留既有1.3.0附件；重新构建、签名、完整CI、覆盖升级和公开下载验证均已完成，根version.json在补丁公开前保持1.3.0。

## 优化实现验收（2026-10-06）

O01–O24的代码及本地验收已完成，测试夹具已清理。O25/O26保留为已评估的独立后续阶段；远端CI、实体硬件和正式签名覆盖升级的验证边界见下文。

最终Debug/validation/Release均构建成功，61项JVM全部通过。完整Debug51项通过（313.41s），完整validation51项通过（85.638s），包含增强后的真实v2→v4迁移、睡眠取消与实际服务停止、标准MediaController命令、重复条目恢复、暂停通知、重复恢复播放和无外部控制器绑定时的后台自然切歌。最终独立Lint为0错误/61警告。

最终未签名Release为8,094,688字节（7.72MiB），DEX4,731,984字节，SHA-256 f4d031dd771fcfec8594d8404927017d7f4c25c1c4ff97fa1ef469a61c3d804f；双ABI及16KiB ZIP/ELF检查通过。相对历史正式APK54,403,842字节（51.9MiB）减小85.12%。本地元数据明确publishable=false，源码版本仍为1.2.0/code 9。

历史正式APK SHA-256仍为2f60cb8df209bcb0688ed6ed50de8462cbc3b1c8e832b076d615700b7a99a511；正式证书SHA-256 1e564b554f48b6a69d80e6d76c704e8b16e9a600d2511205754839c729d4a281。原APK、release.jks及历史版本资料保留。

10,000首曲库专项已确认歌单查询不加载整库。复用搜索索引后的Debug首轮样本首筛272ms、20次筛选p95=39ms；旧冷筛选676ms。最终Debug整组复测首筛64ms、p95=11ms，20首歌单22ms，全库首次查询320ms；最终validation样本首筛6ms、p95=2ms，20首歌单4ms，全库查询63ms。不同轮次的JIT、系统缓存、裁剪和模拟器状态不完全相同，不视为严格配对的冷启动性能比较，也不宣称实体设备帧率。

实际生产裁剪代码的测试签名副本与Release的classes.dex SHA-256完全一致：ba0a66d4a320a2160050654f1e930cbb2b37e2d789c3992f69070474e5877d6b。验证已通过：force-stop后的通知Intent冷启动，完整播放器恢复0:02暂停进度和三条重复队列；真实点击播放及后台自然切歌；媒体会话PLAYING，通知2041前台服务与三个有效媒体控件；从实际通知暂停并点击返回，播放器与通知的歌曲/0:13进度一致。未读取正式keystore。启动命令TotalTime=11,187ms，仅作为本次模拟器观测，不计为启动性能验收。

随后恢复validation并正常运行媒体专项（53.342s通过）清理夹具，再恢复最终Debug/匹配测试APK检查私有文件和界面：保留的测试WAV为0，曲库为0首，播放队列及迷你播放器已清空。只清理专用设备上的本轮自有媒体夹具；未操作个人设备。

## 本地验收证据

以下均为最终代码结果。app/build是本地生成目录，不提交到源码；后续运行会覆盖同名报告。

| 验证 | 结果 | 本地原始记录 |
|---|---|---|
| JVM | 61项，0失败/0错误 | app/build/reports/tests/testDebugUnitTest/index.html |
| API36 Debug | 完整51项通过，313.41s | app/build/reports/optimization/debug-instrumentation.txt |
| API36 validation | 完整51项通过，85.638s | app/build/reports/optimization/validation-instrumentation.txt |
| Lint | 0错误/61警告，保留测试源码检查 | app/build/reports/lint-results-debug.html |
| 查询/筛选及媒体专项 | scoped查询、后台自然切歌、通知稳定性 | app/build/reports/optimization/debug-performance-media.txt、validation-performance-media.txt |
| Release | R8/资源裁剪、包名/版本、双ABI/16KiB通过 | app/build/reports/optimization/minified-final-build.txt、release-verification.json |
| 实际生产代码启动/通知 | 冷恢复、重复队列、后台切歌、真实通知控件与返回 | app/build/reports/optimization/release-cold-ui.xml、release-queue-ui.xml、release-notification-ui.xml、release-notification-open-ui.xml |
| 实际前台播放 | PLAYING、有效媒体通知、自然切歌后仍前台 | app/build/reports/optimization/release-media-session-background.txt、release-services-background.txt、release-notification.png |
| 测试夹具清理 | 私有WAV=0、曲库=0、队列清空；恢复最终Debug/匹配测试APK | app/build/reports/optimization/release-fixture-cleanup.txt、fixture-cleanup-verification.txt、fixture-cleanup-ui.xml |
| Room schema | v2/v3/v4核查；真实v2→v4迁移通过 | app/schemas、OptimizationPlaybackTest |

通知播放动画会持续产生无障碍事件，uiautomator曾无法取得idle；该失败未产生新XML，不用旧文件作为证据。最终通知截图记录播放状态，通知暂停后成功生成了新的XML，并实际点击通知返回播放器。

## 本轮落地内容

- 更新与播放：实际取消和旧代次保护；更新大小、SHA-256、ZIP、包名、签名与版本核验；独立队列条目和完整系统媒体时间线；串行持久快照与重复条目恢复。
- 曲库与下载：歌曲、资源、歌单关系事务；持久删除待确认，重启只核查、不继续删除；下载租约、可靠续传、空间预算、发布ticket与中断核对；失效完成文件回到需处理/可重下。目录一次列举并索引歌词，稳定ID重新关联和人工确认候选。
- 网络、脚本和备份：读取上限、取消、DNS/逐跳政策与正式TLS；JNI UTF-8/emoji/NUL、真实MD5、引用释放与异常传播、引擎/请求限额；v3备份兼容v1/v2，16MiB对称契约、文件payload、小Room receipt及Room→DataStore幂等恢复。
- 搜索和操作：分平台摘要/分页结束判断、保留各平台状态；100首97成功/3失败的批量报告和只重试失败项；原位撤销、真实收藏时间、独立排序及并发冲突反馈；canonical平台歌曲身份复用且不回退更新的元信息。
- 性能与体验：后台曲库索引/筛选、scoped歌单查询、稳定Flow/生命周期订阅；歌词按歌刷新、GB18030/BOM、断网缓存、合并校准；分层返回、通知等待恢复、2倍字号与语义、158条静态资源文案；elapsedRealtime睡眠和服务停止清理。
- 缓存与诊断：来源/歌曲/音质/最终主机路径/强ETag缓存身份、If-Match保护；无强验证器保留完整URL；HEAD最多1500ms、128项验证器LRU、64/256/1024MiB容量和低空间预算、原地缩小、本地播放旁路；显式本地JSON诊断不含歌名、URI、脚本或凭据。
- 构建与维护：生产R8/资源裁剪及精确Gson/JNI保留；真实Room v2/v3/v4 schema和迁移；version.properties、依赖锁定/校验、schema/发布核查脚本、CI工作流；106项构建依赖及完整第三方许可离线查看；系统备份/设备迁移排除设备绑定私有状态，手动备份保持可恢复。

## 验收中发现并修复的问题

前台服务必须主动注册MediaSession，并在异步解析期间及时进入前台；否则睡眠取消后仍会触发平台的前台启动超时。对应测试等待系统期限，并通过真实stopService验证清理。

实际Release代码的后台检查发现通知反馈循环：onStartCommand主动刷新通知，Media3发布前台通知又启动服务（短时lastStartId=197），最终后台停止并残留准备通知。增补专项在旧代码失败；现已移除主动刷新，应用启动携带专用action，Media3无action自启动保留已有媒体通知；取消且无可播放媒体时移除准备通知。最终Debug/validation整组及实际生产代码的后台切歌/通知均通过；实际服务在自然切歌后lastStartId=10，保留正确的三个媒体控件。

validation的共享库被测试APK排除，必须为测试运行器、Compose、Room和网络测试保留公共ABI，并禁止把未知测试子类会覆盖的方法finalize。该宽规则仅在proguard-validation.pro；生产proguard-rules.pro保持精确保留。真实生产代码另以DEX相同的debug签名副本检查，不能用validation包大小代替Release。

Windows下生成资源的任务依赖已补。各变体先完成构建，再独立执行Lint，避免Lint读取KSP正在更新的生成Java文件；测试源码Lint保持开启。61条警告以依赖/KTX升级建议及既有locale/样式建议为主，未因此盲目升级依赖。

## 任务状态

| 编号 | 状态 | 交付与证据 |
|---|---|---|
| O01 | 已验收（JVM） | 实际取消、旧代次回写与页面退出 |
| O02 | 已验收（JVM/API36） | 实际签名/包名/内部版本/降级校验与下载限制通过；正式证书覆盖升级另验 |
| O03/O04 | 已验收（JVM/API36/实际Release代码） | 生产队列策略、完整时间线、标准媒体命令、重复条目快照、无外部控制器绑定时的后台自然切歌 |
| O05 | 已验收（API36） | 导入原子性、稳定关联、真实删除报告、操作阻挡、删除中断核对 |
| O06–O08 | 已验收（API36） | 下载代次、worker退出窗口、续传验证、发布阶段恢复、完成文件失效/重下 |
| O09–O11 | 已验收（JVM/API36） | 统一受限网络、DNS/每跳检查、TLS、JNI异常与资源释放 |
| O12 | 已验收（JVM/API36） | v3备份往返、容量对称、Room/DataStore幂等恢复会话 |
| O13/O15 | 已验收专项 | 真实16/24bit PCM、截断、目录一次列举、标签/歌词/封面 |
| O14 | 已验收查询与索引专项 | 10,000首查询范围/后台筛选p95；稳定Flow/生命周期；实体帧率另验 |
| O16/O17 | 已验收（JVM/API36） | 目标歌词刷新/缓存/编码/校准；来源摘要/分页/竞态和真实批量UI流程 |
| O18 | 已验收（JVM/API36） | 97/3报告与失败重试、原位撤销、收藏真实时间、排序冲突拒绝 |
| O19 | 后端与候选规则已验收 | 持久修复、稳定关联/转移保留歌单、同名不同录音候选排除；人工确认入口已实现 |
| O20 | 已验收（API36/实际Release代码） | 分层返回/2倍字号/睡眠取消/服务释放/冷通知恢复/真实通知暂停与返回；TalkBack/实体蓝牙另验 |
| O21 | 已验收（JVM/API36） | 安全缓存键、实际字节复用/资源变化、运行中容量缩小、本地文件旁路、诊断隐私 |
| O22 | 本地已验收 | R8/资源裁剪、生产精确保留、validation设备整组与相同Release DEX的启动/通知验证 |
| O23 | 本地已验收 | 61项JVM，Debug/validation各完整51项；生产策略和真实故障边界，增补后台通知回归 |
| O24 | 已验收（本地/远端CI/正式发布） | schema、版本、锁定/校验、发布/签名/对齐工具、许可打包与系统备份规则；CI三项job通过，公开APK下载和SHA-256核验通过 |
| O25 | 已评估：保持API36 | 暂无扩大设备范围的需求，UIDT与权限政策维持一致 |
| O26 | 已评估：后续独立阶段 | 无缝切歌、速度/均衡器、标签写入、分享分别需要额外流程和硬件/格式验证，不设为本轮可靠性前提 |

## 决策与限制

- 渐进式整理ViewModel、用例和数据边界，保留单个Room数据库及原生栈；新增字段均提供保留用户数据的迁移。
- 下载/删除/备份的外部副作用使用持久阶段和幂等核对；Room内的歌曲、资源、成员和收藏使用事务。删除中断恢复只核查，不再次执行删除。
- 脚本每操作独立引擎，上限4个；网络同时8请求、每引擎排队32。代价是每首解析重新初始化，不保留跨歌脚本内存缓存；未宣称完整LX crypto兼容。
- 备份payload置于私有文件，Room只存小receipt/阶段；Room提交→DataStore同次edit会话标记→完成。避免CursorWindow超限和崩溃后覆盖后续设置。
- 音频校验包含真实格式、轨道、数据包和明显截断；不是整首PCM解码验收。缓存强ETag复用受源/歌曲/质量/最终主机与路径约束，HTTP不提供验证器时不剥离查询参数。
- 正式发布使用单调版本号与原证书；优化实现阶段未提交、推送或发布。本次发布使用1.3.0/code 10且已补原证书覆盖安装，实际远端状态以当前发布检查点为准。validation与测试签名副本的结果不替代正式签名升级验证。
- 蓝牙硬件、TalkBack人工流程、持续滚动帧率/耗电和实体设备首播指标仍需对应设备；没有测量的指标不标为通过。

## 环境和验证入口

PowerShell；JDK D:/dev/jdk-17、SDK D:/dev/android-sdk、Gradle home C:/Users/awxds/.gradle-desktile；NDK28.2.13676358/CMake3.22.1。

唯一专用设备PickAudio_Optimization_API36（emulator-5560，D:/dev/avd/PickAudioOptimization36）。禁止操作个人设备emulator-5554。中文路径下UTP定位APK失败时，改用adb直接运行相同instrumentation；虚拟蓝牙只在专用模拟器关闭。

构建与设备命令、发布和系统备份范围见[构建与发布基线](BUILD-AND-RELEASE.md)。JVM报告app/build/reports/tests/testDebugUnitTest/index.html；性能日志PickAudioPerformance，媒体阶段日志OptimizationMedia。本轮最终原始报告路径见上表，后续执行在本文件新增检查点。

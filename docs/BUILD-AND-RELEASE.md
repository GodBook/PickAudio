# 构建、回归与发布基线

应用ID为com.pickaudio，当前正式版本1.3.1/code 11。version.properties 为源码构建版本的唯一入口；根目录 version.json 描述已公开发布的APK，不随本地构建覆盖，在正式Release可下载后同步。

## 环境和常规检查

JDK17、Android SDK36、NDK28.2.13676358、CMake3.22.1。SDK位置放local.properties或ANDROID_SDK_ROOT；签名与凭据均不进入源码库。

```powershell
$env:JAVA_HOME = 'D:/dev/jdk-17'
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew.bat :app:assembleRelease
./gradlew.bat :app:lintDebug
./scripts/verify-schemas.ps1
```

Room导出schema位于app/schemas；v2为已发布版本的真实历史结构，v3/v4为本轮演进。禁止用删库代替迁移。MigrationTestHelper使用这些文件核验v2→v4并保留队列条目、收藏时间和关联；旧v1测试仍属于结构模拟，不宣称覆盖历史正式签名安装升级。

Windows下让各变体生成代码完成后再单独运行Lint，避免KSP正在更新Release的Java输出时，Lint并行读取该路径发生FileNotFoundException。此处保留测试源码Lint，未屏蔽检测器。

## 隔离设备回归

UiFlowTest及部分媒体测试会清空应用单例队列。只在无个人数据的专用API36设备运行。本地当前专用设备为emulator-5560；不得使用个人设备emulator-5554。

Windows中文路径下UTP不能正确定位APK时，先安装test APK，再安装主APK，然后直接运行同一套instrumentation：

```powershell
adb -s emulator-5560 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5560 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5560 shell am instrument -w com.pickaudio.test/androidx.test.runner.AndroidJUnitRunner
```

裁剪功能回归使用validation变体，继承Release的R8/资源裁剪规则，以公开debug证书签名，加入同UID目录夹具及Compose测试宿主，并为instrumentation保留公开业务接口及测试APK共享的Kotlin、AndroidX、网络库公开ABI。否则测试APK排除共享库后，会引用主包已经裁掉的类。图标仍可裁剪，Gson字段/JNI契约使用相同生产精确规则。它不能覆盖正式签名生产应用，也不作为发布包；正式包的实际裁剪/重命名另通过相同Release代码的测试签名副本做启动与通知检查。

```powershell
./gradlew.bat -PinstrumentationBuildType=validation :app:assembleValidation :app:assembleValidationAndroidTest
```

Debug/validation的更新测试夹具自动生成：正确签名、更高版本；错误签名；错误包名。测试不会安装这些夹具，不读取release.jks。生产Release不包含目录提供者、测试宿主或更新夹具。

### 实际生产裁剪代码的启动专项

validation保留了测试需要的公共ABI，另须核对实际Release代码。只在上述专用设备执行以下流程：

1. 在validation运行真实媒体专项，传入`keepPlaybackFixture=true`，保留两首私有WAV和含重复条目的暂停快照。
2. 用Android公开debug证书签署未签名Release的副本，输出至`app/build/optimization-release-smoke/`。核对副本与原Release全部DEX的SHA-256一致；不读取`release.jks`，不替换历史正式APK。
3. 以`adb install -r`安装副本保留夹具，force-stop后使用通知所用的`open_player=true` Intent冷启动。记录UI XML，检查完整播放器、暂停状态、保存的进度、三条队列及最后一个重复条目为当前歌曲。
4. 点击实际播放控件，切到后台，核对媒体会话PLAYING、前台播放服务、通知的歌名和上一首/暂停/下一首控件；从通知点击返回播放器，观察自然切歌后仍持续播放。
5. 恢复validation主包及匹配的测试包，正常运行同一媒体专项清理夹具，核对自有测试歌曲、私有WAV和队列已清理。不得对实际生产裁剪副本运行依赖测试ABI的白盒instrumentation。

```powershell
adb -s emulator-5560 shell am instrument -w -e class 'com.pickaudio.OptimizationPlaybackTest#mediaControllerCommandsAndDuplicateQueueSnapshotUseSameOccurrences' -e keepPlaybackFixture true com.pickaudio.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5560 shell am force-stop com.pickaudio
adb -s emulator-5560 shell am start -W -n com.pickaudio/.MainActivity --ez open_player true
```

媒体服务首次播放请求必须及时进入前台。应用请求携带专用action；Media3内部的无action自启动已配套发布媒体通知，不再创建准备通知或主动刷新通知。否则`onStartCommand`→刷新通知→Media3自启动会形成循环。设备专项同时检查暂停后的有效媒体通知、重复恢复播放、控制器解绑后的后台自然切歌，以及取消解析后的前台期限和真实服务停止。

## 发布检查

Release默认为未签名构建。用户已授权本次GitHub与Release更新，沿用原项目证书签名；版本从1.2.0/code 9提升至1.3.0/code 10。先完成本地覆盖安装与数据保留验证，再检查远端CI并发布；实体蓝牙、TalkBack和实体升级记录按实际验证范围记录。

```powershell
./scripts/verify-release.ps1 -ApkPath <signed-apk> -PreviousVersionCode 9 -ExpectedCertificateSha256 <original-certificate-sha256> -SdkPath D:/dev/android-sdk -ChangelogPath <release-notes> -OutputDirectory app/build/release-candidate
```

脚本核对包名、源码版本、单调版本号、正式证书、签名有效性、双ABI、ELF PT_LOAD和APK ZIP的16KiB对齐，输出实际大小、SHA-256与元数据。输出目录限定在app/build，根目录历史version.json不受影响。仅检查本地未签名包时可使用-AllowUnsigned并指定真实较低的PreviousVersionCode；结果明确为publishable=false。

GitHub工作流执行单测、Lint、Debug/Release构建、schema核查以及API36 Debug/裁剪设备回归；v1.3.1的[完整远端CI](https://github.com/GodBook/PickAudio/actions/runs/37507715988)已通过，单测61项、两组设备测试各52项，均无失败或跳过。构建许可材料与准确依赖列表打包进APK。升级依赖时成组更新锁定和校验信息，复查源授权、JNI和音频流程。

托管CI明确安装SDK36/build-tools35/NDK28.2/CMake3.22.1，避免setup-android默认请求已下线的tools包。CI=true时从Google、Maven Central和Gradle Plugin Portal解析依赖；本地环境继续使用既有镜像配置。依赖锁定与摘要校验保持开启。

官方源与镜像的少量POM/module及废弃transform-api空JAR存在字节差异；新增摘要均直接核对官方发布的SHA1、坐标与版本约束，空JAR逐条核对ZIP内容一致。不得直接信任失败报告中的新值或关闭校验。LazyColumn界面测试查找屏幕外项目时使用列表performScrollToNode，再检查实际按钮可见，不依赖目标已被组合。

## 系统备份范围

禁用系统自动云备份，并用data-extraction-rules同时排除设备迁移中的私有文件、数据库与偏好，避免搬迁旧URI授权、脚本和活动下载状态。用户使用设置中的手动备份：恢复歌曲、歌单、收藏、歌词/校准和偏好；音频及源脚本另行保存，恢复会话可继续。缓存、下载临时文件和设备绑定授权不进入手动备份。

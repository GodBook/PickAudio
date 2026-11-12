# 拾音发布签名

2026-11-12 起按使用者要求采用新的个人发布证书。旧证书及原 `release.jks` 保留，新密钥不覆盖它，也不进入 Git、构建缓存或 Release。

新证书 SHA-256：`c8728b534a21f89310b8edddfccc1b1f2f1a77dac0104e53871f2e4ed9a8a5ea`。

本机配置：`C:\Users\awxds\.codex\signing\pickaudio\signing.json`。该目录包含密钥、公开证书和用 Windows 当前账户 DPAPI 加密的密码，仅当前用户及 SYSTEM 可访问。后续发布复用该配置，不再从历史聊天或临时构建脚本中提取密码。应备份整个签名目录并妥善保留 Windows 用户的解密能力；仅复制 DPAPI 密码文件到其他账户不能解密。

```powershell
$env:JAVA_HOME = 'D:/dev/jdk-17'
./scripts/sign-release.ps1 `
  -SigningConfigPath 'C:/Users/awxds/.codex/signing/pickaudio/signing.json' `
  -InputApk 'app/build/outputs/apk/release/app-release-unsigned.apk' `
  -OutputApk 'PickAudio-v1.5.1-release.apk' `
  -SdkPath 'D:/dev/android-sdk'
```

脚本不打印密码，拒绝覆盖已有输出，并核对签名证书。随后使用 `scripts/verify-release.ps1`，将上方新证书指纹作为 `-ExpectedCertificateSha256`，核验版本、签名和 16 KiB 对齐后才发布。

旧版 v1.5.0 的证书指纹为 `1e564b554f48b6a69d80e6d76c704e8b16e9a600d2511205754839c729d4a281`。新旧证书不同，不能覆盖升级或在旧版应用内直接更新。迁移前必须从旧版“设置 → 备份与恢复”导出并另存备份，单独保存音频和自定义音源脚本；再卸载旧版、安装新包并恢复备份/重新关联文件。未获得删除旧版及数据的授权时，设备测试使用隔离包，保留原应用。

package com.pickaudio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.update.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OptimizationUpdateTest {
    @Test fun realSignedArchivesEnforcePackageSignerInternalVersionAndDowngradePolicy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = AppUpdateManager(context)
        val info = UpdateInfo(100000, "99.0.0", "test", "https://github.com/GodBook/PickAudio/releases/download/test/app.apk", "")
        fun fixture(name: String): File = File.createTempFile("optimization-update-", ".apk", context.cacheDir).also { file ->
            instrumentation.context.assets.open("$name.apk").use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        val valid = fixture("valid")
        val wrongSigner = fixture("wrong-signer")
        val wrongPackage = fixture("wrong-package")
        try {
            manager.validateApk(valid, info)
            assertTrue(runCatching { manager.validateApk(wrongSigner, info) }.exceptionOrNull()?.message.orEmpty().contains("签名"))
            assertTrue(runCatching { manager.validateApk(wrongPackage, info) }.exceptionOrNull()?.message.orEmpty().contains("不属于"))
            assertTrue(runCatching { manager.validateApk(valid, info.copy(versionName = "99.0.1")) }.isFailure)
            assertTrue(runCatching { manager.validateApk(valid, info.copy(versionCode = 100001)) }.isFailure)
            assertTrue(runCatching { manager.validateApk(File(context.applicationInfo.sourceDir)) }.exceptionOrNull()?.message.orEmpty().contains("不高于"))
        } finally { valid.delete(); wrongSigner.delete(); wrongPackage.delete() }
    }
}

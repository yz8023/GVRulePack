package io.github.gvrulepack.app

import com.android.apksig.ApkVerifier
import com.reandroid.apk.ApkModule
import io.github.gvrulepack.app.export.ModuleApkExporter
import io.github.gvrulepack.common.RuleBundleParser
import io.github.gvrulepack.common.RuleFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 端到端（JVM）验证导出器：模板 APK + 示例规则 → 改包 → 签名 → 校验。
 *
 * 运行前置（pack.py 已自动保证）：
 * 1. `gradle :module:assembleRelease` 生成模板
 * 2. 模板复制到 app/src/main/assets/template.apk
 * 3. assets/default_sign.jks 存在（pack.py 自动生成）
 */
class ExporterJvmTest {

    private fun projectFile(vararg candidates: String): File =
        candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: throw AssertionError(
                "缺少文件，先运行 `python3 pack.py`（或 gradle :module:assembleRelease 并复制模板）: ${candidates.joinToString()}",
            )

    private val templateApk: File
        get() = projectFile(
            "src/main/assets/template.apk",
            "../module/build/outputs/apk/release/module-release.apk",
        )

    private val defaultKeystore: File
        get() = projectFile("src/main/assets/default_sign.jks")

    @Test
    fun exportProducesVerifiableModuleApk() {
        val rulesJson = File("src/main/assets/sample_rules.json").readText(Charsets.UTF_8)
        val parsed = RuleBundleParser.parse(rulesJson)
        assertTrue(parsed.ok)

        val pwd = RuleFormat.DEFAULT_KEYSTORE_PASSWORD.toCharArray()
        val sign = ModuleApkExporter.loadKeystore(
            defaultKeystore, pwd, RuleFormat.DEFAULT_KEY_ALIAS, pwd,
        )

        val outDir = File("build/test-export").apply { mkdirs() }
        val unsigned = File(outDir, "unsigned.apk")
        // 文件名用 ASCII：CI/沙箱的 POSIX locale 下 JVM 对非 ASCII 路径会抛
        // InvalidPathException（设备端 Android 是 UTF-8，无此问题）
        val signed = File(outDir, "gv-module-2.3.4.apk")
        unsigned.delete(); signed.delete()

        val result = ModuleApkExporter.export(
            templateApk = templateApk,
            rulesBundleJson = rulesJson,
            config = ModuleApkExporter.Config(
                packageName = "io.github.test.pack1",
                label = "测试模块",
                versionName = "2.3.4",
                versionCode = 7,
            ),
            sign = sign,
            unsignedOut = unsigned,
            signedOut = signed,
        )

        // ---- 导出结果统计 ----
        assertEquals(listOf("com.ss.android.ugc.aweme"), result.scopePackages)
        assertEquals(4, result.viewRuleCount)

        // ---- apksig 校验签名 ----
        val verifyResult = ApkVerifier.Builder(signed).build().verify()
        assertTrue("APK 签名校验失败", verifyResult.isVerified)

        // ---- ARSCLib 读回 manifest ----
        val module = ApkModule.loadApkFile(signed)
        try {
            val manifest = module.androidManifest
                ?: throw AssertionError("缺少 manifest")
            assertEquals("io.github.test.pack1", manifest.packageName)
            assertEquals("2.3.4", manifest.versionName)
            assertEquals(7, manifest.versionCode?.toInt())
            assertEquals("测试模块", manifest.applicationLabelString)
        } finally {
            module.close()
        }

        // ---- zip 内容核对 ----
        ZipFile(signed).use { zip ->
            val scope = String(zip.getInputStream(zip.getEntry(RuleFormat.SCOPE_LIST_PATH)).readAllBytes())
            assertTrue(scope.contains("com.ss.android.ugc.aweme"))

            // 规则必须在 assets/ 前缀的真实路径下（裸文件名曾导致模块读不到规则）
            val embedded = String(zip.getInputStream(zip.getEntry(RuleFormat.EMBEDDED_RULEPACK_ZIP_PATH)).readAllBytes())
            val reparsed = RuleBundleParser.parse(embedded)
            assertTrue(reparsed.ok)
            assertEquals(1, reparsed.apps.size)
            assertEquals(4, reparsed.apps[0].viewRules.size)
            assertTrue("裸路径 rulepack.json 不应存在", zip.getEntry(RuleFormat.EMBEDDED_RULEPACK_ASSET) == null)

            // 传统 Xposed 入口（所有框架都认 assets/xposed_init）
            assertTrue(zip.getEntry(RuleFormat.XPOSED_INIT_ASSET) != null)
            val init = String(zip.getInputStream(zip.getEntry(RuleFormat.XPOSED_INIT_ASSET)).readAllBytes())
            assertTrue(init.contains("io.github.gvrulepack.module.RulePackModule"))

            // 模块元数据随导出改写（LSPosed 列表里显示的模块名 = 填写的模块名）
            val prop = String(zip.getInputStream(zip.getEntry(RuleFormat.MODULE_PROP_PATH)).readAllBytes())
            assertTrue(prop.contains("name=测试模块"))
            assertTrue(prop.contains("version=2.3.4"))
            assertTrue(prop.contains("versionCode=7"))
        }
    }
}

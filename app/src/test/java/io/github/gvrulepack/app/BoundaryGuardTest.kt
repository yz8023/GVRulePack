package io.github.gvrulepack.app

import io.github.gvrulepack.app.export.ModuleApkExporter
import io.github.gvrulepack.common.RuleBundleParser
import io.github.gvrulepack.common.RuleFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计修复的防回归测试（v1.2.9，F14）：
 * 1. 缺 depth/modified 字段的规则必须在解析层被拒（Gson Unsafe 实例化 NPE 事故）
 * 2. 非法包名必须被拒（xposedscope 分号注入 + 存储文件名碰撞）
 * 3. 导出器必须拒绝路径穿越 versionName 与越界 versionCode
 */
class BoundaryGuardTest {

    private fun bundle(appsBody: String): String =
        """
        {"format":"${RuleFormat.BUNDLE_FORMAT}","format_version":1,"apps":[$appsBody]}
        """.trimIndent()

    private fun rule(
        pkg: String = "com.example.app",
        depth: String = "[0,1]",
        modified: String = """{"width":0,"height":0,"margin_left":0,"margin_top":0,"margin_right":0,"margin_bottom":0,"padding_left":0,"padding_top":0,"padding_right":0,"padding_bottom":0,"visibility":0,"text":null,"hint":null,"max_lines":1,"image_url":null,"scale_type":null}""",
    ): String = """
        {"package":"$pkg","label":"t","view_rules":[
          {"package_name":"$pkg","match_version_code":1,"activity_class":"a.B","view_class":"android.view.View",
           "depth":$depth,"res_name":null,"text":null,"description":null,
           "original":$modified,"modified":$modified}
        ],"dialog_rules":[]}
    """.trimIndent()

    // ---- 1. Gson 缺字段防线 ----

    @Test
    fun parserRejectsMissingDepth() {
        val direct = """
            {"format":"${RuleFormat.BUNDLE_FORMAT}","format_version":1,"apps":[
              {"package":"com.example.app","label":"t","view_rules":[
                {"package_name":"com.example.app","match_version_code":1,"activity_class":"a.B","view_class":"android.view.View",
                 "res_name":null,"text":null,"description":null,
                 "original":{"width":0,"height":0,"margin_left":0,"margin_top":0,"margin_right":0,"margin_bottom":0,"padding_left":0,"padding_top":0,"padding_right":0,"padding_bottom":0,"visibility":0,"text":null,"hint":null,"max_lines":1,"image_url":null,"scale_type":null},
                 "modified":{"width":0,"height":0,"margin_left":0,"margin_top":0,"margin_right":0,"margin_bottom":0,"padding_left":0,"padding_top":0,"padding_right":0,"padding_bottom":0,"visibility":0,"text":null,"hint":null,"max_lines":1,"image_url":null,"scale_type":null}}
              ],"dialog_rules":[]}
            ]}
        """.trimIndent()
        val result = RuleBundleParser.parse(direct)
        assertTrue("缺 depth 必须被识别为错误", result.errors.any { it.contains("depth") })
        assertTrue("缺 depth 的规则不得进入结果", result.apps.all { it.viewRules.isEmpty() })
        assertFalse(result.ok)
    }

    @Test
    fun parserRejectsMissingModified() {
        val direct = """
            {"format":"${RuleFormat.BUNDLE_FORMAT}","format_version":1,"apps":[
              {"package":"com.example.app","label":"t","view_rules":[
                {"package_name":"com.example.app","match_version_code":1,"activity_class":"a.B","view_class":"android.view.View",
                 "depth":[0,1],"res_name":null,"text":null,"description":null,
                 "original":{"width":0,"height":0,"margin_left":0,"margin_top":0,"margin_right":0,"margin_bottom":0,"padding_left":0,"padding_top":0,"padding_right":0,"padding_bottom":0,"visibility":0,"text":null,"hint":null,"max_lines":1,"image_url":null,"scale_type":null}}
              ],"dialog_rules":[]}
            ]}
        """.trimIndent()
        val result = RuleBundleParser.parse(direct)
        assertTrue("缺 modified 必须被识别为错误", result.errors.any { it.contains("modified") })
        assertFalse(result.ok)
    }

    // ---- 2. 包名白名单（注入 + 文件碰撞） ----

    @Test
    fun parserRejectsPackageNameWithSemicolon() {
        val result = RuleBundleParser.parse(bundle(rule(pkg = "com.evil;injected.pkg")))
        assertTrue("含分号的包名必须被拒（xposedscope 注入）", result.errors.any { it.contains("不合法") })
        assertFalse(result.ok)
    }

    @Test
    fun parserRejectsPackageNameWithPathChars() {
        val result = RuleBundleParser.parse(bundle(rule(pkg = "com/evil..path")))
        assertTrue(result.errors.any { it.contains("不合法") })
        assertFalse(result.ok)
    }

    // ---- 3. 导出器边界（require 在解析之前，无需模板文件） ----

    private fun exportConfig(
        pkg: String = "io.github.test.pack1",
        vName: String = "1.0.0",
        vCode: Int = 1,
    ) = ModuleApkExporter.Config(pkg, "测试模块", vName, vCode)

    private fun expectReject(config: ModuleApkExporter.Config, what: String) {
        try {
            ModuleApkExporter.export(
                templateApk = java.io.File("nonexistent.apk"),
                rulesBundleJson = bundle(rule()),
                config = config,
                sign = ModuleApkExporter.SignMaterial(
                    java.security.KeyPairGenerator.getInstance("RSA").generateKeyPair().private,
                    emptyList(),
                    "t",
                ),
                unsignedOut = java.io.File("build/test-export/u.apk"),
                signedOut = java.io.File("build/test-export/s.apk"),
            )
            throw AssertionError("$what 应该被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue("拒绝原因应指向 $what：${e.message}", e.message?.contains(what.substring(0, 4)) == true)
        }
    }

    @Test
    fun exporterRejectsPathTraversalVersionName() {
        expectReject(exportConfig(vName = "a/../b"), "版本名含非法字符")
    }

    @Test
    fun exporterRejectsSlashInVersionName() {
        expectReject(exportConfig(vName = "1.0/evil.apk"), "版本名含非法字符")
    }

    @Test
    fun exporterRejectsZeroVersionCode() {
        expectReject(exportConfig(vCode = 0), "versionCode")
    }

    @Test
    fun exporterRejectsNegativeVersionCode() {
        expectReject(exportConfig(vCode = -7), "versionCode")
    }

    @Test
    fun exporterAcceptsChineseVersionName() {
        // 中文版本名合法（白名单是 \p{L}\p{N}._-），不应被误杀
        val cfg = exportConfig(vName = "测试版")
        assertTrue(cfg.versionName.matches(Regex("[\\p{L}\\p{N}._-]+")))
    }
}

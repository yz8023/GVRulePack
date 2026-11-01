package io.github.gvrulepack.module

import android.util.Log
import io.github.gvrulepack.common.ParsedRemoteRules
import io.github.gvrulepack.common.RuleBundleParser
import io.github.gvrulepack.common.RuleFormat
import java.util.zip.ZipFile

/**
 * 注入进程内的规则来源：用 ZipFile 直接读模块自身 APK 里的
 * `assets/rulepack.json`（initZygote 提供的 modulePath）。
 *
 * 早期实现反射 AssetManager.addAssetPath——那是非 SDK 隐藏 API，
 * 高版本 Android 上随时可能失败，且模板/导出路径稍有差错就静默空规则；
 * 现在全公开 API + 详细日志，任何一步失败都会出现在 LSPosed 日志里。
 */
object EmbeddedRules {

    private const val TAG = "GVRulePack"

    /** 按当前进程包名取预置规则；任何失败都返回空，不抛。 */
    fun load(packageName: String, moduleApkPath: String?): ParsedRemoteRules {
        return runCatching { loadInternal(packageName, moduleApkPath) }
            .onFailure { Log.e(TAG, "embedded rules load failed", it) }
            .getOrNull()
            ?: ParsedRemoteRules.EMPTY
    }

    private fun loadInternal(packageName: String, moduleApkPath: String?): ParsedRemoteRules {
        val apkPath = moduleApkPath
            ?: run { Log.e(TAG, "module apk path unknown (initZygote not called?)"); return ParsedRemoteRules.EMPTY }

        val json = ZipFile(apkPath).use { zip ->
            val entry = zip.getEntry(RuleFormat.EMBEDDED_RULEPACK_ZIP_PATH)
                ?: run {
                    Log.e(TAG, "entry missing in $apkPath: ${RuleFormat.EMBEDDED_RULEPACK_ZIP_PATH}")
                    return ParsedRemoteRules.EMPTY
                }
            zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        }

        val parsed = RuleBundleParser.parse(json)
        if (!parsed.ok) {
            Log.e(TAG, "rule json parse failed: ${parsed.errors}")
            return ParsedRemoteRules.EMPTY
        }
        val app = parsed.apps.firstOrNull { it.packageName == packageName }
            ?: run {
                Log.e(TAG, "no rules for pkg=$packageName (bundle has ${parsed.apps.map { it.packageName }})")
                return ParsedRemoteRules.EMPTY
            }
        Log.i(
            TAG,
            "embedded rules pkg=$packageName view=${app.viewRules.size} dialog=${app.dialogRules.size}",
        )
        return ParsedRemoteRules(app.viewRules, app.dialogRules)
    }

    /** 仅测试用：直接解析 bundle 文本（不走 ZipFile）。 */
    fun parseForPackage(json: String, packageName: String): ParsedRemoteRules {
        val app = RuleBundleParser.parse(json).apps.firstOrNull { it.packageName == packageName }
            ?: return ParsedRemoteRules.EMPTY
        return ParsedRemoteRules(app.viewRules, app.dialogRules)
    }
}

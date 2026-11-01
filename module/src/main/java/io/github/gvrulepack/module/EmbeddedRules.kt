package io.github.gvrulepack.module

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.util.Log
import io.github.gvrulepack.common.ParsedRemoteRules
import io.github.gvrulepack.common.RuleBundleParser
import io.github.gvrulepack.common.RuleFormat

/**
 * 注入进程内的规则来源：只读本模块 APK 预置的 assets/rulepack.json。
 * （工厂导出时把选中的应用规则写进该文件，并生成 scope.list 推荐作用域。）
 */
object EmbeddedRules {

    private const val TAG = "GVRulePack"

    @Volatile
    private var assetManager: AssetManager? = null

    /** 按当前进程包名取预置规则；任何失败都返回空，不抛。 */
    fun load(context: Context, moduleAppInfo: ApplicationInfo?): ParsedRemoteRules {
        return runCatching { loadInternal(context.packageName, moduleAppInfo) }
            .onFailure { Log.e(TAG, "embedded rules load failed", it) }
            .getOrNull()
            ?: ParsedRemoteRules.EMPTY
    }

    private fun loadInternal(packageName: String, moduleAppInfo: ApplicationInfo?): ParsedRemoteRules {
        val apkPath = moduleAppInfo?.sourceDir ?: return ParsedRemoteRules.EMPTY
        val assets = assetManager ?: runCatching {
            val ctor = AssetManager::class.java.getDeclaredConstructor()
            ctor.isAccessible = true
            val am = ctor.newInstance() as AssetManager
            AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
                .apply { isAccessible = true }
                .invoke(am, apkPath)
            am
        }.getOrNull()?.also { assetManager = it } ?: return ParsedRemoteRules.EMPTY

        val json = runCatching {
            assets.open(RuleFormat.EMBEDDED_RULEPACK_ASSET)
                .use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return ParsedRemoteRules.EMPTY

        val parsed = RuleBundleParser.parse(json)
        val app = parsed.apps.firstOrNull { it.packageName == packageName }
            ?: return ParsedRemoteRules.EMPTY
        Log.i(
            TAG,
            "embedded rules pkg=$packageName view=${app.viewRules.size} dialog=${app.dialogRules.size}",
        )
        return ParsedRemoteRules(app.viewRules, app.dialogRules)
    }

    /** 仅测试用：直接解析 bundle 文本（不走 AssetManager）。 */
    fun parseForPackage(json: String, packageName: String): ParsedRemoteRules {
        val app = RuleBundleParser.parse(json).apps.firstOrNull { it.packageName == packageName }
            ?: return ParsedRemoteRules.EMPTY
        return ParsedRemoteRules(app.viewRules, app.dialogRules)
    }
}


package io.github.gvrulepack.app.module

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.net.Uri
import com.google.gson.Gson
import io.github.gvrulepack.app.shared.Constants
import io.github.gvrulepack.app.shared.ParsedRemoteRules
import io.github.gvrulepack.app.shared.RuleMerge
import io.github.gvrulepack.app.shared.RuleBundleParser
import io.github.gvrulepack.app.shared.StoredApp

/**
 * 注入进程内的规则来源，按优先级合并：
 *
 * 1. 模块 APK 预置 assets（`gradle assembleRelease -PrulesBundle=xxx.json` 打的「分享包」）
 * 2. 宿主运行时导入的规则（经 ContentProvider 跨进程读取）
 *
 * 两者按规则 key 合并，运行时导入的覆盖预置的同 key 规则。
 */
object RuleSource {

    private const val TAG = "GVRulePack"
    private val gson = Gson()

    @Volatile
    private var embeddedAssetManager: AssetManager? = null

    /** 读取当前包的全部规则（view + dialog）。任何失败都返回空，不抛。 */
    fun load(context: Context, moduleAppInfo: ApplicationInfo?): ParsedRemoteRules {
        val embedded = runCatching { loadEmbedded(context.packageName, moduleAppInfo) }
            .onFailure { android.util.Log.e(TAG, "embedded load failed", it) }
            .getOrNull()
            ?: ParsedRemoteRules.EMPTY
        val remote = runCatching { loadFromProvider(context, moduleAppInfo) }
            .onFailure { android.util.Log.e(TAG, "provider load failed", it) }
            .getOrNull()
            ?: ParsedRemoteRules.EMPTY
        return ParsedRemoteRules(
            viewRules = RuleMerge.merge(embedded.viewRules, remote.viewRules),
            dialogRules = RuleMerge.merge(embedded.dialogRules, remote.dialogRules),
        )
    }

    // ------------------------------------------------------------ 预置 assets

    private fun loadEmbedded(packageName: String, moduleAppInfo: ApplicationInfo?): ParsedRemoteRules {
        val apkPath = moduleAppInfo?.sourceDir ?: return ParsedRemoteRules.EMPTY
        val assets = embeddedAssetManager ?: runCatching {
            val ctor = AssetManager::class.java.getDeclaredConstructor()
            ctor.isAccessible = true
            val am = ctor.newInstance() as AssetManager
            AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
                .apply { isAccessible = true }
                .invoke(am, apkPath)
            am
        }.getOrNull()?.also { embeddedAssetManager = it } ?: return ParsedRemoteRules.EMPTY

        val json = runCatching {
            assets.open(Constants.EMBEDDED_RULEPACK_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return ParsedRemoteRules.EMPTY

        val parsed = RuleBundleParser.parse(json)
        val app = parsed.apps.firstOrNull { it.packageName == packageName }
            ?: return ParsedRemoteRules.EMPTY
        android.util.Log.i(
            TAG,
            "embedded rules pkg=$packageName view=${app.viewRules.size} dialog=${app.dialogRules.size}",
        )
        return ParsedRemoteRules(app.viewRules, app.dialogRules)
    }

    // ---------------------------------------------------------- 运行时导入

    private fun loadFromProvider(context: Context, moduleAppInfo: ApplicationInfo?): ParsedRemoteRules {
        // authority 用运行时包名推导（不用 BuildConfig 常量）：
        // 普通构建与「改 applicationId 的二进制重打包」都能对上 Manifest 里的 ${applicationId}.rules
        val modulePkg = moduleAppInfo?.packageName ?: Constants.RULES_AUTHORITY.removeSuffix(".rules")
        val uri = Uri.parse("content://$modulePkg/rules/${context.packageName}")
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && cursor.columnCount > 0) {
                val json = cursor.getString(0)
                if (!json.isNullOrBlank()) {
                    val stored = runCatching {
                        gson.fromJson(json, StoredApp::class.java)
                    }.getOrNull()
                    if (stored != null) {
                        android.util.Log.i(
                            TAG,
                            "provider rules pkg=${stored.packageName} " +
                                "view=${stored.viewRules.size} dialog=${stored.dialogRules.size}",
                        )
                        return ParsedRemoteRules(stored.viewRules, stored.dialogRules)
                    }
                }
            }
        }
        return ParsedRemoteRules.EMPTY
    }
}

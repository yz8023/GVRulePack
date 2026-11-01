package io.github.gvrulepack.app.host

import android.content.Context
import com.google.gson.Gson
import io.github.gvrulepack.app.shared.RuleMerge
import io.github.gvrulepack.app.shared.StoredApp
import io.github.gvrulepack.app.shared.RuleBundleParser
import java.io.File
import java.util.concurrent.Executors

/**
 * 宿主侧规则存储：每个应用一个 JSON 文件（files/rulepack/<pkg>.json）。
 *
 * 写入为原子写 + 单线程串行；读取同步。注入进程经 [RulesProvider] 读取。
 */
class RulePackStore(context: Context) {

    private val appContext = context.applicationContext
    private val gson = Gson()
    private val executor = Executors.newSingleThreadExecutor()

    private val rootDir: File
        get() = File(appContext.filesDir, "rulepack")

    data class ImportSummary(
        val packageName: String,
        val label: String?,
        val viewRuleCount: Int,
        val dialogRuleCount: Int,
    )

    data class ImportResult(
        val imported: List<ImportSummary>,
        val errors: List<String>,
    ) {
        val ok: Boolean get() = imported.isNotEmpty()
    }

    /** 导入一个 GodViewer 规则包：按应用合并（同 key 规则覆盖，其余保留）。 */
    fun importBundle(json: String): ImportResult {
        val parsed = RuleBundleParser.parse(json)
        val errors = parsed.errors.toMutableList()
        val summaries = ArrayList<ImportSummary>()
        for (app in parsed.apps) {
            try {
                val merged = mergeAndSave(app)
                summaries += ImportSummary(
                    packageName = app.packageName,
                    label = merged.label,
                    viewRuleCount = merged.viewRules.size,
                    dialogRuleCount = merged.dialogRules.size,
                )
            } catch (t: Throwable) {
                errors += "${app.packageName} 写入失败：${t.message}"
            }
        }
        return ImportResult(summaries, errors)
    }

    private fun mergeAndSave(app: RuleBundleParser.ParsedAppEntry): StoredApp {
        val existing = loadForPackage(app.packageName)
        val merged = StoredApp(
            packageName = app.packageName,
            label = app.label ?: existing?.label,
            viewRules = RuleMerge.merge(existing?.viewRules ?: emptyList(), app.viewRules),
            dialogRules = RuleMerge.merge(existing?.dialogRules ?: emptyList(), app.dialogRules),
            importedAt = System.currentTimeMillis(),
        )
        saveSync(merged)
        return merged
    }

    /** 已导入的全部应用（按导入时间倒序）。 */
    fun listApps(): List<StoredApp> {
        val dir = rootDir
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f -> runCatching { gson.fromJson(f.readText(), StoredApp::class.java) }.getOrNull() }
            ?.filter { !it.packageName.isNullOrBlankCompat() }
            ?.sortedByDescending { it.importedAt }
            ?: emptyList()
    }

    fun loadForPackage(packageName: String): StoredApp? {
        val file = fileFor(packageName)
        if (!file.exists()) return null
        return runCatching { gson.fromJson(file.readText(), StoredApp::class.java) }.getOrNull()
    }

    /** 删除单个应用的规则。 */
    fun deleteApp(packageName: String): Boolean {
        val file = fileFor(packageName)
        return if (file.exists()) file.delete() else false
    }

    /** 清空全部。 */
    fun clearAll() {
        rootDir.deleteRecursively()
    }

    private fun saveSync(app: StoredApp) {
        val dir = rootDir
        if (!dir.exists()) dir.mkdirs()
        val file = fileFor(app.packageName)
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText(gson.toJson(app), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    /** 异步保存（Provider 读多写少，这里只给宿主 UI 用） */
    fun saveAsync(app: StoredApp) {
        executor.execute { runCatching { saveSync(app) } }
    }

    private fun fileFor(packageName: String): File {
        val safe = packageName.replace(Regex("[^A-Za-z0-9._]"), "_")
        return File(rootDir, "$safe.json")
    }

    private fun String?.isNullOrBlankCompat(): Boolean = this == null || isBlank()
}

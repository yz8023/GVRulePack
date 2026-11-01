package io.github.gvrulepack.common

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser

/**
 * GodViewer 导出 bundle 的宽松解析器。
 *
 * 兼容 format = "godviewer-rules-bundle"（format_version ≤ 1）；
 * 单条规则损坏时跳过该条并记录错误，不让整包导入失败。
 */
object RuleBundleParser {

    private val gson = Gson()

    data class ParsedAppEntry(
        val packageName: String,
        val label: String?,
        val viewRules: List<ViewRule>,
        val dialogRules: List<ViewRule>,
    )

    data class ParseResult(
        val apps: List<ParsedAppEntry>,
        val errors: List<String>,
        val exportedAt: Long?,
    ) {
        val ok: Boolean get() = apps.isNotEmpty()
    }

    fun parse(json: String): ParseResult {
        val errors = ArrayList<String>()
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrElse {
            return ParseResult(emptyList(), listOf("JSON 解析失败：${it.message}"), null)
        }

        val format = root.str("format")
        if (format != RuleFormat.BUNDLE_FORMAT) {
            return ParseResult(
                emptyList(),
                listOf("格式不匹配：需要 ${RuleFormat.BUNDLE_FORMAT}，实际为 ${format ?: "(空)"}"),
                null,
            )
        }
        val version = runCatching { root.get("format_version")?.asInt }.getOrNull() ?: 0
        if (version > RuleFormat.BUNDLE_FORMAT_VERSION) {
            errors += "警告：bundle 版本 $version 高于本模块支持的 ${RuleFormat.BUNDLE_FORMAT_VERSION}，尽力解析"
        }

        val exportedAt = runCatching { root.get("exported_at")?.asLong }.getOrNull()
        val appsEl = root.get("apps")
        if (appsEl == null || !appsEl.isJsonArray) {
            return ParseResult(emptyList(), errors + listOf("缺少 apps 数组"), exportedAt)
        }

        val apps = ArrayList<ParsedAppEntry>()
        for ((index, el) in appsEl.asJsonArray.withIndex()) {
            try {
                val obj = el.asJsonObject
                val pkg = obj.str("package")
                if (pkg.isNullOrBlank()) {
                    errors += "第 ${index + 1} 个应用缺少 package，已跳过"
                    continue
                }
                apps += ParsedAppEntry(
                    packageName = pkg,
                    label = obj.str("label"),
                    viewRules = parseRules(obj.get("view_rules"), "$pkg/view", errors),
                    dialogRules = parseRules(obj.get("dialog_rules"), "$pkg/dialog", errors),
                )
            } catch (t: Throwable) {
                errors += "第 ${index + 1} 个应用解析失败：${t.message}"
            }
        }
        if (apps.isEmpty()) {
            errors += "bundle 中没有可用的应用条目"
        }
        return ParseResult(apps, errors, exportedAt)
    }

    private fun parseRules(el: JsonElement?, where: String, errors: MutableList<String>): List<ViewRule> {
        if (el == null || el.isJsonNull) return emptyList()
        if (!el.isJsonArray) {
            errors += "$where 不是数组，已忽略"
            return emptyList()
        }
        val out = ArrayList<ViewRule>()
        for ((i, ruleEl) in el.asJsonArray.withIndex()) {
            runCatching {
                val rule = gson.fromJson(ruleEl, ViewRule::class.java)
                if (rule != null && rule.activityClass.isNotBlank() && rule.viewClass.isNotBlank()) {
                    out += rule
                } else {
                    errors += "$where 第 $i 条规则缺少标识字段，已跳过"
                }
            }.onFailure {
                errors += "$where 第 $i 条规则解析失败：${it.message}"
            }
        }
        return out
    }

    private fun JsonElement.str(key: String): String? {
        if (!isJsonObject) return null
        val el = asJsonObject.get(key) ?: return null
        if (el.isJsonNull) return null
        return runCatching { el.asString }.getOrNull()
    }
}

package io.github.gvrulepack.common

import com.google.gson.annotations.SerializedName

/**
 * 数据模型与 GodViewer 导出的 `godviewer-rules-bundle` v1 格式一一对应
 * （字段名、语义与上游 app/src/main/java/com/godviewer/app/shared/model/ViewRule.kt 保持一致）。
 */

/** GodViewer 导出的规则包。 */
data class RulesBundle(
    @SerializedName("format") val format: String? = null,
    @SerializedName("format_version") val formatVersion: Int = 0,
    @SerializedName("exported_at") val exportedAt: Long = 0L,
    @SerializedName("apps") val apps: List<BundleApp> = emptyList(),
)

/** 规则包内单个应用的条目。 */
data class BundleApp(
    @SerializedName("package") val packageName: String? = null,
    @SerializedName("label") val label: String? = null,
    @SerializedName("view_rules") val viewRules: List<ViewRule> = emptyList(),
    @SerializedName("dialog_rules") val dialogRules: List<ViewRule> = emptyList(),
)

/** 一条持久化的视图修改规则（移植自 GodViewer ViewRule）。 */
data class ViewRule(
    // ---------- 标识 ----------
    @SerializedName("package_name")
    val packageName: String,
    @SerializedName("match_version_code")
    val matchVersionCode: Int,
    @SerializedName("activity_class")
    val activityClass: String,
    @SerializedName("view_class")
    val viewClass: String,
    /** 自 decorView 向下的逐层 childIndex 路径 */
    @SerializedName("depth")
    val depth: List<Int>,
    @SerializedName("res_name")
    val resourceName: String?,
    @SerializedName("text")
    val text: String?,
    @SerializedName("description")
    val description: String?,
    // ---------- 状态 ----------
    @SerializedName("original")
    var original: ViewAttrSnapshot,
    @SerializedName("modified")
    var modified: ViewAttrSnapshot,
    // ---------- 生效范围 ----------
    @SerializedName("changed_size")
    var changedSize: Boolean = false,
    @SerializedName("changed_margin")
    var changedMargin: Boolean = false,
    @SerializedName("changed_padding")
    var changedPadding: Boolean = false,
    @SerializedName("changed_visibility")
    var changedVisibility: Boolean = false,
    @SerializedName("changed_text")
    var changedText: Boolean = false,
    @SerializedName("changed_hint")
    var changedHint: Boolean = false,
    @SerializedName("changed_image")
    var changedImage: Boolean = false,
    @SerializedName("timestamp")
    var timestamp: Long = 0L,
) {
    /** 视图唯一键：同一 Activity 内 depth 路径 + 视图类名 */
    data class RuleKey(val activityClass: String, val depth: List<Int>, val viewClass: String)

    fun key(): RuleKey = RuleKey(activityClass, depth, viewClass)

    /** 轻量去重键 */
    fun dedupKey(): String = "$activityClass|${depth.joinToString(",")}|$viewClass"
}

/** 视图属性快照（像素值），字段与 GodViewer 完全一致。 */
data class ViewAttrSnapshot(
    @SerializedName("width") val width: Int,
    @SerializedName("height") val height: Int,
    @SerializedName("margin_left") val marginLeft: Int,
    @SerializedName("margin_top") val marginTop: Int,
    @SerializedName("margin_right") val marginRight: Int,
    @SerializedName("margin_bottom") val marginBottom: Int,
    @SerializedName("padding_left") val paddingLeft: Int,
    @SerializedName("padding_top") val paddingTop: Int,
    @SerializedName("padding_right") val paddingRight: Int,
    @SerializedName("padding_bottom") val paddingBottom: Int,
    @SerializedName("visibility") val visibility: Int,
    @SerializedName("text") val text: String?,
    @SerializedName("hint") val hint: String?,
    @SerializedName("max_lines") val maxLines: Int,
    @SerializedName("image_url") val imageUrl: String?,
    @SerializedName("scale_type") val scaleType: String?,
)

/** 宿主侧持久化的单个应用规则（工厂 App 内使用）。 */
data class StoredApp(
    @SerializedName("package") val packageName: String,
    @SerializedName("label") val label: String? = null,
    @SerializedName("view_rules") val viewRules: List<ViewRule> = emptyList(),
    @SerializedName("dialog_rules") val dialogRules: List<ViewRule> = emptyList(),
    @SerializedName("imported_at") val importedAt: Long = 0L,
)

/** 注入侧加载到的规则集合。 */
data class ParsedRemoteRules(
    val viewRules: List<ViewRule>,
    val dialogRules: List<ViewRule>,
) {
    companion object {
        val EMPTY = ParsedRemoteRules(emptyList(), emptyList())
    }
}

/** 规则合并工具：按 [ViewRule.dedupKey] 去重，后者覆盖前者。 */
object RuleMerge {
    fun merge(existing: List<ViewRule>, incoming: List<ViewRule>): List<ViewRule> {
        if (existing.isEmpty()) return incoming
        if (incoming.isEmpty()) return existing
        val map = LinkedHashMap<String, ViewRule>(existing.size + incoming.size)
        existing.forEach { map[it.dedupKey()] = it }
        incoming.forEach { map[it.dedupKey()] = it }
        return map.values.toList()
    }
}

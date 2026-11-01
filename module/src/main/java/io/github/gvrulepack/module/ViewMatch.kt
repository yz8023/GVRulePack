package io.github.gvrulepack.module

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.TextView
import io.github.gvrulepack.common.ViewRule

/**
 * 视图定位与匹配（移植自上游 GodViewer 的 ViewRuleUtil / findViewBestMatch，
 * 匹配顺序与语义保持一致，保证导入规则的命中率和 GodViewer 本体重放相同）。
 */

/** 自 decorView 向下逐层 childIndex 组成的路径 */
fun getViewHierarchyDepth(view: View): List<Int> {
    val depth = ArrayList<Int>()
    var v: View = view
    var parent: ViewParent? = view.parent
    while (parent is ViewGroup) {
        depth.add(0, parent.indexOfChild(v))
        v = parent
        parent = v.parent
    }
    return depth
}

/** 从根 View 按 depth 路径定位视图 */
fun findViewByDepth(root: View, depth: List<Int>): View? {
    var view: View = root
    for (index in depth) {
        view = if (view is ViewGroup && index in 0 until view.childCount) {
            view.getChildAt(index)
        } else {
            return null
        }
    }
    return view
}

/** 递归按文字查找视图 */
fun findViewByText(root: View, text: String): View? {
    if (root is TextView && root.text?.toString() == text) {
        return root
    }
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) {
            findViewByText(root.getChildAt(i), text)?.let { return it }
        }
    }
    return null
}

/** 从视图上溯 Context 链找到所属 Activity */
fun getAttachedActivityFromView(view: View): Activity? {
    getActivityFromViewContext(view.context)?.let { return it }
    val parent = view.parent
    return if (parent is ViewGroup) getAttachedActivityFromView(parent) else null
}

private fun getActivityFromViewContext(context: Context): Activity? {
    if (context is Activity) {
        return context
    }
    if (context is ContextWrapper) {
        // 某些 App（如微信）getBaseContext 返回自身，兜底防死循环
        val base = context.baseContext
        return if (base == context) null else getActivityFromViewContext(base)
    }
    return null
}

/** 目标应用当前版本号 */
fun versionCode(context: Context): Int {
    return try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    } catch (e: PackageManager.NameNotFoundException) {
        0
    }
}

/** 视图资源名（如 "com.example:id/title"），无资源 id 时为 null */
fun resourceNameOf(view: View): String? {
    if (view.id == View.NO_ID) {
        return null
    }
    return try {
        view.resources.getResourceName(view.id)
    } catch (e: Exception) {
        null
    }
}

/** 把 "pkg:type/entry" 形式的资源名解析为资源 id */
fun resolveResourceId(root: View, resourceName: String): Int {
    return try {
        val start = resourceName.split(":")
        if (start.size < 2) return View.NO_ID
        val end = start[1].split("/")
        if (end.size < 2) return View.NO_ID
        root.resources.getIdentifier(end[1], end[0], start[0])
    } catch (e: Exception) {
        View.NO_ID
    }
}

/**
 * 在 Activity 中按规则定位视图（与 GodViewer 完全一致的匹配顺序）：
 *
 * 1. depth 路径（版本一致 = 严格模式；版本不一致时同时校验资源名，避免升级后误匹配）
 * 2. resourceName 兜底
 * 3. text 兜底
 *
 * 每个候选都校验 viewClass。
 */
fun findViewBestMatch(activity: Activity, rule: ViewRule): View? {
    val strict = versionCode(activity) == rule.matchVersionCode
    val resName = rule.resourceName
    val ruleText = rule.text
    // 1) depth 优先
    findViewByDepth(activity.window.decorView, rule.depth)?.let { view ->
        if (view.javaClass.name == rule.viewClass) {
            if (strict || resName.isNullOrEmpty() || resourceNameOf(view) == resName) {
                return view
            }
        }
    }
    // 2) resourceName 兜底
    if (!resName.isNullOrEmpty()) {
        val id = resolveResourceId(activity.window.decorView, resName)
        if (id != View.NO_ID) {
            activity.findViewById<View>(id)?.let { view ->
                if (view.javaClass.name == rule.viewClass) {
                    return view
                }
            }
        }
    }
    // 3) text 兜底
    if (!ruleText.isNullOrEmpty()) {
        findViewByText(activity.window.decorView, ruleText)?.let { view ->
            if (view.javaClass.name == rule.viewClass) {
                return view
            }
        }
    }
    return null
}

/**
 * 在任意窗口根（对话框 / PopupWindow 的 decorView）内按规则定位视图。
 * dialog_rules 的 depth 路径按该根解释；再退到 res_name / text。
 */
fun findViewInRoot(root: View, rule: ViewRule): View? {
    val resName = rule.resourceName
    val ruleText = rule.text
    findViewByDepth(root, rule.depth)?.let { view ->
        if (view.javaClass.name == rule.viewClass) {
            if (resName.isNullOrEmpty() || resourceNameOf(view) == resName) {
                return view
            }
        }
    }
    if (!resName.isNullOrEmpty()) {
        val id = resolveResourceId(root, resName)
        if (id != View.NO_ID) {
            val view = root.findViewById<View>(id)
            if (view != null && view.javaClass.name == rule.viewClass) {
                return view
            }
        }
    }
    if (!ruleText.isNullOrEmpty()) {
        findViewByText(root, ruleText)?.let { view ->
            if (view.javaClass.name == rule.viewClass) {
                return view
            }
        }
    }
    return null
}

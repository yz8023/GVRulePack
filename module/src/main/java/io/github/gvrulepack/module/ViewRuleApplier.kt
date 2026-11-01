package io.github.gvrulepack.module

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import io.github.gvrulepack.common.ViewRule

/**
 * 把 [ViewRule] 的 modified 快照应用到具体 View（移植自上游 GodViewer 的 ViewRuleApplier）。
 *
 * 差异：图片加载不依赖目标应用内的 Glide，改用 [AsyncImageLoader]（模块自带的简易下载器），
 * 其余属性应用逻辑与 GodViewer 保持一致。
 *
 * 不做持久化；所有调用点都 runCatching 包裹，任何失败不影响目标应用。
 */
object ViewRuleApplier {

    /** 重放：把规则的修改值应用到视图。返回是否发生了布局变化。 */
    fun applyRuleToView(view: View, rule: ViewRule): Boolean {
        return runCatching {
            var changed = false
            val s = rule.modified
            val lp = view.layoutParams

            if (rule.changedSize && lp != null) {
                if (lp.width != s.width || lp.height != s.height) {
                    lp.width = s.width
                    lp.height = s.height
                    changed = true
                }
            }
            if (rule.changedMargin && lp is ViewGroup.MarginLayoutParams) {
                if (lp.leftMargin != s.marginLeft || lp.topMargin != s.marginTop ||
                    lp.rightMargin != s.marginRight || lp.bottomMargin != s.marginBottom
                ) {
                    lp.setMargins(s.marginLeft, s.marginTop, s.marginRight, s.marginBottom)
                    changed = true
                }
            }
            if (changed) {
                view.layoutParams = lp
            }
            if (rule.changedPadding) {
                if (view.paddingLeft != s.paddingLeft || view.paddingTop != s.paddingTop ||
                    view.paddingRight != s.paddingRight || view.paddingBottom != s.paddingBottom
                ) {
                    view.setPadding(s.paddingLeft, s.paddingTop, s.paddingRight, s.paddingBottom)
                }
            }
            if (rule.changedVisibility && view.visibility != s.visibility) {
                view.visibility = s.visibility
            }
            if (rule.changedText && view is TextView) {
                if (view.text?.toString() != s.text) {
                    view.text = s.text ?: ""
                }
                if (view.maxLines != s.maxLines) {
                    view.maxLines = s.maxLines
                }
            }
            if (rule.changedHint && view is TextView) {
                val hint = s.hint ?: ""
                if (view.hint?.toString() != hint) {
                    view.hint = hint
                }
            }
            if (rule.changedImage && view is ImageView) {
                val url = s.imageUrl
                if (!url.isNullOrEmpty()) {
                    // 布局回调里会反复调用：缓存命中时同步/近同步返回，只有首帧才真正下载
                    AsyncImageLoader.load(view, url)
                }
                s.scaleType?.let { name ->
                    runCatching { ImageView.ScaleType.valueOf(name) }
                        .getOrNull()
                        ?.let { view.scaleType = it }
                }
            }
            changed
        }.getOrDefault(false)
    }
}

package io.github.gvrulepack.app.module

import android.app.Activity
import android.app.Dialog
import android.content.pm.ApplicationInfo
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.widget.PopupWindow
import io.github.gvrulepack.app.shared.ParsedRemoteRules
import io.github.libxposed.api.XposedInterface
import java.util.WeakHashMap

/**
 * 规则自动重放引擎（移植自上游 GodViewer 的 ActivityLifecycleHooker 重放路径）：
 *
 * - hook `Activity.onPostResume`：Activity 恢复时应用该 Activity 的全部 view_rules
 * - 每个 Activity 注册一次 `OnGlobalLayoutListener`：列表刷新 / 数据加载后重新应用
 * - `onDestroy` 时移除监听，WeakHashMap 防泄漏
 * - hook `Dialog.show` / `PopupWindow.invokePopup`：dialog_rules 尽力重放
 *
 * 与 GodViewer 的编辑模式不同，本模块是纯被动重放：不拦截触摸、不弹 UI、不常驻通知。
 */
object ReplayEngine {

    private const val TAG = "GVRulePack"

    @Volatile
    internal var module: XposedInterface? = null

    @Volatile
    internal var moduleAppInfo: ApplicationInfo? = null

    private val layoutListeners = WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>()
    private var popupDecorFieldReady = false
    private var popupDecorField: java.lang.reflect.Field? = null

    @Volatile
    private var installedForPackage: String? = null

    @Volatile
    private var rules: ParsedRemoteRules = ParsedRemoteRules.EMPTY

    @Volatile
    private var rulesLoaded = false

    /** 安装全部 Hook（每个进程一次）。 */
    fun install(packageName: String) {
        if (installedForPackage == packageName) return
        val base = module ?: run {
            Log.e(TAG, "install skipped: framework not attached")
            return
        }
        installedForPackage = packageName

        val okResume = HookAdapter.hookAfter(
            base,
            Activity::class.java.getDeclaredMethod("onPostResume"),
        ) { param ->
            val activity = param.thisObject as? Activity ?: return@hookAfter
            runCatching {
                replayActivity(activity)
                registerLayoutListener(activity)
            }.onFailure { Log.e(TAG, "onPostResume replay failed", it) }
        }

        val okDestroy = HookAdapter.hookAfter(
            base,
            Activity::class.java.getDeclaredMethod("onDestroy"),
        ) { param ->
            val activity = param.thisObject as? Activity ?: return@hookAfter
            runCatching {
                unregisterLayoutListener(activity)
            }.onFailure { Log.e(TAG, "onDestroy cleanup failed", it) }
        }

        val okDialog = HookAdapter.hookAfter(
            base,
            Dialog::class.java.getDeclaredMethod("show"),
        ) { param ->
            val dialog = param.thisObject as? Dialog ?: return@hookAfter
            runCatching { replayDialogLike(dialog.window?.decorView) }
                .onFailure { Log.e(TAG, "dialog replay failed", it) }
        }

        var okPopup = false
        runCatching {
            PopupWindow::class.java.getDeclaredMethod(
                "invokePopup",
                android.view.WindowManager.LayoutParams::class.java,
            )
        }.getOrNull()?.let { method ->
            okPopup = HookAdapter.hookAfter(base, method) { param ->
                val popup = param.thisObject as? PopupWindow ?: return@hookAfter
                runCatching { replayDialogLike(popupDecorView(popup)) }
                    .onFailure { Log.e(TAG, "popup replay failed", it) }
            }
        }

        Log.i(TAG, "installed pkg=$packageName hooks resume=$okResume destroy=$okDestroy dialog=$okDialog popup=$okPopup")
    }

    // ------------------------------------------------------------ 规则加载

    private fun ensureRules(context: android.content.Context): ParsedRemoteRules {
        if (rulesLoaded) return rules
        synchronized(this) {
            if (rulesLoaded) return rules
            val loaded = RuleSource.load(context, moduleAppInfo)
            rules = loaded
            rulesLoaded = true
            Log.i(
                TAG,
                "rules loaded pkg=${context.packageName} view=${loaded.viewRules.size} " +
                    "dialog=${loaded.dialogRules.size}",
            )
            return rules
        }
    }

    // ------------------------------------------------------------ Activity 重放

    private fun replayActivity(activity: Activity) {
        val loaded = ensureRules(activity)
        if (loaded.viewRules.isEmpty()) return
        val activityClass = activity.componentName?.className ?: return
        for (rule in loaded.viewRules) {
            if (rule.activityClass != activityClass) continue
            runCatching {
                val view = findViewBestMatch(activity, rule) ?: return@runCatching
                ViewRuleApplier.applyRuleToView(view, rule)
            }.onFailure { Log.e(TAG, "apply rule failed key=${rule.dedupKey()}", it) }
        }
    }

    private fun registerLayoutListener(activity: Activity) {
        if (layoutListeners.containsKey(activity)) return
        val decor = runCatching { activity.window?.decorView }.getOrNull() ?: return
        val vto = runCatching { decor.viewTreeObserver }.getOrNull() ?: return
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            runCatching { replayActivity(activity) }
                .onFailure { Log.e(TAG, "layout replay failed", it) }
        }
        runCatching { vto.addOnGlobalLayoutListener(listener) }
            .onSuccess { layoutListeners[activity] = listener }
            .onFailure { Log.e(TAG, "addOnGlobalLayoutListener failed", it) }
    }

    private fun unregisterLayoutListener(activity: Activity) {
        layoutListeners.remove(activity)?.let { listener ->
            runCatching {
                activity.window?.decorView?.viewTreeObserver?.removeOnGlobalLayoutListener(listener)
            }
        }
    }

    // ------------------------------------------------------- Dialog / Popup 重放

    /**
     * 在对话框 / PopupWindow 的 decor 内重放 dialog_rules。
     * depth 按该 decor 为根解释；若规则记录了宿主 Activity 类名，则仅当其匹配时应用。
     */
    private fun replayDialogLike(decor: View?) {
        if (decor == null) return
        val loaded = ensureRules(decor.context)
        if (loaded.dialogRules.isEmpty()) return
        val hostActivity = getAttachedActivityFromView(decor)
        val hostName = hostActivity?.javaClass?.name

        fun applyAll() {
            for (rule in loaded.dialogRules) {
                if (rule.activityClass.isNotBlank() && hostName != null && rule.activityClass != hostName) {
                    continue
                }
                runCatching {
                    val view = findViewInRoot(decor, rule) ?: return@runCatching
                    ViewRuleApplier.applyRuleToView(view, rule)
                }.onFailure { Log.e(TAG, "dialog rule failed key=${rule.dedupKey()}", it) }
            }
        }

        applyAll()
        // 对话框首次 show 时内容可能还没布局完，post 一帧再试
        decor.post { runCatching { applyAll() } }
    }

    private fun popupDecorView(popup: PopupWindow): View? {
        if (!popupDecorFieldReady) {
            popupDecorField = runCatching {
                PopupWindow::class.java.getDeclaredField("mDecorView").apply { isAccessible = true }
            }.getOrNull()
            popupDecorFieldReady = true
        }
        return runCatching { popupDecorField?.get(popup) as? View }.getOrNull()
    }
}

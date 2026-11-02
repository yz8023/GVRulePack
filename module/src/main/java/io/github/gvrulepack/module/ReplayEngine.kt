package io.github.gvrulepack.module

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.widget.PopupWindow
import de.robv.android.xposed.XposedBridge
import io.github.gvrulepack.common.ParsedRemoteRules
import java.util.WeakHashMap

/**
 * 规则自动重放引擎（移植自上游 GodViewer 的 ActivityLifecycleHooker 重放路径）：
 *
 * - hook `Activity.onPostResume`：应用该 Activity 的全部 view_rules
 * - 每个 Activity 注册一次 `OnGlobalLayoutListener`：布局变化后重新应用
 * - `onDestroy` 时移除监听，WeakHashMap 防泄漏
 * - hook `Dialog.show` / `PopupWindow.invokePopup`：dialog_rules 尽力重放
 *
 * 纯被动重放：不拦截触摸、不弹 UI、不发通知。
 *
 * 日志走 logcat（tag=GVRulePack）+ XposedBridge.log（LSPosed 日志）双写，
 * 「模块装没装上 / 规则读没读到 / hook 成没成功」全部可诊断。
 */
object ReplayEngine {

    private const val TAG = "GVRulePack"

    /** 模块自身 APK 路径（initZygote 提供；读 assets/rulepack.json 用） */
    @Volatile
    internal var modulePath: String? = null

    private val layoutListeners = WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>()
    private var popupDecorFieldReady = false
    private var popupDecorField: java.lang.reflect.Field? = null

    /** hook 是进程级的：装过一次就不再装（与加载规则的包名解耦，防同进程多包重复 hook） */
    @Volatile
    private var hooksInstalled = false

    @Volatile
    private var rules: ParsedRemoteRules = ParsedRemoteRules.EMPTY

    @Volatile
    private var rulesLoaded = false

    /** 当前缓存规则对应的包名（同进程加载第二个包时必须重载，否则规则打错包） */
    @Volatile
    private var rulesPkg: String? = null

    /** 布局回调合并窗口（ms）：GlobalLayout 风暴时只在尾部执行一次重放 */
    private const val LAYOUT_THROTTLE_MS = 16L

    private val pendingReplays = WeakHashMap<Activity, Runnable>()

    /** 安装全部 Hook（每个进程一次）。 */
    fun install(packageName: String) {
        if (hooksInstalled) {
            slog("install skipped (hooks already installed), pkg=$packageName")
            return
        }
        hooksInstalled = true

        val okResume = HookAdapter.hookAfter(
            Activity::class.java.getDeclaredMethod("onPostResume"),
        ) { param ->
            val activity = param.thisObject as? Activity ?: return@hookAfter
            runCatching {
                replayActivity(activity)
                registerLayoutListener(activity)
            }.onFailure { logE("onPostResume replay failed", it) }
        }

        val okDestroy = HookAdapter.hookAfter(
            Activity::class.java.getDeclaredMethod("onDestroy"),
        ) { param ->
            val activity = param.thisObject as? Activity ?: return@hookAfter
            runCatching {
                unregisterLayoutListener(activity)
            }.onFailure { logE("onDestroy cleanup failed", it) }
        }

        val okDialog = HookAdapter.hookAfter(
            Dialog::class.java.getDeclaredMethod("show"),
        ) { param ->
            val dialog = param.thisObject as? Dialog ?: return@hookAfter
            runCatching { replayDialogLike(dialog.window?.decorView) }
                .onFailure { logE("dialog replay failed", it) }
        }

        var okPopup = false
        runCatching {
            PopupWindow::class.java.getDeclaredMethod(
                "invokePopup",
                android.view.WindowManager.LayoutParams::class.java,
            )
        }.getOrNull()?.let { method ->
            okPopup = HookAdapter.hookAfter(method) { param ->
                val popup = param.thisObject as? PopupWindow ?: return@hookAfter
                runCatching { replayDialogLike(popupDecorView(popup)) }
                    .onFailure { logE("popup replay failed", it) }
            }
        }

        slog("installed pkg=$packageName hooks resume=$okResume destroy=$okDestroy dialog=$okDialog popup=$okPopup")
    }

    // ------------------------------------------------------------ 规则加载

    private fun ensureRules(context: Context): ParsedRemoteRules {
        val pkg = context.packageName
        if (rulesLoaded && rulesPkg == pkg) return rules
        synchronized(this) {
            if (rulesLoaded && rulesPkg == pkg) return rules
            val loaded = EmbeddedRules.load(pkg, modulePath)
            rules = loaded
            rulesPkg = pkg
            rulesLoaded = true
            slog(
                "rules loaded pkg=$pkg view=${loaded.viewRules.size} " +
                    "dialog=${loaded.dialogRules.size} modulePath=${modulePath != null}",
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
            }.onFailure { logE("apply rule failed key=${rule.dedupKey()}", it) }
        }
    }

    private fun registerLayoutListener(activity: Activity) {
        if (layoutListeners.containsKey(activity)) return
        val decor = runCatching { activity.window?.decorView }.getOrNull() ?: return
        val vto = runCatching { decor.viewTreeObserver }.getOrNull() ?: return
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            // 风暴节流：布局高频触发时合并为尾部一次重放（保底仍执行，不丢最终状态）
            runCatching {
                val decor = activity.window?.decorView ?: return@runCatching
                val runnable = pendingReplays.getOrPut(activity) {
                    Runnable {
                        pendingReplays.remove(activity)
                        runCatching { replayActivity(activity) }
                            .onFailure { logE("layout replay failed", it) }
                    }
                }
                decor.removeCallbacks(runnable)
                decor.postDelayed(runnable, LAYOUT_THROTTLE_MS)
            }.onFailure { logE("layout replay failed", it) }
        }
        runCatching { vto.addOnGlobalLayoutListener(listener) }
            .onSuccess { layoutListeners[activity] = listener }
            .onFailure { logE("addOnGlobalLayoutListener failed", it) }
    }

    private fun unregisterLayoutListener(activity: Activity) {
        layoutListeners.remove(activity)?.let { listener ->
            runCatching {
                val decor = activity.window?.decorView
                decor?.viewTreeObserver?.removeOnGlobalLayoutListener(listener)
                pendingReplays.remove(activity)?.let { decor?.removeCallbacks(it) }
            }
        }
    }

    // ------------------------------------------------------- Dialog / Popup 重放

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
                }.onFailure { logE("dialog rule failed key=${rule.dedupKey()}", it) }
            }
        }

        applyAll()
        decor.post { runCatching { applyAll() } }
    }

    private fun popupDecorView(popup: PopupWindow): View? {
        if (!popupDecorFieldReady) {
            val field = runCatching {
                PopupWindow::class.java.getDeclaredField("mDecorView").apply { isAccessible = true }
            }.getOrNull()
            if (field == null) {
                logE("PopupWindow.mDecorView reflection failed; popup rules will not replay on this API")
            }
            popupDecorField = field
            popupDecorFieldReady = true
        }
        return runCatching { popupDecorField?.get(popup) as? View }.getOrNull()
    }

    // --------------------------------------------------------------- 日志双写

    private fun slog(msg: String) {
        Log.i(TAG, msg)
        runCatching { XposedBridge.log("[GVRulePack] $msg") }
    }

    private fun logE(msg: String, tr: Throwable? = null) {
        Log.e(TAG, msg, tr)
        runCatching { XposedBridge.log("[GVRulePack] $msg" + (tr?.let { ": $it" } ?: "")) }
    }
}

package io.github.gvrulepack.module

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method

/**
 * 传统 Xposed API 的 after-hook 适配层。
 * 成功/失败都留日志，方便在 LSPosed 日志里诊断「装没装上」。
 */
object HookAdapter {

    /** hook [method] 的 after 回调；返回是否成功。 */
    fun hookAfter(method: Method, callback: (XC_MethodHook.MethodHookParam) -> Unit): Boolean {
        return runCatching {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { callback(param) }
                        .onFailure { logE("hook callback error ${method.name}: $it") }
                }
            })
            true
        }.onFailure {
            logE("hook failed: ${method.declaringClass.name}#${method.name}", it)
        }.getOrDefault(false)
    }

    private fun logE(msg: String, tr: Throwable? = null) {
        android.util.Log.e("GVRulePack", msg, tr)
        runCatching { XposedBridge.log("[GVRulePack] $msg" + (tr?.let { ": $it" } ?: "")) }
    }
}

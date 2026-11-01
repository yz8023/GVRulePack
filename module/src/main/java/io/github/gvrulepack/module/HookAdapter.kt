package io.github.gvrulepack.module

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

/**
 * libxposed API 102 `Chain` 模型 → 简单 after 回调的适配层
 * （语义对齐上游 GodViewer 的 GvHook，只保留本模块用得到的 after 路径）。
 *
 * Hook 体异常由框架按 module.prop 的 exceptionMode=protective 吞掉并记录，
 * 绝不拖垮目标应用。
 */
object HookAdapter {

    class AfterParam(
        val thisObject: Any?,
        val args: Array<Any?>,
        val result: Any?,
    )

    /** 安装 after Hook；失败只记日志，返回 false。 */
    fun hookAfter(
        base: XposedInterface?,
        method: Executable,
        after: (AfterParam) -> Unit,
    ): Boolean {
        if (base == null) return false
        return runCatching {
            base.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    var result: Any? = null
                    var error: Throwable? = null
                    try {
                        result = chain.proceed(chain.args.toTypedArray())
                    } catch (t: Throwable) {
                        error = t
                    }
                    runCatching {
                        after(AfterParam(chain.thisObject, chain.args.toTypedArray(), result))
                    }
                    if (error != null) throw error
                    result
                }
            true
        }.onFailure {
            android.util.Log.e("GVRulePack", "hook failed: ${method.declaringClass.name}#${method.name}", it)
        }.getOrDefault(false)
    }
}

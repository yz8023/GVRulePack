package io.github.gvrulepack.module

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import de.robv.android.xposed.XposedBridge

/**
 * 传统 Xposed 模块入口（assets/xposed_init 声明，LSPosed / EdXposed / 太极全兼容）。
 *
 * 由「GV 规则工坊」导出的独立规则模块：规则全部预置在本 APK 的
 * assets/rulepack.json 中，无需宿主 App、无需 Provider，安装勾选作用域即生效。
 *
 * - initZygote：拿到模块 APK 自身路径（读取预置规则用）
 * - handleLoadPackage：在作用域进程安装重放 Hook
 */
class RulePackModule : IXposedHookLoadPackage, IXposedHookZygoteInit {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        ReplayEngine.modulePath = startupParam.modulePath
        log("initZygote modulePath=${startupParam.modulePath}")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (ReplayEngine.modulePath == null) {
            // 兜底：个别框架未回调 initZygote 时，尝试 LSPosed 扩展字段
            ReplayEngine.modulePath = runCatching {
                XC_LoadPackage.LoadPackageParam::class.java
                    .getDeclaredField("moduleAppInfo")
                    .get(lpparam)
                    ?.let { it as android.content.pm.ApplicationInfo }
                    ?.sourceDir
            }.getOrNull()
        }
        log("loadPackage pkg=${lpparam.packageName} proc=${lpparam.processName}")
        ReplayEngine.install(lpparam.packageName)
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        runCatching { XposedBridge.log("[GVRulePack] $msg") }
    }

    companion object {
        private const val TAG = "GVRulePack"
    }
}

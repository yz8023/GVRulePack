package io.github.gvrulepack.module

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * libxposed API 102 模块入口（META-INF/xposed/java_init.list 声明，框架反射创建）。
 *
 * 由「GV 规则包工厂」导出的独立规则模块：规则全部预置在本 APK 的
 * assets/rulepack.json 中，无需宿主 App、无需 Provider，安装勾选作用域即生效。
 */
class RulePackModule : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        ReplayEngine.module = this
        ReplayEngine.moduleAppInfo = runCatching { getModuleApplicationInfo() }.getOrNull()
        Log.i(
            TAG,
            "module loaded process=${param.processName} api=${getApiVersion()} " +
                "framework=${getFrameworkName()}/${getFrameworkVersion()} " +
                "modulePkg=${ReplayEngine.moduleAppInfo?.packageName}",
        )
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        maybeInstall(param.packageName)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (!param.isFirstPackage) return
        maybeInstall(param.packageName)
    }

    private fun maybeInstall(packageName: String) {
        // 跳过模块自身进程（用运行时包名，改包名后依然正确）
        val modulePkg = ReplayEngine.moduleAppInfo?.packageName
        if (modulePkg != null && packageName == modulePkg) return
        runCatching { ReplayEngine.install(packageName) }
            .onFailure { Log.e(TAG, "install failed pkg=$packageName", it) }
    }

    companion object {
        private const val TAG = "GVRulePack"
    }
}

package io.github.gvrulepack.app.module

import android.util.Log
import io.github.gvrulepack.app.BuildConfig
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * libxposed API 102 模块入口（META-INF/xposed/java_init.list 声明，框架反射创建）。
 *
 * 职责极简：记录模块 APK 路径（读预置 assets 用），包 ready 时给 [ReplayEngine]
 * 装 Activity / Dialog / PopupWindow 的重放 Hook。
 * 不注入编辑模式、不拦截触摸、不发通知。
 */
class RulePackModule : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        ReplayEngine.module = this
        ReplayEngine.moduleAppInfo = runCatching { getModuleApplicationInfo() }.getOrNull()
        Log.i(
            TAG,
            "module loaded process=${param.processName} api=${getApiVersion()} " +
                "framework=${getFrameworkName()}/${getFrameworkVersion()}",
        )
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        // 与 GodViewer 相同：部分实现下这是最早的回调时机；install 内部按包幂等
        maybeInstall(param.packageName)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // 框架类 Hook 每进程装一次即可（同 GodViewer：非首包跳过）
        if (!param.isFirstPackage) return
        maybeInstall(param.packageName)
    }

    private fun maybeInstall(packageName: String) {
        if (packageName == BuildConfig.APPLICATION_ID) return
        runCatching { ReplayEngine.install(packageName) }
            .onFailure { Log.e(TAG, "install failed pkg=$packageName", it) }
    }

    companion object {
        private const val TAG = "GVRulePack"
    }
}

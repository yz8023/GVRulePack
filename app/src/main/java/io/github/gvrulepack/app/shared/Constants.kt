package io.github.gvrulepack.app.shared

import io.github.gvrulepack.app.BuildConfig

/**
 * 全局常量：宿主 UI、Provider、注入侧共用。
 *
 * 注意：authority 不硬编码包名，而是在运行时由包名推导——
 * 这样发布构建（-PcfgApplicationId=...）与未来「像 SimpleHook 那样的二进制改包导出」
 * 改了 applicationId 之后，Provider authority 依然与 Manifest 中 ${applicationId}.rules 一致。
 */
object Constants {

    /** ContentProvider authority（= Manifest 中 ${applicationId}.rules） */
    val RULES_AUTHORITY: String
        get() = BuildConfig.APPLICATION_ID + ".rules"

    /** 由任意 Context 推导 authority（宿主侧用；与 [RULES_AUTHORITY] 等价） */
    fun authorityOf(packageName: String): String = "$packageName.rules"

    /** Provider 返回的 JSON 列名 */
    const val COLUMN_JSON = "json"

    /** GodViewer 导出 bundle 的格式标识 */
    const val BUNDLE_FORMAT = "godviewer-rules-bundle"
    const val BUNDLE_FORMAT_VERSION = 1

    /** 预置在模块 APK assets 里的规则包文件名（打包脚本写入） */
    const val EMBEDDED_RULEPACK_ASSET = "rulepack.json"
}

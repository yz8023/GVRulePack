package io.github.gvrulepack.common

/** 格式与路径常量：工厂、模板模块、导出器共用。 */
object RuleFormat {

    /** GodViewer 导出 bundle 的格式标识 */
    const val BUNDLE_FORMAT = "godviewer-rules-bundle"
    const val BUNDLE_FORMAT_VERSION = 1

    /** 预置在模块 APK assets 里的规则包文件名 */
    const val EMBEDDED_RULEPACK_ASSET = "rulepack.json"

    /** LSPosed 推荐作用域列表在 APK 内的路径 */
    const val SCOPE_LIST_PATH = "META-INF/xposed/scope.list"

    /** 模板 APK 在工厂 assets 里的文件名 */
    const val TEMPLATE_APK_ASSET = "template.apk"

    /** 内置默认签名密钥在工厂 assets 里的文件名 */
    const val DEFAULT_KEYSTORE_ASSET = "default_sign.jks"
    const val DEFAULT_KEYSTORE_PASSWORD = "gvrulepack"
    const val DEFAULT_KEY_ALIAS = "gvrulepack"
}

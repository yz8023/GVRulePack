package io.github.gvrulepack.common

/** 格式与路径常量：工厂、模板模块、导出器共用。 */
object RuleFormat {

    /** GodViewer 导出 bundle 的格式标识 */
    const val BUNDLE_FORMAT = "godviewer-rules-bundle"
    const val BUNDLE_FORMAT_VERSION = 1

    /** 预置在模块 APK assets 里的规则包文件名（AssetManager.open 用，不带前缀） */
    const val EMBEDDED_RULEPACK_ASSET = "rulepack.json"

    /**
     * 规则文件在 APK zip 内的真实路径（含 assets/ 前缀，ZipFile 读写用）。
     * 注意：导出时必须写到这个路径——写成裸文件名会落在 zip 根部，
     * AssetManager/ZipFile 按 assets/ 前缀查找，读不到就完全不生效。
     */
    const val EMBEDDED_RULEPACK_ZIP_PATH = "assets/rulepack.json"

    /** 传统 Xposed 入口清单（assets/xposed_init，所有框架都认） */
    const val XPOSED_INIT_ASSET = "assets/xposed_init"

    /** LSPosed 模块元数据（name= 行导出时改写为模块名） */
    const val MODULE_PROP_PATH = "META-INF/xposed/module.prop"

    /** LSPosed 推荐作用域列表在 APK 内的路径 */
    const val SCOPE_LIST_PATH = "META-INF/xposed/scope.list"

    /** 模板 APK 在工厂 assets 里的文件名 */
    const val TEMPLATE_APK_ASSET = "template.apk"

    /** 内置默认签名密钥在工厂 assets 里的文件名 */
    const val DEFAULT_KEYSTORE_ASSET = "default_sign.jks"
    const val DEFAULT_KEYSTORE_PASSWORD = "gvrulepack"
    const val DEFAULT_KEY_ALIAS = "gvrulepack"
}

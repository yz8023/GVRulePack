# libxposed 入口由 META-INF/xposed/java_init.list 按类名反射创建，禁止混淆/重命名
-keep class io.github.gvrulepack.app.module.RulePackModule { <init>(); *; }

# Gson 模型（@SerializedName 与 GodViewer 导出格式一一对应）
-keep class io.github.gvrulepack.app.shared.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# compileOnly 依赖，运行时由框架提供
-dontwarn io.github.libxposed.**

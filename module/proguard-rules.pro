# libxposed 入口由 META-INF/xposed/java_init.list 按类名反射创建，禁止混淆/重命名
-keep class io.github.gvrulepack.module.RulePackModule { <init>(); *; }

# Gson 模型（随模板打进 dex，工厂改包后仍需可用）
-keep class io.github.gvrulepack.common.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

-dontwarn io.github.libxposed.**

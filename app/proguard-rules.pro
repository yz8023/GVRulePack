# Gson 模型
-keep class io.github.gvrulepack.common.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ARSCLib / apksig（纯 Java 库，R8 正常处理；防御性 dontwarn）
-dontwarn com.reandroid.**
-dontwarn com.android.apksig.**
-keep class com.reandroid.apk.ApkModule { public *; }
-keep class com.android.apksig.ApkSigner { public *; }

# 分享 Provider：manifest 引用的组件必须完整保留（曾因 androidx FileProvider 被 R8
# 裁坏导致启动即崩 Missing meta-data，已改为自实现 GvFileProvider）
-keep class io.github.gvrulepack.app.host.GvFileProvider { *; }
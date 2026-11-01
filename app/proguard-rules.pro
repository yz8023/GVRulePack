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

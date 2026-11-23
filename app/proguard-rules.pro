# LSPosed 按 assets/xposed_init 里的全名反射加载入口类，名字必须保留。
-keep class com.zaka.douyin.glass.** { *; }

# Xposed API 由 LSPosed 运行时提供，不打进包里。
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# 只做收缩和优化，不做重命名：避免 Compose/LSPosed 任何按名字走的路径被改坏。
-dontobfuscate

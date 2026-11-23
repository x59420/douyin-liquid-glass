# LSPosed loads this class by its fully-qualified name from assets/xposed_init.
# Keep its name, constructor, and hook entry method stable.
-keep class com.zaka.douyin.glass.GlassEntry { *; }

# Xposed APIs are supplied by the LSPosed runtime, not packaged into this APK.
-dontwarn de.robv.android.xposed.**

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

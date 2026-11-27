# Preserve native method bindings used by PRoot JNI.
-keepclasseswithmembernames class * { native <methods>; }

# Preserve Kotlin metadata used by reflection in AndroidX/Compose.
-keep class kotlin.Metadata { *; }

# PRoot JNI entry points are resolved by native symbol names at runtime.
-keep class id.or.oo.pr.engine.PtyNative { *; }

# Keep JavaScript bridge methods invoked reflectively by Android WebView.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ByeDPI native entry points and JNI-called socket protector must retain names.
-keep class app.jacket.mobile.byedpi.core.ByeDpiNative { *; }
-keep class app.jacket.mobile.byedpi.core.ByedpiVpnSocketProtector { *; }
-keepclassmembers class app.jacket.mobile.byedpi.core.ByedpiVpnSocketProtector {
    public static void protectSocket(int);
}

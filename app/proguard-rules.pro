# Preserve native method bindings used by PRoot JNI.
-keepclasseswithmembernames class * { native <methods>; }

# Preserve Kotlin metadata used by reflection in AndroidX/Compose.
-keep class kotlin.Metadata { *; }

# PRoot JNI entry points are resolved by native symbol names at runtime.
-keep class id.or.oo.pr.engine.PtyNative { *; }

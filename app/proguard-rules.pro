# libtorrent4j uses JNI-generated SWIG classes and native method bindings.
-keep class org.libtorrent4j.swig.libtorrent_jni { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Preserve Kotlin metadata used by reflection in AndroidX/Compose.
-keep class kotlin.Metadata { *; }

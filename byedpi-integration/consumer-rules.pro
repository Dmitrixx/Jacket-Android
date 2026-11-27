# ByeByeDPI resolves native methods by their Java class/method symbols.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class io.github.romanvht.byedpi.core.ByeDpiProxy { *; }
-keep class io.github.romanvht.byedpi.core.TProxyService { *; }
-keep class io.github.romanvht.byedpi.activities.** { *; }
-keep class io.github.romanvht.byedpi.services.** { *; }
-keep class io.github.romanvht.byedpi.receiver.** { *; }
-keep class io.github.romanvht.byedpi.fragments.** {
    <init>();
}
-keep class io.github.romanvht.byedpi.data.** {
    <fields>;
}
-keepattributes Signature,*Annotation*

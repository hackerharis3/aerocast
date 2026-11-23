-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class app.airmusic.modern.nativebridge.NativeAudioEngine { *; }

# Ktor's IntelliJ debugger detector references JVM-only management classes
# that don't exist on Android; that code path never runs on a phone.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

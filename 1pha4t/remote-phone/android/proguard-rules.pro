# Полное отключение логов в релизе (Zero-Trace)
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# WebRTC
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Kotlin
-keep class kotlin.** { *; }
-keepclassmembers class ** {
    @kotlinx.coroutines.* <methods>;
}

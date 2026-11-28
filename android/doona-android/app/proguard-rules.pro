# Release shrinking (ARCH D22). R8 reads the consumer rules of the libraries too; these keep what only this app
# reaches by name.

# The JNI entry points are bound by name from the native library.
-keepclasseswithmembernames class dev.doona.android.vpn.NativeCore {
    native <methods>;
}

# The core builds NativeResponse and calls SocketProtector.protect by name and JNI descriptor.
-keep class dev.doona.android.honk.NativeResponse {
    <init>(int, java.lang.String);
}
-keep interface dev.doona.android.vpn.SocketProtector {
    boolean protect(int);
}
-keepclassmembers class * implements dev.doona.android.vpn.SocketProtector {
    boolean protect(int);
}

# @Serializable DTOs and back stack keys: kotlinx.serialization looks serializers up by name at run time, and the
# keys are saved across process death under their class names.
-keep @kotlinx.serialization.Serializable class dev.doona.android.** { *; }
-keepclassmembers class dev.doona.android.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class dev.doona.android.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class dev.doona.android.**$$serializer { *; }

# Crash files (report/CrashCapture.kt) keep only the app's own frames, picked by package, as `class.method:line`:
# the package names and line numbers stay, so a release build's frames are found and retrace with the mapping.
-keeppackagenames dev.doona.android.**
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

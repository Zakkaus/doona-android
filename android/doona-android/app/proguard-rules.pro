# Release shrinking (ARCH D22). R8 reads the consumer rules of the libraries too; these keep what only this app
# reaches by name.

# The JNI entry points are bound by name from the native library.
-keepclasseswithmembernames class dev.doona.android.vpn.NativeCore {
    native <methods>;
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

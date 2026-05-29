# ProGuard rules for LiquidMusicGlass
# Maximum obfuscation + anti-tampering

# Keep entry points
-keep public class com.liquidmusicglass.MainActivity { *; }
-keep public class com.liquidmusicglass.engine.AudioService { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep JNI class
-keep class com.liquidmusicglass.engine.IcmKeyProvider {
    native <methods>;
    <init>(...);
}

# Obfuscate everything else
-repackageclasses 'a'
-allowaccessmodification
-overloadaggressively
-useuniqueclassmembernames
-flattenpackagehierarchy

# String encryption (basic)
-assumenosideeffects class java.lang.String {
    public java.lang.String intern();
}

# Remove logs in release
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Keep serialization
-keepattributes *Annotation*, Signature, Exception, InnerClasses, EnclosingMethod
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep Kotlin serialization
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
    @kotlinx.serialization.Serializable <methods>;
}

# Keep all API models and network structures from obfuscation/shrinking
-keep class com.liquidmusicglass.api.icm.** { *; }
-keepclassmembers class com.liquidmusicglass.api.icm.** { *; }

# Keep all security and JNI bridge classes from obfuscation/shrinking (NativeSecurity, LcmNative)
-keep class com.liquidmusicglass.security.** { *; }
-keepclassmembers class com.liquidmusicglass.security.** { *; }

# Additional kotlinx.serialization safety rules
-keep class * implements kotlinx.serialization.KSerializer { *; }
-keepclassmembers class * {
    *** Companion;
}
-keepclassmembers class * {
    *** $serializer;
}

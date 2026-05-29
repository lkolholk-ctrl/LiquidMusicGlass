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
-keep class com.liquidmusicglass.api.youtube.** { *; }
-keepclassmembers class com.liquidmusicglass.api.youtube.** { *; }

# Keep Room entities and DAOs
-keep class com.liquidmusicglass.data.local.db.** { *; }
-keepclassmembers class com.liquidmusicglass.data.local.db.** { *; }

# Keep sealed classes and their subclasses
-keep class * extends com.liquidmusicglass.camp.MusicCamp { *; }
-keep class * extends com.liquidmusicglass.engine.PlayerController.PlaybackContext { *; }
-keep class * extends com.liquidmusicglass.api.youtube.YouTubeMusicRepository.YtBrowseItem { *; }
-keep class * extends com.liquidmusicglass.api.youtube.YouTubeMusicRepository.YtSearchItem { *; }

# Keep Kotlinx Serialization @SerialName fields
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

# Keep enum classes
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Ktor/OkHttp
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class okio.** { *; }
-dontwarn okio.**

# Keep Kotlin stdlib reflection (for reified generics)
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**

# ProGuard/R8 rules for LiquidMusicGlass
# Полная обфускация + анти-тампер, но с ПУЛЕНЕПРОБИВАЕМЫМИ keep для всего,
# что R8 мог бы «утащить»: JNI-мосты, Room, kotlinx.serialization, LiteRT.

# ── Точки входа ───────────────────────────────────────────────────────────────
-keep public class com.liquidmusicglass.MainActivity { *; }
-keep public class com.liquidmusicglass.engine.AudioService { *; }
-keep class com.liquidmusicglass.App { *; }

# ── JNI-мосты (КРИТИЧНО) ──────────────────────────────────────────────────────
# Символы нативных функций формата Java_<пакет>_<Класс>_<метод>. Если R8
# переименует/перепакует класс — символ не резолвится, и нативная либа не
# заводится. Для оффлайн-аудио это фатально: AutoMixNativeEngine → automix_juce.
# Держим ВСЕ 3 JNI-класса целиком (имя пакета + класс + методы + <init>).
-keep,includedescriptorclasses class com.liquidmusicglass.engine.automix.AutoMixNativeEngine { *; }
-keep,includedescriptorclasses class com.liquidmusicglass.engine.IcmKeyProvider { *; }
-keep,includedescriptorclasses class com.liquidmusicglass.security.NativeSecurity { *; }
-keep class com.liquidmusicglass.security.** { *; }
-keepclassmembers class com.liquidmusicglass.security.** { *; }

# Любой класс с нативными методами — имена сохраняем (страховка сверх явных выше).
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ── Room (БД локальной библиотеки/истории) ────────────────────────────────────
# Room генерит *_Impl и обращается к сущностям/DAO/конвертерам. Держим весь
# db-пакет + все Room-аннотированные типы.
-keep class com.liquidmusicglass.data.local.db.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**

# ── kotlinx.serialization (ВСЕ @Serializable, без перечисления пакетов) ────────
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exception, RuntimeVisibleAnnotations, AnnotationDefault

# Держим КАЖДЫЙ @Serializable класс и его синтетический $serializer целиком —
# покрывает api.icm, camp (sealed MusicCamp), data.local, data.playlistimport
# и всё, что могли забыть.
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class * {
    *** Companion;
    *** $serializer;
}
-keep class * implements kotlinx.serialization.KSerializer { *; }
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# Сетевые модели ICM — держим целиком (уходят в сериализацию/десериализацию).
-keep class com.liquidmusicglass.api.icm.** { *; }
-keepclassmembers class com.liquidmusicglass.api.icm.** { *; }
-keep class com.liquidmusicglass.camp.** { *; }
-keep class com.liquidmusicglass.data.playlistimport.** { *; }

# Enum'ы: values()/valueOf нужны сериализации и рефлексии when.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── LiteRT / TensorFlow Lite (ML-модель AutoMix + VAD-лирика) ──────────────────
-keep class com.google.ai.edge.litert.** { *; }
-keep class org.tensorflow.** { *; }
-dontwarn com.google.ai.edge.litert.**
-dontwarn org.tensorflow.**

# ── Compose / Media3 ──────────────────────────────────────────────────────────
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ── Сеть (OkHttp / Ktor / Retrofit) ──────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn io.ktor.**
-dontwarn retrofit2.**
-keepclassmembers class * {
    @retrofit2.http.* <methods>;
}

# ── Логи в релизе (убираем verbose/debug/info, оставляем w/e) ──────────────────
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# ── Обфускация (агрессивная, НО без -overloadaggressively) ─────────────────────
# -overloadaggressively намеренно убран: он ломает рефлексию/сериализацию/JNI на
# ряде устройств. Остальная обфускация — максимальная.
-repackageclasses 'a'
-allowaccessmodification
-useuniqueclassmembernames
-flattenpackagehierarchy

# Kotlin-метаданные и корутины — без предупреждений.
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlin.Metadata { *; }

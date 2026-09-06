# The extraction engine reflects over its own Jackson models.
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.ffmpeg.** { *; }
-keepclassmembers class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

# kotlinx.serialization keeps generated serializers on the companion.
-keepclassmembers class app.bibifoq.**$$serializer { *; }
-keepclasseswithmembers class app.bibifoq.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.bibifoq.core.**$$serializer { *; }

# OkHttp's optional platform integrations are referenced but never present on Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

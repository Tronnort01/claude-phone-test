# Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.stealthagent.model.**$$serializer { *; }
-keepclassmembers class com.stealthagent.model.** { *** Companion; }
-keepclasseswithmembers class com.stealthagent.model.** { kotlinx.serialization.KSerializer serializer(...); }

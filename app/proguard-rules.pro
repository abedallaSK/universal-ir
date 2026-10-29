# kotlinx.serialization: keep generated serializers for @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.universalir.**$$serializer { *; }
-keepclassmembers class com.universalir.** {
    *** Companion;
}
-keepclasseswithmembers class com.universalir.** {
    kotlinx.serialization.KSerializer serializer(...);
}

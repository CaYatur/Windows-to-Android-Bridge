# kotlinx.serialization generates serializers as synthetic companions and looks
# them up reflectively. R8 cannot see those links, so without these the release
# build fails at runtime with "Serializer for class X is not found" -- and only
# for the release build, which is the worst time to discover it.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.cayatur.winbridge.**$$serializer { *; }
-keepclassmembers class com.cayatur.winbridge.** {
    *** Companion;
}
-keepclasseswithmembers class com.cayatur.winbridge.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The protocol module's message classes are serialized by name.
-keep,includedescriptorclasses class com.cayatur.winbridge.protocol.**$$serializer { *; }
-keepclassmembers class com.cayatur.winbridge.protocol.** {
    *** Companion;
}
-keep class com.cayatur.winbridge.protocol.** { *; }

# Glance instantiates receivers and action callbacks by name from the system.
-keep class com.cayatur.winbridge.widget.** { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
-keep class * implements androidx.glance.appwidget.action.ActionCallback { *; }

# ZXing reflects over format enums.
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

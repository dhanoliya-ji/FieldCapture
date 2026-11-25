-keep class com.fieldcapture.app.data.model.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}

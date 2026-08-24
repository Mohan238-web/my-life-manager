-keepclassmembers class com.mylifemanager.app.bridge.** {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }

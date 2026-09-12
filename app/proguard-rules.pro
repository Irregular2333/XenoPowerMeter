# Add project specific ProGuard rules here.
-keep class com.irregular.xenopowermeter.data.model.** { *; }
-keep class com.irregular.xenopowermeter.data.usb.** { *; }
-dontwarn com.hoho.android.usbserial.**

# Compose
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# Backdrop library
-keep class io.github.kyant0.** { *; }
-dontwarn io.github.kyant0.**

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep data classes for serialization
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

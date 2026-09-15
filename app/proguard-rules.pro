# The app doesn't use reflection over its own classes, and usb-serial-for-android,
# Compose and the vendored :backdrop module all ship their own consumer rules —
# R8 can shrink all of them without blanket keeps (the previous
# "-keep class androidx.compose.** { *; }" defeated shrinking of the entire
# Compose stack). Add targeted rules here only if a release build actually
# breaks at runtime.
-dontwarn com.hoho.android.usbserial.**

# Data model classes are used with kotlinx.serialization-like patterns
# and ByteArray content comparison; keep them to avoid R8 field mangling.
-keep class com.irregular.xenopowermeter.data.model.** { *; }

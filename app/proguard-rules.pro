# Add project specific ProGuard rules here.
# The demo keeps full bytecode (minify disabled); this file reserves the
# slot for the future release hardening pass (R8 + resource shrinking).
-keep class com.cursor.mobile.android.data.** { *; }

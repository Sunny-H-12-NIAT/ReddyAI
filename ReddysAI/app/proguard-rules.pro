# Proguard rules for Reddy's AI

# Keep Google API client classes
-keep class com.google.api.** { *; }
-keep class com.google.android.gms.** { *; }
-keep class com.google.auth.** { *; }
-dontwarn com.google.api.**
-dontwarn com.google.auth.**

# Keep javax.mail (used by GmailClient for MIME messages)
-keep class javax.mail.** { *; }
-dontwarn javax.mail.**

# Keep Compose
-keep class androidx.compose.** { *; }

# General Android
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

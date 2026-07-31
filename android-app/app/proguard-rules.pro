# ProGuard rules for Telegram-Drive

# Keep TDLib native methods and classes
-keep class org.drinkless.tdlib.** { *; }
-keepclassmembers class org.drinkless.tdlib.** { *; }

# Keep security and encryption models
-keep class com.nmtuong.telegramdrive.security.** { *; }

# Keep domain models and serialization
-keep class com.nmtuong.telegramdrive.domain.** { *; }
-keepclassmembers class com.nmtuong.telegramdrive.domain.** { *; }

# Keep Compose models and internals
-keep class com.nmtuong.telegramdrive.feature.** { *; }

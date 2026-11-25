# Duo Phone ProGuard / R8 rules
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# Android components & custom host views
-keep public class com.duophone.app.DuoPhoneApplication { *; }
-keep public class com.duophone.app.MainActivity { *; }
-keep public class com.duophone.app.SecondaryDeckActivity { *; }
-keep public class com.duophone.app.service.ModemService { *; }
-keep public class com.duophone.app.service.CallActionReceiver { *; }
-keep class com.duophone.app.ui.gta.PerspectiveQuadLayout { *; }

# GTA stage configuration & domain models
-keep class com.duophone.app.stage.** { *; }

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

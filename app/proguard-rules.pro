# Keep libbox Go/JNI bindings and callbacks intact. The AAR has consumer rules,
# but these explicit rules avoid release-only callback/JNI breakage.
-keep class com.hiddify.core.libbox.** { *; }
-keep interface com.hiddify.core.libbox.** { *; }
-dontwarn com.hiddify.core.libbox.**

-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

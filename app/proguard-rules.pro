# R8 full mode is on by default in AGP 8. Everything the app needs is referenced
# directly from code or declared in AndroidManifest.xml (which R8 keeps), so no
# custom keep rules are required. The rules below only strip leftovers.

# Kotlin stdlib metadata and coroutine debug probes are not used at runtime.
-dontwarn kotlin.**
-dontwarn kotlinx.**

# Drop source-file/line attributes we do not need, keeping stack traces usable by name.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# Manifest-declared components are kept automatically, but be explicit so that
# shrinking never breaks the device-admin / accessibility user flows.
-keep class com.impel.touchlock.LockDeviceAdminReceiver { *; }
-keep class com.impel.touchlock.BootReceiver { *; }
-keep class com.impel.touchlock.SleepTimerReceiver { *; }
-keep class com.impel.touchlock.LockAccessibilityService { *; }
-keep class com.impel.touchlock.BubbleService { *; }
-keep class com.impel.touchlock.SettingsActivity { *; }

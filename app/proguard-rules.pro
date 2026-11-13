# Strip debug logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
# Tink (used by EncryptedSharedPreferences) references annotations that are not on the classpath.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

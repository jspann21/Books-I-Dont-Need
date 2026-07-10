# Project-specific R8/ProGuard rules.
#
# Add keep rules here only when release minification removes code used through
# reflection, serialization, Android framework entry points, or third-party APIs.

# Debug/info logging is intentionally verbose while developing the site parsers. Remove it
# from minified release builds to avoid constructing large URL/parser messages and writing
# browsing details to Logcat. Warnings and errors remain available for production diagnosis.
-assumenosideeffects class android.util.Log {
    public static int v(java.lang.String, java.lang.String);
    public static int v(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int d(java.lang.String, java.lang.String);
    public static int d(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int i(java.lang.String, java.lang.String);
    public static int i(java.lang.String, java.lang.String, java.lang.Throwable);
}

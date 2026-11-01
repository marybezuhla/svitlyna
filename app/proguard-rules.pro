# Svitlyna: shrink, but keep every name (crash reports stay readable, reflection keeps working).
-dontobfuscate

# Text recognition models run through ONNX Runtime's Java/JNI layer.
-keep class ai.onnxruntime.** { *; }
# The encrypted search database (SQLCipher's JNI looks classes up by name).
-keep class net.zetetic.** { *; }
# Metadata copying lists ExifInterface's TAG_* fields by reflection: all of them must stay.
-keep class androidx.exifinterface.media.ExifInterface { public static final java.lang.String *; }
# AVIF decoder (JNI).
-keep class org.aomedia.avif.android.** { *; }

# No log lines in the app people install (they could hold file names or paths).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

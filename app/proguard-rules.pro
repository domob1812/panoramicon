# Add project specific ProGuard rules here.

# Keep PanoramaGL classes
-keep class com.panoramagl.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep OpenGL related classes
-keep class javax.microedition.khronos.opengles.** { *; }
-keep class android.opengl.** { *; }
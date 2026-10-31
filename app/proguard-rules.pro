# Project-specific R8 rules. Compose, Coil and Media3 ship their own consumer rules.

# GPUImage's YUV helper is looked up from native code by name.
-keep class jp.co.cyberagent.android.gpuimage.GPUImageNativeLibrary { *; }

# Image decoders whose native code reaches back into these classes by name.
-keep class com.t8rin.raw_coder.** { *; }
-keep class com.t8rin.tiff_coder.** { *; }
-keep class org.beyka.tiffbitmapfactory.** { *; }
-keep class com.gemalto.jp2.** { *; }
-keep class com.awxkee.jxlcoderlibjxl.** { *; }

# libVLC ships no rules, and its native code reaches these classes by name.
-keep class org.videolan.libvlc.** { *; }

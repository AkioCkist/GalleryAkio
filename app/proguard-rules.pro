# Project-specific R8 rules. Compose, Coil and Media3 ship their own consumer rules.

# GPUImage's YUV helper is looked up from native code by name.
-keep class jp.co.cyberagent.android.gpuimage.GPUImageNativeLibrary { *; }

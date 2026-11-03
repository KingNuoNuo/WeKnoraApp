# 这个文件通常不需要改动；OkHttp / kotlinx-serialization 反射需求极少。
-keepclassmembers class * {
    @androidx.annotation.Keep <methods>;
}

# FragmentManager recreates these classes by name after process/configuration changes.
-keep,allowoptimization class com.jinge.weknora.ReaderPdfFragment { public <init>(); }
-keep,allowoptimization class com.jinge.weknora.ReadOnlyPdfFragment { public <init>(); }

# Optional PDFBox JPEG2000 codec is absent. PDFBox only reads outlines here;
# page/image decoding is handled by AndroidX PDF, which does not use this codec.
-dontwarn com.gemalto.jp2.JP2Decoder

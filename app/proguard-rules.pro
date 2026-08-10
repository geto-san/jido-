# OkHttp and Okio ship their own consumer ProGuard rules inside their AARs,
# so no manual rules are needed for them here. org.json is part of the
# Android platform, not a library, so it isn't touched by R8 either.

# Keep line numbers in stack traces for crash reports from release builds.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Data models are only ever read/written directly in Kotlin (no reflection-
# based JSON mapping), so no explicit -keep is required for them — but this
# is a safe, cheap guard in case that ever changes.
-keep class com.geto.jido.DownloadItem { *; }
-keep enum com.geto.jido.DownloadStatus { *; }

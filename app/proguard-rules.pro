# Media3 通过反射发现 DataSource/Loader 实现，收缩时需保留。
-keep class androidx.media3.datasource.** { *; }
-keep class androidx.media3.exoplayer.source.** { *; }
-keep class androidx.media3.common.** { *; }
-keep class androidx.media3.session.** { *; }
-dontwarn androidx.media3.**

# OkHttp 在低版本 Android 上依赖可选平台类。
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# PlaybackEntry 序列化到 MediaItem extras 与本地 JSON，需要保留字段名。
-keep class com.watchlisten.music.model.** { *; }
-keep class com.watchlisten.music.playback.MusicService { *; }
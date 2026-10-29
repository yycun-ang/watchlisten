package com.watchlisten.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.text.SubtitleParser

/** Keeps cast retries and progressive decoding separate from ordinary catalog playback. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OriginRoutingMediaSourceFactory(
    private val normal: MediaSource.Factory,
    private val cast: MediaSource.Factory
) : MediaSource.Factory {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        (if (mediaItem.localConfiguration?.uri?.scheme.equals("watchlisten-cast", ignoreCase = true)) cast else normal)
            .createMediaSource(mediaItem)

    override fun getSupportedTypes(): IntArray = (normal.supportedTypes + cast.supportedTypes).distinct().toIntArray()

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = apply {
        normal.setDrmSessionManagerProvider(provider)
        cast.setDrmSessionManagerProvider(provider)
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = apply {
        normal.setLoadErrorHandlingPolicy(policy)
        cast.setLoadErrorHandlingPolicy(policy)
    }

    override fun setCmcdConfigurationFactory(factory: CmcdConfiguration.Factory) = apply {
        normal.setCmcdConfigurationFactory(factory)
        cast.setCmcdConfigurationFactory(factory)
    }

    override fun setSubtitleParserFactory(factory: SubtitleParser.Factory) = apply {
        normal.setSubtitleParserFactory(factory)
        cast.setSubtitleParserFactory(factory)
    }

    @Suppress("DEPRECATION")
    override fun experimentalParseSubtitlesDuringExtraction(parse: Boolean) = apply {
        normal.experimentalParseSubtitlesDuringExtraction(parse)
        cast.experimentalParseSubtitlesDuringExtraction(parse)
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecs: Int) = apply {
        normal.experimentalSetCodecsToParseWithinGopSampleDependencies(codecs)
        cast.experimentalSetCodecsToParseWithinGopSampleDependencies(codecs)
    }
}

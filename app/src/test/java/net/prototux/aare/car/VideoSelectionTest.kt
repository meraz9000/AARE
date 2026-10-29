package net.prototux.aare.car

import aap_protobuf.service.media.sink.message.VideoCodecResolutionTypeOuterClass.VideoCodecResolutionType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSelectionTest {
    private fun config(
        width: Int,
        height: Int,
        widthMargin: Int = 0,
        heightMargin: Int = 0,
    ): VideoConfiguration = VideoConfiguration.newBuilder()
        .setCodecResolution(
            when (width to height) {
                1280 to 720 -> VideoCodecResolutionType.VIDEO_1280x720
                1920 to 1080 -> VideoCodecResolutionType.VIDEO_1920x1080
                else -> error("unsupported test resolution ${width}x$height")
            },
        )
        .setWidthMargin(widthMargin)
        .setHeightMargin(heightMargin)
        .build()

    @Test
    fun choosesFullWidthModeWhenMarginsMakeIt720High() {
        val configs = listOf(
            config(1280, 720),
            config(1920, 1080, heightMargin = 360),
        )

        assertEquals(1, CarSession.chooseVideo(configs, emptyList(), maxHeight = 720, prefer60 = false))
    }

    @Test
    fun stillChooses720pWhen1080pHasNoMargins() {
        val configs = listOf(
            config(1280, 720),
            config(1920, 1080),
        )

        assertEquals(0, CarSession.chooseVideo(configs, emptyList(), maxHeight = 720, prefer60 = false))
    }
}

package technology.cariad.entertainment.metadataservice.data.provider.radioplayer

import org.junit.Assert
import org.junit.Test

class RadioplayerSongRecognitionServiceTest {

    @Test
    fun extractStationImageUrl_shouldPreferStationImageUrlWhenRpuidMissing() {
        val service = RadioplayerSongRecognitionService()

        val station = object {
            fun getName() = "Example Station"
            fun getImageUrl() = "https://example.com/station-logo.jpg"
            fun getUrl() = "https://example.com/fallback.jpg"
        }

        Assert.assertEquals(
            "https://example.com/station-logo.jpg",
            service.extractStationImageUrl(station)
        )
    }
}

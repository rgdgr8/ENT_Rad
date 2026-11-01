package technology.cariad.entertainment.metadataservice.data.provider.radioplayer

data class RadioplayerSongRecognitionResult(
    val title: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val mood: String? = null,
    val tempo: String? = null,
    val externalIds: List<RadioplayerExternalId> = listOf(),
    val externalAlbumIds: List<RadioplayerExternalId> = listOf(),
    val image: ByteArray? = null,
    val providerId: String? = null,
    val providerUniqueId: String? = null
)

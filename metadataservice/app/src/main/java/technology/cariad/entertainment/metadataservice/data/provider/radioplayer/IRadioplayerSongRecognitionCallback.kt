package technology.cariad.entertainment.metadataservice.data.provider.radioplayer

interface IRadioplayerSongRecognitionCallback {
    fun onRecognized(result: RadioplayerSongRecognitionResult)

    fun onStatus(status: RadioplayerRecognitionStatus)
}

package technology.cariad.entertainment.metadataservice.data.provider.radioplayer

interface IRadioplayerStationCallback {
    fun onRadioStationData(name: String, providerId: String, imgData: ByteArray)
}

package technology.cariad.entertainment.metadataservice.data.provider.radioplayer

fun interface IRadioplayerCoverArtLookupCallback {
    fun onCoverArtReceived(imgData: ByteArray?)
}

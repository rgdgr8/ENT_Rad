package technology.cariad.entertainment.metadataservice.data.provider.radioplayer

fun interface IRadioplayerSongLyricsCallback {
    fun onLyricsReceived(playPosition: Int, lyricsData: String?)
}

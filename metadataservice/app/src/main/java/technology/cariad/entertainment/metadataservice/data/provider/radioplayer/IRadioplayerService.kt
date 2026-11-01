package technology.cariad.entertainment.metadataservice.data.provider.radioplayer

import technology.cariad.entertainment.metadataservice.data.provider.GnServiceRequestNr

interface IRadioplayerService {
    fun getSdkVersionInfo(): String

    fun requiresAudioCapture(): Boolean = false

    fun initializeService()

    fun doTextLookupForAlbum(
        title: String?,
        artist: String?,
        album: String?,
        callback: IRadioplayerCoverArtLookupCallback
    )

    fun startSongRecognition(fd: Int)

    fun stopSongRecognition()

    fun requestRadioStationData(
        positionData: RadioplayerPositionData,
        stationInfo: RadioplayerStationInfo,
        countryCode: Int,
        callback: IRadioplayerStationCallback
    )

    fun requestLyrics(
        type: RadioplayerLyricsType,
        familyFriendly: Boolean,
        callback: IRadioplayerSongLyricsCallback
    )

    fun registerCallback(callback: IRadioplayerSongRecognitionCallback)

    fun unregisterCallback(callback: IRadioplayerSongRecognitionCallback)

    fun updateApnConfigEntry(requestNr: GnServiceRequestNr, networkInterface: String?)

    fun setMcc(mcc: Int)

    fun setCountryOfSale(country: String)
}

package technology.cariad.entertainment.metadataservice.data.provider

import technology.cariad.entertainment.metadataservice.common.log.logger
import technology.cariad.entertainment.metadataservice.common.network.NetworkInfo
import technology.cariad.entertainment.metadataservice.data.provider.radioplayer.IRadioplayerService
import technology.cariad.entertainment.metadataservice.data.provider.radioplayer.RadioplayerLyricsType
import technology.cariad.entertainment.metadataservice.domain.ISongLyricsProvider
import technology.cariad.entertainment.metadataservice.domain.entity.SongLyricsEntity
import technology.cariad.entertainment.metadataservice.domain.value.LyricsType
import technology.cariad.entertainment.metadataservice.domain.value.ServiceIdentifier
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class SongLyricsProvider(
    override val serviceId: ServiceIdentifier,
    private val radioplayerService: IRadioplayerService
) : ISongLyricsProvider {

    private val log by logger()

    /**
     * @see [ISongLyricsProvider.getSongLyrics]
     */
    override fun getSongLyrics(
        lyricsType: LyricsType,
        familyFriendly: Boolean
    ): Flow<SongLyricsEntity> = callbackFlow {
        log.info(this@SongLyricsProvider::getSongLyrics.name) { "register new subscriber" }

        val radioplayerLyricsType = when (lyricsType) {
            LyricsType.STATIC -> RadioplayerLyricsType.STATIC
            LyricsType.LINE_BY_LINE -> RadioplayerLyricsType.LINEBYLINE
            LyricsType.WORD_BY_WORD -> RadioplayerLyricsType.WORDBYWORD
        }

        radioplayerService.requestLyrics(
            radioplayerLyricsType,
            familyFriendly
        ) { playPosition: Int, lyricsData: String? ->

            log.info(this@SongLyricsProvider::getSongLyrics.name) {
                "received new lyrics: length=${lyricsData?.length}"
            }

            trySend(SongLyricsEntity(lyricsData ?: "", playPosition))
        }

        awaitClose()
    }

    /**
     * @see [ISongLyricsProvider.selectNetwork]
     */
    override fun selectNetwork(networkInfo: NetworkInfo?) {
        log.info(this::selectNetwork.name) {
            "set network to: networkInfo=$networkInfo"
        }

        radioplayerService.updateApnConfigEntry(
            GnServiceRequestNr.LYRICS,
            networkInfo?.interfaceName
        )
    }

    /**
     * @see [ISongLyricsProvider.setMcc]
     */
    override fun setMcc(mcc: Int) {
        log.info(this::selectNetwork.name) {
            "set mcc to: mcc=$mcc"
        }

        radioplayerService.setMcc(mcc)
    }
}


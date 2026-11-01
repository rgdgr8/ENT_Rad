package technology.cariad.entertainment.metadataservice.service.di.module

import android.content.Context
import android.media.AudioManager
import dagger.Module
import dagger.Provides
import de.esolutions.gmdlib.media.GmdClient
import de.esolutions.magicadapter.stapi.MagicAdapter
import technology.cariad.entertainment.metadataservice.common.codingprovider.MediaCodingProvider
import technology.cariad.entertainment.metadataservice.common.log.Logger
import technology.cariad.entertainment.metadataservice.common.log.logger
import technology.cariad.entertainment.metadataservice.data.provider.CoverArtProvider
import technology.cariad.entertainment.metadataservice.data.provider.MusicMetadataProvider
import technology.cariad.entertainment.metadataservice.data.provider.RadioStationDataAsiaProvider
import technology.cariad.entertainment.metadataservice.data.provider.SongLyricsProvider
import technology.cariad.entertainment.metadataservice.data.provider.radioplayer.IRadioplayerService
import technology.cariad.entertainment.metadataservice.data.provider.radioplayer.RadioplayerSongRecognitionService
import technology.cariad.entertainment.metadataservice.data.provider.songinfo.SongInfoProvider
import technology.cariad.entertainment.metadataservice.data.provider.songinfo.audiorecord.AudioRecordWrapper
import technology.cariad.entertainment.metadataservice.data.provider.songinfo.audiorecord.IAudioRecordWrapper
import technology.cariad.entertainment.metadataservice.data.provider.songinfo.audiorecorder.AudioRecorder
import technology.cariad.entertainment.metadataservice.data.provider.songinfo.audiorecorder.AudioRecorderThread
import technology.cariad.entertainment.metadataservice.data.provider.songinfo.audiorecorder.IAudioRecorder
import technology.cariad.entertainment.metadataservice.data.provider.songinfo.audiorecorder.IAudioRecorderThread
import technology.cariad.entertainment.metadataservice.data.provider.songinfo.audiorecorder.RecordingConfig
import technology.cariad.entertainment.metadataservice.domain.ICoverArtProvider
import technology.cariad.entertainment.metadataservice.domain.IMusicMetadataProvider
import technology.cariad.entertainment.metadataservice.domain.IRadioStationDataAsiaProvider
import technology.cariad.entertainment.metadataservice.domain.ISongInfoProvider
import technology.cariad.entertainment.metadataservice.domain.ISongLyricsProvider
import technology.cariad.entertainment.metadataservice.domain.value.ServiceIdentifier
import technology.cariad.entertainment.metadataservice.service.config.ModuleConfig
import technology.cariad.entertainment.metadataservice.service.di.ServiceScope
import javax.inject.Named

@Module(includes = [ProviderRegionModule::class])
class ProviderModule(private val config: ModuleConfig) {

    /**
     * Provides single instance of the [IRadioStationDataAsiaProvider] to the dependency graph.
     */
    @ServiceScope
    @Provides
    fun provideRadioStationDataAsiaProvider(
        radioplayerService: IRadioplayerService
    ): IRadioStationDataAsiaProvider {
        return RadioStationDataAsiaProvider(ServiceIdentifier.HYBRIDRADIODATA_V3, radioplayerService)
    }

    /**
     * Provides single instance of the [RecordingConfig] to the dependency graph.
     */
    @ServiceScope
    @Provides
    fun provideRecordingConfig(context: Context): RecordingConfig {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputDeviceAddress = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.address == "bus9_gnt_in" }
            ?.address
            ?: audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull()
                ?.address
            ?: "bus9_gnt_in"

        log.info(this::provideRecordingConfig.name) {
            "selected inputDeviceAddress=$inputDeviceAddress"
        }
        return RecordingConfig(
            inputDeviceAddress = inputDeviceAddress,
            sampleRate = 48000,
            channels = RecordingConfig.Channel.IN_STEREO,
            encoding = RecordingConfig.Encoding.PCM_16BIT
        )
    }

    private val log by logger()

    /**
     * Provides single instance of the [IRadioplayerService] to the dependency graph.
     */
    @ServiceScope
    @Provides
    fun provideRadioplayerService(
        context: Context,
        codingProvider: MediaCodingProvider
    ): IRadioplayerService {

        log.info(this::provideRadioplayerService.name) {
            "${codingProvider.getCountryOfSales()}"
        }

        return RadioplayerSongRecognitionService(context.applicationContext)
    }

    /**
     * Provides single instance of the [IAudioRecordWrapper] to the dependency graph.
     */
    @ServiceScope
    @Provides
    fun provideAudioRecordWrapper(
        context: Context,
        recordingConfig: RecordingConfig
    ): IAudioRecordWrapper {
        return AudioRecordWrapper(context, recordingConfig)
    }

    /**
     * Provides single instance of the [IAudioRecorder] to the dependency graph.
     */
    @ServiceScope
    @Provides
    fun provideAudioRecorder(audioRecordWrapper: IAudioRecordWrapper): IAudioRecorder {
        return AudioRecorder(audioRecordWrapper)
    }

    /**
     * Provides single instance of the [IAudioRecorderThread] to the dependency graph.
     */
    @ServiceScope
    @Provides
    fun provideAudioRecorderThread(
        audioRecorder: IAudioRecorder
    ): IAudioRecorderThread {
        return AudioRecorderThread(audioRecorder)
    }

    /**
     * Provides single instance of the [ISongInfoProvider] to the dependency graph.
     */
    @ServiceScope
    @Provides
    fun provideSongInfoProvider(
        radioplayerService: IRadioplayerService,
        audioRecorderThread: IAudioRecorderThread
    ): ISongInfoProvider {
        return SongInfoProvider(
            ServiceIdentifier.GRACENOTE_V1,
            radioplayerService,
            audioRecorderThread
        )
    }

    /**
     * Provides single instance of the [ISongLyricsProvider] to the dependency graph.
     */
    @ServiceScope
    @Provides
    fun provideSongLyricsProvider(
        radioplayerService: IRadioplayerService
    ): ISongLyricsProvider {
        return SongLyricsProvider(
            ServiceIdentifier.GRACENOTELYRICS_V1,
            radioplayerService
        )
    }

    /**
     * Provides single instance of the [ICoverArtProvider] to the dependency graph.
     */
    @ServiceScope
    @Provides
    fun provideCoverArtDataProvider(radioplayerService: IRadioplayerService):
            ICoverArtProvider {
        return CoverArtProvider(ServiceIdentifier.GRACENOTE_V1, radioplayerService)
    }

    /**
     * Provides single instance of the [GmdClient] to the dependency graph.
     */
    @ServiceScope
    @Provides
    fun provideGmdClient(context: Context): GmdClient {
        return GmdClient(context, Logger.PROCESS_NAME)
    }

    /**
     * Provides single instance of the [IMusicMetadataProvider] to the dependency graph.
     */
    @ServiceScope
    @Provides
    fun provideMusicMetadataProvider(gmdClient: GmdClient):
            IMusicMetadataProvider {
        return MusicMetadataProvider(ServiceIdentifier.GRACENOTEGMD_V1, gmdClient)
    }
}


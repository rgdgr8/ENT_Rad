package technology.cariad.entertainment.metadataservice.data.provider.songinfo

import technology.cariad.entertainment.metadataservice.common.io.IFileDescriptor
import technology.cariad.entertainment.metadataservice.common.io.IInputStream
import technology.cariad.entertainment.metadataservice.common.io.IOutputStream
import technology.cariad.entertainment.metadataservice.common.io.IPipe
import technology.cariad.entertainment.metadataservice.common.io.IPipeFactory
import technology.cariad.entertainment.metadataservice.common.network.NetworkInfo
import technology.cariad.entertainment.metadataservice.data.provider.GnServiceRequestNr
import technology.cariad.entertainment.metadataservice.data.provider.radioplayer.IRadioplayerService
import technology.cariad.entertainment.metadataservice.data.provider.radioplayer.RadioplayerExternalId
import technology.cariad.entertainment.metadataservice.data.provider.radioplayer.RadioplayerRecognitionStatus
import technology.cariad.entertainment.metadataservice.data.provider.radioplayer.RadioplayerSongRecognitionResult
import technology.cariad.entertainment.metadataservice.data.provider.songinfo.audiorecorder.IAudioRecorderThread
import technology.cariad.entertainment.metadataservice.domain.entity.ExternalIdentifierEntity
import technology.cariad.entertainment.metadataservice.domain.entity.ImageEntity
import technology.cariad.entertainment.metadataservice.domain.entity.SongInfoEntity
import technology.cariad.entertainment.metadataservice.domain.value.ServiceIdentifier
import technology.cariad.entertainment.metadataservice.mock.common.log.LoggerMock
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class SongInfoProviderTest {
    private val radioplayerServiceMock = mockk<IRadioplayerService>()
    private val audioRecorderThreadMock = mockk<IAudioRecorderThread>()

    private val pipeMock = mockk<IPipe>()
    private val readFdMock = mockk<IFileDescriptor>()
    private val writeFdMock = mockk<IFileDescriptor>()
    private val inputStreamMock = mockk<IInputStream>()
    private val outputStreamMock = mockk<IOutputStream>()

    private lateinit var songInfoProvider: SongInfoProvider

    @Before
    fun setup() {

        LoggerMock()

        every { radioplayerServiceMock.registerCallback(any()) } just Runs

        mockkObject(IPipeFactory)
        every { IPipeFactory.createPipe() } returns pipeMock

        every { pipeMock.readFd } returns readFdMock
        every { pipeMock.writeFd } returns writeFdMock
        every { pipeMock.readStream } returns inputStreamMock
        every { pipeMock.writeStream } returns outputStreamMock
        every {outputStreamMock.close()} just Runs

        every { readFdMock.detach() } returns 1

        songInfoProvider = SongInfoProvider(
            ServiceIdentifier.GRACENOTE_V1,
            radioplayerServiceMock,
            audioRecorderThreadMock
        )

        songInfoProvider.serviceId
    }

    @Test
    fun getSongInfo_shouldStartRecognitionOnFirstSubscription() = runBlocking {

        var cont: Continuation<Unit>? = null

        every { radioplayerServiceMock.startSongRecognition(any()) } answers {
            songInfoProvider.onStatus(RadioplayerRecognitionStatus.RUNNING)
        }

        every { audioRecorderThreadMock.start(any()) } answers {
            songInfoProvider.onRecognized(RadioplayerSongRecognitionResult())
            songInfoProvider.onRecognized(
                RadioplayerSongRecognitionResult(
                    image = byteArrayOf(),
                    externalIds = listOf(RadioplayerExternalId("apple_music", "id"))
                )
            )
            songInfoProvider.onRecognized(
                RadioplayerSongRecognitionResult(
                    image = byteArrayOf(
                        0x12,
                        0x34
                    )
                )
            )
        }

        every { audioRecorderThreadMock.stop() } just Runs
        every { radioplayerServiceMock.stopSongRecognition() } answers {
            cont!!.resume(Unit)
        }

        val resultFlow = songInfoProvider.getSongInfo()

        val job: Job

        suspendCoroutine<Unit> {
            cont = it
            job = launch { resultFlow.first() }
        }
        job.join()


        verifyOrder {
            radioplayerServiceMock.startSongRecognition(any())
            audioRecorderThreadMock.start(any())
        }
    }

    @Test
    fun getSongInfo_shouldStopRecognitionAfterLastUnsubscribe() = runBlocking {

        var cont: Continuation<Unit>? = null

        every { radioplayerServiceMock.startSongRecognition(any()) } answers {
            songInfoProvider.onStatus(RadioplayerRecognitionStatus.RUNNING)
        }
        every { audioRecorderThreadMock.start(any()) } answers {
            songInfoProvider.onRecognized(RadioplayerSongRecognitionResult())
        }
        every { audioRecorderThreadMock.stop() } just Runs
        every { radioplayerServiceMock.stopSongRecognition() } answers {
            cont!!.resume(Unit)
        }

        var job: Job

        suspendCoroutine<Unit> {
            cont = it
            job = launch {
                songInfoProvider.getSongInfo().first()
            }
        }

        job.join()

        verifyOrder {
            audioRecorderThreadMock.stop()
            radioplayerServiceMock.stopSongRecognition()
        }
    }

    fun getRecognitionState(provider: SongInfoProvider): String {
        val flowField = SongInfoProvider::class.java.getDeclaredField("recognitionStatusFlow")
        flowField.isAccessible = true
        val flow = flowField.get(provider) as kotlinx.coroutines.flow.StateFlow<*>
        return flow.value.toString()
    }

    @Test
    fun onStatus_shouldUpdateRecognitionStateCorrectly() = runBlocking {
        val states = mapOf(
            RadioplayerRecognitionStatus.UNKNOWN to "INIT",
            RadioplayerRecognitionStatus.STOPPED to "INIT",
            RadioplayerRecognitionStatus.RUNNING to "READY",
            RadioplayerRecognitionStatus.ERROR to "INIT"
        )

        states.forEach { (inputStatus, expectedState) ->
            val provider = SongInfoProvider(
                ServiceIdentifier.GRACENOTE_V1,
                radioplayerServiceMock,
                audioRecorderThreadMock
            )
            provider.onStatus(inputStatus)
            delay(50)
            val currentState = getRecognitionState(provider)
            assert(currentState == expectedState) {
                "Expected state $expectedState but was $currentState"
            }
        }
    }

    @Test
    fun stopAudioRecording_shouldHandlePipeNullAndNotNull() {
        every { audioRecorderThreadMock.stop() } just Runs

        val pipeField = SongInfoProvider::class.java.getDeclaredField("pipe")
        pipeField.isAccessible = true

        val stopAudioRecordingMethod = SongInfoProvider::class.java.getDeclaredMethod("stopAudioRecording")
        stopAudioRecordingMethod.isAccessible = true

        // Pipe null case
        pipeField.set(songInfoProvider, null)
        stopAudioRecordingMethod.invoke(songInfoProvider)

        // Pipe non-null case
        pipeField.set(songInfoProvider, pipeMock)
        stopAudioRecordingMethod.invoke(songInfoProvider)

        verify { audioRecorderThreadMock.stop() }
        verify { outputStreamMock.close() }
    }

    @Test
    fun onRecognized_shouldEmitCorrectSongInfoAndImage() = runBlocking {
        every { radioplayerServiceMock.startSongRecognition(any()) } answers {
            songInfoProvider.onStatus(RadioplayerRecognitionStatus.RUNNING)
        }

        val result = RadioplayerSongRecognitionResult(
            title = "Title",
            album = "Album",
            artist = "Artist",
            year = "2025",
            genre = "Pop",
            mood = "Happy",
            tempo = "120",
            externalIds = listOf(RadioplayerExternalId("spotify", "123"), RadioplayerExternalId("unknown", "abc")),
            externalAlbumIds = listOf(RadioplayerExternalId("apple_music", "abc")),
            image = byteArrayOf(1, 2, 3),
            providerId = "rpId",
            providerUniqueId = "rpUniqueId"
        )
        every { audioRecorderThreadMock.start(any()) } answers {
            songInfoProvider.onRecognized(result)
        }
        val emittedPair = mutableListOf<Pair<SongInfoEntity, ImageEntity>>()
        val job = launch {
            songInfoProvider.getSongInfo().collect {
                emittedPair.add(it)
                cancel()
            }
        }

        songInfoProvider.onRecognized(result)
        job.join()

        val songInfo = emittedPair.first().first
        val image = emittedPair.first().second

        assert(songInfo.title == "Title")
        assert(songInfo.externalIds.any { it.originType == ExternalIdentifierEntity.Companion.OriginType.SPOTIFY})
        assert(songInfo.externalAlbumIds.any { it.originType == ExternalIdentifierEntity.Companion.OriginType.APPLE})
        assert(image.imageData?.size == 3)
    }

    @Test
    fun selectNetwork_shouldUpdateApnConfig() {
        val networkInfoMock = mockk<NetworkInfo>()
        every { networkInfoMock.interfaceName } returns "eth0"
        every { radioplayerServiceMock.updateApnConfigEntry(any(), any()) } just Runs

        songInfoProvider.selectNetwork(networkInfoMock)
        verify { radioplayerServiceMock.updateApnConfigEntry(GnServiceRequestNr.SONGRECOGNITION, "eth0") }

        songInfoProvider.selectNetwork(null)
        verify(exactly = 2) {
            radioplayerServiceMock.updateApnConfigEntry(GnServiceRequestNr.SONGRECOGNITION, "eth0")
        }
    }

    @Test
    fun onRecognized_shouldMapExternalAlbumIdsOriginTypeCorrectly() = runBlocking {
        every { radioplayerServiceMock.startSongRecognition(any()) } answers {
            songInfoProvider.onStatus(RadioplayerRecognitionStatus.RUNNING)
        }

        val externalAlbumIds = listOf(
            RadioplayerExternalId("apple_music", "album_apple"),
            RadioplayerExternalId("amazon_music", "album_amazon"),
            RadioplayerExternalId("spotify_music", "album_spotify"),
            RadioplayerExternalId("unknown_source", "album_unknown")
        )

        val result = RadioplayerSongRecognitionResult(
            externalAlbumIds = externalAlbumIds
        )

        every { audioRecorderThreadMock.start(any()) } answers {
            songInfoProvider.onRecognized(result)
        }

        val emittedResults = mutableListOf<Pair<SongInfoEntity, ImageEntity>>()
        val job = launch {
            songInfoProvider.getSongInfo().collect {
                emittedResults.add(it)
                cancel() // cancel after first emission to avoid infinite collection
            }
        }

        songInfoProvider.onRecognized(result)
        job.join()

        val externalAlbumIdsMapped = emittedResults.first().first.externalAlbumIds

        assert(externalAlbumIdsMapped.any { it.originType == ExternalIdentifierEntity.Companion.OriginType.APPLE })
        assert(externalAlbumIdsMapped.any { it.originType == ExternalIdentifierEntity.Companion.OriginType.AMAZON })
        assert(externalAlbumIdsMapped.any { it.originType == ExternalIdentifierEntity.Companion.OriginType.SPOTIFY })

        // The unknown source should not be mapped, so it should NOT be in externalAlbumIdsMapped
        assert(externalAlbumIdsMapped.none { it.id == "album_unknown" })
    }


    @After
    fun tearDown() {
        unmockkAll()
    }
}


package technology.cariad.entertainment.metadataservice.data.provider.songinfo

import technology.cariad.entertainment.metadataservice.common.io.IPipe
import technology.cariad.entertainment.metadataservice.common.io.IPipeFactory
import technology.cariad.entertainment.metadataservice.common.io.generateFilename
import technology.cariad.entertainment.metadataservice.common.log.logger
import technology.cariad.entertainment.metadataservice.common.network.NetworkInfo
import technology.cariad.entertainment.metadataservice.data.provider.GnServiceRequestNr
import technology.cariad.entertainment.metadataservice.data.provider.radioplayer.IRadioplayerService
import technology.cariad.entertainment.metadataservice.data.provider.radioplayer.IRadioplayerSongRecognitionCallback
import technology.cariad.entertainment.metadataservice.data.provider.radioplayer.IRadioplayerStationCallback
import technology.cariad.entertainment.metadataservice.data.provider.radioplayer.RadioplayerPositionData
import technology.cariad.entertainment.metadataservice.data.provider.radioplayer.RadioplayerRecognitionStatus
import technology.cariad.entertainment.metadataservice.data.provider.radioplayer.RadioplayerSongRecognitionResult
import technology.cariad.entertainment.metadataservice.data.provider.radioplayer.RadioplayerStationInfo
import technology.cariad.entertainment.metadataservice.data.provider.songinfo.audiorecorder.IAudioRecorderThread
import technology.cariad.entertainment.metadataservice.domain.ISongInfoProvider
import technology.cariad.entertainment.metadataservice.domain.entity.ExternalIdentifierEntity
import technology.cariad.entertainment.metadataservice.domain.entity.ImageEntity
import technology.cariad.entertainment.metadataservice.domain.entity.ImageEntity.Companion.ImageType
import technology.cariad.entertainment.metadataservice.domain.entity.SongInfoEntity
import technology.cariad.entertainment.metadataservice.domain.value.ServiceIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SongInfoProvider(
    override val serviceId: ServiceIdentifier,
    private val radioplayerService: IRadioplayerService,
    private val audioRecorderThread: IAudioRecorderThread
) : ISongInfoProvider, IRadioplayerSongRecognitionCallback {

    companion object {
        private const val RUNNING_STATUS_TIMEOUT_MS = 1500L
        private const val RUNNING_STATUS_MAX_RETRIES = 2
        private const val DEFAULT_FALLBACK_INTERFACE = "eth0"
    }

    private val log by logger()

    private enum class RecognitionState {
        INIT, AWAIT_RUNNING, READY, RECORDING, AWAIT_STOPPED, ERROR, FAILED
    }

    private var pipe: IPipe? = null
    private var selectedInterfaceName: String? = null

    private val mutableResultFlow = MutableSharedFlow<Pair<SongInfoEntity, ImageEntity>>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val recognitionStatusFlow = MutableStateFlow(RecognitionState.INIT)

    private val resultFlow = mutableResultFlow.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.Default)
    private val job: Job
    private var runningStatusWatchdog: Job? = null
    private var stationChangeWatchdog: Job? = null
    private var runningStatusRetryCount: Int = 0
    private var recognitionSessionId: Long = 0
    private var lastObservedStationProviderId: String? = null

    init {
        radioplayerService.registerCallback(this)

        job = mutableResultFlow.subscriptionCount
            .map { count ->
                log.info("subscriptionCount_map") { "count=$count" }
                count > 0
            }
            .distinctUntilChanged()
            .combine(recognitionStatusFlow) { isActive, state ->
                log.info("subscriptionCount_combine") {
                    "isActive=$isActive state=$state"
                }

                if (isActive) {
                    when (state) {
                        RecognitionState.INIT -> {
                            if (recognitionStatusFlow.value == RecognitionState.AWAIT_STOPPED ||
                                recognitionStatusFlow.value == RecognitionState.INIT) {
                                recognitionStatusFlow.value = RecognitionState.AWAIT_RUNNING
                                startSongRecognition()
                            } else {
                                log.warn("subscriptionCount_combine") {
                                    "Wrong state transition: ${recognitionStatusFlow.value} -> $state"
                                }
                            }
                        }
                        RecognitionState.READY -> {
                            recognitionStatusFlow.value = RecognitionState.RECORDING
                            if (radioplayerService.requiresAudioCapture()) {
                                startAudioRecording()
                            } else {
                                log.info("subscriptionCount_combine") {
                                    "metadata-only provider detected; skipping audio capture while awaiting server-pushed station metadata"
                                }
                            }
                        }
                        RecognitionState.ERROR -> {
                            recognitionStatusFlow.value = RecognitionState.FAILED
                            stopAudioRecording()
                            log.error("subscriptionCount_combine") {
                                "Received error: song recognition unavailable."
                            }
                        }
                        else -> {
                        }
                    }
                } else {
                    when (state) {
                        RecognitionState.RECORDING -> {
                            recognitionStatusFlow.value = RecognitionState.AWAIT_STOPPED
                            stopSongRecognition()
                        }
                        RecognitionState.ERROR -> {
                            recognitionStatusFlow.value = RecognitionState.FAILED
                            log.error("subscriptionCount_combine") {
                                "Received error: song recognition unavailable."
                            }
                        }
                        RecognitionState.FAILED -> {
                            recognitionStatusFlow.value = RecognitionState.INIT
                            log.warn("subscriptionCount_combine") {
                                "Trying to recover from error state"
                            }
                        }
                        else -> {
                        }
                    }
                }
            }.launchIn(scope)
    }

    /**
     * @see [ISongInfoProvider.getSongInfo]
     */
    override fun getSongInfo(): Flow<Pair<SongInfoEntity, ImageEntity>> {
        log.info(this::getSongInfo.name) { "register new subscriber" }
        return resultFlow
    }

    /**
     * @see [IRadioplayerSongRecognitionCallback.onRecognized]
     */
    override fun onRecognized(result: RadioplayerSongRecognitionResult) {
        log.info(this::onRecognized.name) { "new track recognized: updating metadata" }

        val metadata = SongInfoEntity(
            title = result.title,
            album = result.album,
            artist = result.artist,
            year = result.year,
            genre = result.genre,
            mood = result.mood,
            tempo = result.tempo,
            externalIds = result.externalIds.mapNotNull {
                val originType = it.source?.let { src ->
                    if (src.contains("apple")) {
                        return@let ExternalIdentifierEntity.Companion.OriginType.APPLE
                    }
                    if (src.contains("amazon")) {
                        return@let ExternalIdentifierEntity.Companion.OriginType.AMAZON
                    }
                    if (src.contains("spotify")) {
                        return@let ExternalIdentifierEntity.Companion.OriginType.SPOTIFY
                    } else {
                        null
                    }
                }

                originType?.let { org -> ExternalIdentifierEntity(org, it.value!!) }
            },
            externalAlbumIds = result.externalAlbumIds.mapNotNull {
                val originType = it.source?.let { src ->
                    if (src.contains("apple")) {
                        return@let ExternalIdentifierEntity.Companion.OriginType.APPLE
                    }
                    if (src.contains("amazon")) {
                        return@let ExternalIdentifierEntity.Companion.OriginType.AMAZON
                    }
                    if (src.contains("spotify")) {
                        return@let ExternalIdentifierEntity.Companion.OriginType.SPOTIFY
                    } else {
                        null
                    }
                }

                originType?.let { org -> ExternalIdentifierEntity(org, it.value!!) }
            },
            gracenoteId = result.providerId,
            gracenoteUniqueId = result.providerUniqueId
        )

        val filename = if (result.image != null && result.image.isNotEmpty()) {
            generateFilename("jpg")
        } else ""


        val image = ImageEntity(
            fileName = filename,
            imageType = ImageType.COVER_ART,
            imageData = result.image?.toList()
        )

        val success = mutableResultFlow.tryEmit(Pair(metadata, image))

        if (!success) {
            log.error(this::onRecognized.name) { "failed to emit recognition result" }
        }
    }

    override fun onStatus(status: RadioplayerRecognitionStatus) {
        val hasSubscribers = mutableResultFlow.subscriptionCount.value > 0
        val currentState = recognitionStatusFlow.value

        // STOPPED can be emitted during internal retry restarts; keep state stable to avoid
        // duplicate start loops while already waiting for RUNNING.
        if (status == RadioplayerRecognitionStatus.STOPPED &&
            hasSubscribers &&
            currentState == RecognitionState.AWAIT_RUNNING
        ) {
            log.info(this::onStatus.name) {
                "ignore transitional STOPPED while awaiting RUNNING"
            }
            return
        }

        if (status == RadioplayerRecognitionStatus.RUNNING) {
            runningStatusRetryCount = 0
            runningStatusWatchdog?.cancel()
            runningStatusWatchdog = null
        }

        recognitionStatusFlow.tryEmit(
            when (status) {
                RadioplayerRecognitionStatus.UNKNOWN -> RecognitionState.ERROR
                RadioplayerRecognitionStatus.STOPPED -> RecognitionState.INIT
                RadioplayerRecognitionStatus.RUNNING -> RecognitionState.READY
                RadioplayerRecognitionStatus.ERROR -> RecognitionState.ERROR
            }.also { log.info(this::onStatus.name) { "received status=$status new state=$it" } }
        )
    }

    private fun startSongRecognition() {
        log.info(this::startSongRecognition.name) { "starting song recognition" }

        recognitionSessionId += 1
        runningStatusRetryCount = 0
        startSongRecognitionSession(recognitionSessionId)
        scheduleRunningStatusFallback(recognitionSessionId)
        startStationChangeMonitoring()

        log.info(this::startSongRecognition.name) { "started song recognition" }
    }

    private fun startStationChangeMonitoring() {
        if (mutableResultFlow.subscriptionCount.value <= 0) {
            return
        }

        stationChangeWatchdog?.cancel()
        stationChangeWatchdog = scope.launch {
            while (isActive && mutableResultFlow.subscriptionCount.value > 0) {
                val currentStationProviderId = resolveCurrentStationProviderId()
                if (currentStationProviderId.isNullOrBlank()) {
                    delay(2000L)
                    continue
                }

                if (lastObservedStationProviderId == null) {
                    lastObservedStationProviderId = currentStationProviderId
                    log.info(this@SongInfoProvider::startStationChangeMonitoring.name) {
                        "station monitor initialized with providerId=$currentStationProviderId"
                    }
                } else if (currentStationProviderId != lastObservedStationProviderId) {
                    val previousValue = lastObservedStationProviderId
                    lastObservedStationProviderId = currentStationProviderId
                    log.info(this@SongInfoProvider::startStationChangeMonitoring.name) {
                        "radio station changed: $previousValue -> $currentStationProviderId; refreshing RPUID-based subscription"
                    }
                    recognitionStatusFlow.value = RecognitionState.AWAIT_RUNNING
                    radioplayerService.stopSongRecognition()
                    startSongRecognition()
                    return@launch
                }

                delay(2000L)
            }
        }
    }

    private fun resolveCurrentStationProviderId(): String? {
        val providerIdRef = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)

        radioplayerService.requestRadioStationData(
            RadioplayerPositionData(),
            RadioplayerStationInfo(),
            0,
            object : IRadioplayerStationCallback {
                override fun onRadioStationData(name: String, providerId: String, imgData: ByteArray) {
                    providerIdRef.set(providerId.ifBlank { null })
                    latch.countDown()
                }
            }
        )

        latch.await(2, TimeUnit.SECONDS)
        return providerIdRef.get()
    }

    private fun startSongRecognitionSession(sessionId: Long) {
        if (!radioplayerService.requiresAudioCapture()) {
            log.info(this::startSongRecognitionSession.name) {
                "metadata-only RadioPlayer flow: no audio capture required; waiting for server-pushed song metadata"
            }
            radioplayerService.startSongRecognition(0)
            return
        }

        pipe?.apply { writeStream.close() }

        pipe = IPipeFactory.createPipe().apply {
            val fd = readFd.detach()
            radioplayerService.startSongRecognition(fd)
        }

        log.info(this::startSongRecognitionSession.name) {
            "started recognition sessionId=$sessionId"
        }
    }

    private fun scheduleRunningStatusFallback(sessionId: Long) {
        runningStatusWatchdog?.cancel()
        runningStatusWatchdog = scope.launch {
            delay(RUNNING_STATUS_TIMEOUT_MS)

            if (sessionId != recognitionSessionId) {
                log.info(this@SongInfoProvider::scheduleRunningStatusFallback.name) {
                    "ignore stale watchdog for sessionId=$sessionId"
                }
                return@launch
            }

            val hasSubscribers = mutableResultFlow.subscriptionCount.value > 0
            if (hasSubscribers && recognitionStatusFlow.value == RecognitionState.AWAIT_RUNNING) {
                if (runningStatusRetryCount < RUNNING_STATUS_MAX_RETRIES) {
                    runningStatusRetryCount += 1
                    log.warn(this@SongInfoProvider::scheduleRunningStatusFallback.name) {
                        "RUNNING status timeout; retry ${runningStatusRetryCount}/$RUNNING_STATUS_MAX_RETRIES"
                    }
                    radioplayerService.stopSongRecognition()
                    startSongRecognitionSession(sessionId)
                    scheduleRunningStatusFallback(sessionId)
                    return@launch
                }

                log.warn(this@SongInfoProvider::scheduleRunningStatusFallback.name) {
                    "RUNNING status timeout; marking recognition as failed"
                }
                runningStatusRetryCount = 0
                radioplayerService.stopSongRecognition()
                recognitionStatusFlow.tryEmit(RecognitionState.ERROR)
            }
        }
    }

    private fun startAudioRecording() {
        log.info(this::startAudioRecording.name) { "start audio recording" }

        pipe?.run {
            audioRecorderThread.start(writeStream)
        }

        log.info(this::startAudioRecording.name) { "started audio recording" }
    }

    private fun stopSongRecognition() {
        log.info(this::stopSongRecognition.name) { "stopping song recognition" }

        stationChangeWatchdog?.cancel()
        stationChangeWatchdog = null
        runningStatusWatchdog?.cancel()
        runningStatusWatchdog = null
        runningStatusRetryCount = 0
        recognitionSessionId += 1

        stopAudioRecording()
        radioplayerService.stopSongRecognition()

        mutableResultFlow.resetReplayCache()
        mutableResultFlow.tryEmit(
            SongInfoEntity() to ImageEntity(
                fileName = "",
                imageData = listOf(),
                imageType = ImageType.COVER_ART
            )
        )

        log.info(this::stopSongRecognition.name) { "stopped song recognition" }
    }

    private fun stopAudioRecording() {
        if (!radioplayerService.requiresAudioCapture()) {
            log.info(this::stopAudioRecording.name) {
                "metadata-only RadioPlayer flow: no audio recording to stop"
            }
            return
        }

        log.info(this::stopAudioRecording.name) { "stopping audio recording" }

        if (pipe != null) {
            audioRecorderThread.stop()
            pipe?.apply { writeStream.close() }
            pipe = null

            log.info(this::stopAudioRecording.name) { "stopped audio recording" }
        } else {
            log.warn(this::stopAudioRecording.name) { "audio recording already stopped" }
        }
    }

    /**
     * @see [ISongInfoProvider.selectNetwork]
     */
    override fun selectNetwork(networkInfo: NetworkInfo?) {
        val interfaceName = networkInfo?.interfaceName
            ?: selectedInterfaceName
            ?: DEFAULT_FALLBACK_INTERFACE

        if (networkInfo?.interfaceName == null) {
            log.warn(this::selectNetwork.name) {
                "networkInfo missing - fallback interface applied: $interfaceName"
            }
        }

        selectedInterfaceName = interfaceName

        log.info(this::selectNetwork.name) {
            "set network to: networkInfo=$networkInfo, interface=$interfaceName"
        }

        radioplayerService.updateApnConfigEntry(
            GnServiceRequestNr.SONGRECOGNITION,
            interfaceName
        )
    }
}


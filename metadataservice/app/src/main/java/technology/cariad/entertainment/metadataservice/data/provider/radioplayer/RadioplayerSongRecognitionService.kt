package technology.cariad.entertainment.metadataservice.data.provider.radioplayer

import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import org.radioplayer.automotive.core.AutomotiveSDK
import technology.cariad.entertainment.metadataservice.common.log.logger
import technology.cariad.entertainment.metadataservice.data.provider.GnServiceRequestNr
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

class RadioplayerSongRecognitionService(
    private val appContext: Context
) : IRadioplayerService {

    private val log by logger()
    private val callbacks = CopyOnWriteArraySet<IRadioplayerSongRecognitionCallback>()
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var activeObserver: Observer<Any>? = null

    @Volatile
    private var activeLiveData: LiveData<Any>? = null

    @Volatile
    private var lastKnownRpuid: String? = null

    @Volatile
    private var lastKnownStationFingerprint: String? = null

    @Volatile
    private var lastRecognitionFingerprint: String? = null

    @Volatile
    private var recognitionRequested: Boolean = false

    override fun getSdkVersionInfo(): String {
        return "Radioplayer SDK"
    }

    override fun initializeService() {
        log.info(this::initializeService.name) {
            "Radioplayer-backed song recognition initialized"
        }
    }

    override fun doTextLookupForAlbum(
        title: String?,
        artist: String?,
        album: String?,
        callback: IRadioplayerCoverArtLookupCallback
    ) {
        worker.execute {
            val bytes = runCatching { fetchCoverArtBytes(title, artist, album) }
                .onFailure {
                    log.warn(this::doTextLookupForAlbum.name) {
                        "Radioplayer cover-art lookup failed: ${it.message}"
                    }
                }
                .getOrNull()

            callback.onCoverArtReceived(bytes)
        }
    }

    override fun startSongRecognition(fd: Int) {
        recognitionRequested = true
        callbacks.forEach { it.onStatus(RadioplayerRecognitionStatus.RUNNING) }

        val currentStationFingerprint = resolveCurrentStationFingerprint()
        if (!currentStationFingerprint.isNullOrBlank()) {
            val normalizedFingerprint = normalizeStationFingerprint(currentStationFingerprint)
            val previousFingerprint = lastKnownStationFingerprint
            if (!previousFingerprint.isNullOrBlank() && normalizedFingerprint != previousFingerprint) {
                log.info(this::startSongRecognition.name) {
                    "station changed: $previousFingerprint -> $normalizedFingerprint; invalidating stale RPUID=${lastKnownRpuid ?: "<none>"}"
                }
                lastKnownRpuid = null
            }
            lastKnownStationFingerprint = normalizedFingerprint
        }

        val rpuid = lastKnownRpuid ?: resolveRpuidFromActiveMediaBrowser()
        if (rpuid.isNullOrBlank()) {
            log.warn(this::startSongRecognition.name) {
                "No RPUID available yet; deferring Radioplayer metadata subscription"
            }
            return
        }

        lastKnownRpuid = rpuid
        subscribeToMetadata(rpuid)
    }

    override fun stopSongRecognition() {
        recognitionRequested = false
        unsubscribeMetadata()
    }

    override fun requestRadioStationData(
        positionData: RadioplayerPositionData,
        stationInfo: RadioplayerStationInfo,
        countryCode: Int,
        callback: IRadioplayerStationCallback
    ) {
        worker.execute {
            runCatching {
                val providerId = resolveRpuidFromActiveMediaBrowser() ?: ""
                val name = if (providerId.isNotBlank()) "RadioPlayer Station" else ""

                if (providerId.isNotBlank()) {
                    lastKnownRpuid = providerId

                    if (recognitionRequested && activeLiveData == null) {
                        subscribeToMetadata(providerId)
                    }
                }

                val imgData = if (providerId.isNotBlank()) {
                    fetchStationImageBytes(providerId) ?: ByteArray(0)
                } else {
                    ByteArray(0)
                }

                callback.onRadioStationData(name, providerId, imgData)
            }.onFailure {
                log.warn(this::requestRadioStationData.name) {
                    "station lookup failed: ${it.message}"
                }
                callback.onRadioStationData("", "", ByteArray(0))
            }
        }
    }

    override fun requestLyrics(
        type: RadioplayerLyricsType,
        familyFriendly: Boolean,
        callback: IRadioplayerSongLyricsCallback
    ) {
        log.info(this::requestLyrics.name) {
            "lyrics are not supported by Radioplayer adapter"
        }
        callback.onLyricsReceived(0, null)
    }

    override fun registerCallback(callback: IRadioplayerSongRecognitionCallback) {
        callbacks += callback
    }

    override fun unregisterCallback(callback: IRadioplayerSongRecognitionCallback) {
        callbacks -= callback
    }

    override fun updateApnConfigEntry(requestNr: GnServiceRequestNr, networkInterface: String?) {
        log.info(this::updateApnConfigEntry.name) {
            "ignored APN config for Radioplayer adapter: requestNr=$requestNr network=$networkInterface"
        }
    }

    override fun setMcc(mcc: Int) {
        log.info(this::setMcc.name) { "ignored MCC update for Radioplayer adapter: $mcc" }
    }

    override fun setCountryOfSale(country: String) {
        log.info(this::setCountryOfSale.name) {
            "ignored country-of-sale update for Radioplayer adapter: $country"
        }
    }

    private fun subscribeToMetadata(rpuid: String) {
        log.info("RPUID_FETCH_CONFIRMATION") {
            "Subscribing to RadioPlayer metadata with resolved RPUID=$rpuid"
        }

        unsubscribeMetadata()
        val liveData = runCatching {
            @Suppress("UNCHECKED_CAST")
            AutomotiveSDK.services.realTimeMetadataService
                .metadataLiveData(rpuid) as LiveData<Any>
        }.getOrElse {
            log.warn(this::subscribeToMetadata.name) {
                "Radioplayer metadata subscription unavailable: ${it.message}"
            }
            return
        }

        val observer = Observer<Any> { sharedMetadata ->
            handleSharedMetadata(sharedMetadata)
        }

        activeObserver = observer
        activeLiveData = liveData
        mainHandler.post {
            liveData.observeForever(observer)
        }
    }

    private fun unsubscribeMetadata() {
        val liveData = activeLiveData
        val observer = activeObserver
        if (liveData != null && observer != null) {
            mainHandler.post {
                liveData.removeObserver(observer)
            }
        }

        activeLiveData = null
        activeObserver = null
        lastRecognitionFingerprint = null

        callbacks.forEach { it.onStatus(RadioplayerRecognitionStatus.STOPPED) }
    }

    private fun handleSharedMetadata(sharedMetadata: Any) {
        val nowPlaying = invokeNoArg(sharedMetadata, "getNowPlaying") ?: return
        val song = invokeNoArg(nowPlaying, "getSong")
        val programme = invokeNoArg(nowPlaying, "getProgramme")

        val title = invokeString(song, "getTitle")
        val artist = invokeString(song, "getArtist")
        val album = invokeString(programme, "getTitle")
        val imageUrl = invokeString(song, "getImageUrl").ifBlank {
            invokeString(programme, "getImageUrl")
        }

        val fingerprint = "$title|$artist|$album|$imageUrl"
        if (fingerprint == lastRecognitionFingerprint) {
            return
        }
        lastRecognitionFingerprint = fingerprint

        val imageBytes = fetchImageBytes(imageUrl)
        val result = RadioplayerSongRecognitionResult(
            title = title.ifBlank { null },
            artist = artist.ifBlank { null },
            album = album.ifBlank { null },
            externalIds = emptyList<RadioplayerExternalId>(),
            externalAlbumIds = emptyList<RadioplayerExternalId>(),
            image = imageBytes
        )

        callbacks.forEach { it.onRecognized(result) }
    }

    private fun fetchCoverArtBytes(title: String?, artist: String?, album: String?): ByteArray? {
        val query = listOfNotNull(title, artist, album)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (query.isBlank()) {
            return null
        }

        val searchResult = AutomotiveSDK.services.radioSearchService
            .searchRadioStations(query)
            .get()

        val stations = mutableListOf<Any>()
        searchResult.fold(
            onSuccess = { payload ->
                when (payload) {
                    is Iterable<*> -> payload.filterNotNull().forEach { stations += it }
                    else -> {
                        val maybeResults = invokeNoArg(payload, "getResults")
                        if (maybeResults is Iterable<*>) {
                            maybeResults.filterNotNull().forEach { stations += it }
                        }
                    }
                }
            },
            onFailure = {
                log.warn(this::fetchCoverArtBytes.name) {
                    "searchRadioStations failed: ${it.message}"
                }
            }
        )

        val rpuid = stations
            .asSequence()
            .map { invokeString(it, "getRpuid") }
            .firstOrNull { it.isNotBlank() }
            ?: return null

        lastKnownRpuid = rpuid

        val closestImageResult = AutomotiveSDK.services.radioStationService
            .getClosestRadioStationImageBySizeAsync(rpuid, 512, 512)
            .get()

        var imageUrl: String? = null
        closestImageResult.fold(
            onSuccess = { image ->
                imageUrl = invokeString(image, "getMultimediaUrl")
                    .ifBlank { invokeString(image, "getUrl") }
            },
            onFailure = {
                log.warn(this::fetchCoverArtBytes.name) {
                    "getClosestRadioStationImageBySizeAsync failed: ${it.message}"
                }
            }
        )

        if (imageUrl.isNullOrBlank()) {
            val imageListResult = AutomotiveSDK.services.radioStationService
                .getRadioStationImagesAsync(rpuid)
                .get()

            imageListResult.fold(
                onSuccess = { images ->
                    if (images is Iterable<*>) {
                        val firstImage = images.firstOrNull { it != null }
                        imageUrl = firstImage?.let {
                            invokeString(it, "getMultimediaUrl")
                                .ifBlank { invokeString(it, "getUrl") }
                        }
                    }
                },
                onFailure = {
                    log.warn(this::fetchCoverArtBytes.name) {
                        "getRadioStationImagesAsync failed: ${it.message}"
                    }
                }
            )
        }

        return fetchImageBytes(imageUrl)
    }

    internal fun extractStationImageUrl(station: Any?): String {
        if (station == null) {
            return ""
        }

        return sequenceOf(
            "getMultimediaUrl",
            "getImageUrl",
            "getLogoUrl",
            "getUrl",
            "getLogo",
            "getStationImageUrl"
        )
            .map { invokeString(station, it) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }


    private fun stationFrequencyValue(station: Any?): String {
        if (station == null) return ""
        return sequenceOf(
            "getFrequency",
            "getFrequencyKhz",
            "getFrequencyHz",
            "getStationFrequency",
            "getFrequencyInKhz",
            "getFrequencyInHz"
        )
            .map { invokeString(station, it) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun stationBandValue(station: Any?): String {
        if (station == null) return ""
        return sequenceOf(
            "getBand",
            "getStationBand",
            "getType",
            "getMedium"
        )
            .map { invokeString(station, it) }
            .filter { it.isNotBlank() }
            .firstOrNull()
            ?.lowercase()
            .orEmpty()
    }

    private fun matchesFrequencyApprox(actual: String?, expected: String?): Boolean {
        if (actual.isNullOrBlank() || expected.isNullOrBlank()) return false

        val actualNumber = actual.filter { it.isDigit() || it == '.' || it == ',' }
            .replace(',', '.')
            .toDoubleOrNull() ?: return false
        val expectedNumber = expected.filter { it.isDigit() || it == '.' || it == ',' }
            .replace(',', '.')
            .toDoubleOrNull() ?: return false

        return kotlin.math.abs(actualNumber - expectedNumber) < 0.5
    }

    private fun resolveRpuidFromActiveMediaBrowser(): String? {
        val currentStationFingerprint = resolveCurrentStationFingerprint()
            ?.let(::normalizeStationFingerprint)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val mediaSessionManager = runCatching {
            appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        }.getOrNull() ?: return null

        val activeSessions = runCatching {
            mediaSessionManager.getActiveSessions(null)
        }.getOrNull() ?: return null

        return activeSessions.firstNotNullOfOrNull { controller ->
            val controllerStationFingerprint = extractStationFingerprintFromController(controller)
                ?.let(::normalizeStationFingerprint)
                ?.takeIf { it.isNotBlank() }

            if (!controllerStationFingerprint.isNullOrBlank() && controllerStationFingerprint != currentStationFingerprint) {
                log.info(this::resolveRpuidFromActiveMediaBrowser.name) {
                    "Skipping stale controller RPUID for station fingerprint=$controllerStationFingerprint; active station is $currentStationFingerprint"
                }
                return@firstNotNullOfOrNull null
            }

            extractRpuidFromController(controller)
        }
    }

    private fun resolveCurrentStationFingerprint(): String? {
        val mediaSessionManager = runCatching {
            appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        }.getOrNull() ?: return null

        val activeSessions = runCatching {
            mediaSessionManager.getActiveSessions(null)
        }.getOrNull() ?: return null

        val activeStationFingerprint = resolveCurrentStationFingerprint()
        if (activeStationFingerprint.isNullOrBlank()) {
            log.warn(this::resolveRpuidFromActiveMediaBrowser.name) {
                "Skipping RPUID resolution because active media-browser station fingerprint is unavailable"
            }
            return null
        }

        return activeSessions.firstNotNullOfOrNull { controller ->
            extractRpuidFromController(controller, activeStationFingerprint)
        }
    }

    private fun extractStationFingerprintFromController(controller: MediaController): String? {
        val candidateBundles = mutableListOf<Bundle>()
        readMetadataExtras(controller)?.let { candidateBundles += it }
        readCurrentMediaItemExtras(controller)?.let { candidateBundles += it }
        readQueueItemExtras(controller)?.let { candidateBundles += it }

        for (bundle in candidateBundles) {
            val title = extractStationNameFromBundle(bundle)
            if (!title.isNullOrBlank()) {
                return title
            }
        }

        return null
    }

    private fun extractStationNameFromBundle(bundle: Bundle): String? {
        val preferredKeys = listOf(
            "android.media.metadata.TITLE",
            "android.media.metadata.DISPLAY_TITLE",
            "android.media.metadata.MEDIA_ID",
            "android.title",
            "title",
            "stationName",
            "simpleStationName",
            "radioStationName",
            "serviceName",
            "programName",
            "station",
            "name"
        )

        for (key in preferredKeys) {
            val value = bundle.get(key)
            if (value is String && value.isNotBlank() && !value.equals("null", ignoreCase = true)) {
                return normalizeStationFingerprint(value)
            }
        }

        for (key in bundle.keySet()) {
            val value = bundle.get(key)
            if (value is Bundle) {
                val nested = extractStationNameFromBundle(value)
                if (!nested.isNullOrBlank()) {
                    return nested
                }
            }
            if (key.lowercase().contains("title") || key.lowercase().contains("station") || key.lowercase().contains("program")) {
                if (value is String && value.isNotBlank() && !value.equals("null", ignoreCase = true)) {
                    return normalizeStationFingerprint(value)
                }
            }
        }

        return null
    }

    private fun normalizeStationFingerprint(value: String): String {
        return value.trim().replace("\\s+".toRegex(), " ").lowercase().replace("[^a-z0-9]".toRegex(), "")
    }

    private fun extractRpuidFromController(
        controller: MediaController,
        activeStationFingerprint: String? = null
    ): String? {
        val candidateBundles = mutableListOf<Bundle>()

        readMetadataExtras(controller)?.let { candidateBundles += it }
        readCurrentMediaItemExtras(controller)?.let { candidateBundles += it }
        readQueueItemExtras(controller)?.let { candidateBundles += it }

        val normalizedActiveStationFingerprint = activeStationFingerprint?.let(::normalizeStationFingerprint)

        for (bundle in candidateBundles) {
            if (!normalizedActiveStationFingerprint.isNullOrBlank()) {
                val bundleStationFingerprint = extractStationNameFromBundle(bundle)
                if (!bundleStationFingerprint.isNullOrBlank() && normalizeStationFingerprint(bundleStationFingerprint) != normalizedActiveStationFingerprint) {
                    continue
                }
            }

            val exact = extractRpuidFromBundle(bundle, exactOnly = true)
            if (!exact.isNullOrBlank()) {
                log.info("RPUID_FETCH_CONFIRMATION") {
                    "Successfully fetched RadioPlayerId from active media browser exact key: $exact"
                }
                return exact
            }

            val fuzzy = extractRpuidFromBundle(bundle, exactOnly = false)
            if (!fuzzy.isNullOrBlank()) {
                log.info("RPUID_FETCH_CONFIRMATION") {
                    "Successfully fetched RadioPlayerId from active media browser fuzzy key: $fuzzy"
                }
                return fuzzy
            }
        }

        return null
    }

    private fun readCurrentMediaItemExtras(controller: MediaController): List<Bundle> {
        val bundles = mutableListOf<Bundle>()

        val currentItem = runCatching {
            controller.javaClass.methods
                .firstOrNull { it.name == "getCurrentMediaItem" && it.parameterCount == 0 }
                ?.invoke(controller)
        }.getOrNull() ?: return bundles

        val description = runCatching {
            currentItem.javaClass.methods
                .firstOrNull { it.name == "getDescription" && it.parameterCount == 0 }
                ?.invoke(currentItem)
        }.getOrNull() ?: return bundles

        val extras = runCatching {
            description.javaClass.methods
                .firstOrNull { it.name == "getExtras" && it.parameterCount == 0 }
                ?.invoke(description) as? Bundle
        }.getOrNull() ?: return bundles

        if (extras.keySet().isNotEmpty()) {
            bundles += extras
        }

        return bundles
    }

    private fun readQueueItemExtras(controller: MediaController): List<Bundle> {
        val bundles = mutableListOf<Bundle>()

        val queue = runCatching {
            controller.javaClass.methods
                .firstOrNull { it.name == "getQueue" && it.parameterCount == 0 }
                ?.invoke(controller)
        }.getOrNull() ?: return bundles

        val queueItems = when (queue) {
            is Iterable<*> -> queue
            is Collection<*> -> queue
            else -> null
        } ?: return bundles

        for (queueItem in queueItems) {
            val description = runCatching {
                queueItem?.javaClass?.methods
                    ?.firstOrNull { it.name == "getDescription" && it.parameterCount == 0 }
                    ?.invoke(queueItem)
            }.getOrNull() ?: continue

            val extras = runCatching {
                description.javaClass.methods
                    .firstOrNull { it.name == "getExtras" && it.parameterCount == 0 }
                    ?.invoke(description) as? Bundle
            }.getOrNull() ?: continue

            if (extras.keySet().isNotEmpty()) {
                bundles += extras
            }
        }

        return bundles
    }

    private fun extractRpuidFromBundle(bundle: Bundle, exactOnly: Boolean): String? {
        val exactKeys = preferredRpuidKeys()
        for (key in exactKeys) {
            val value = bundle.get(key)
            val parsed = parseRpuidCandidate(value)
            if (!parsed.isNullOrBlank()) {
                return parsed
            }
        }

        if (exactOnly) {
            return null
        }

        val fuzzyKeys = bundle.keySet()
            .filter { isLikelyRpuidKey(it) }
            .sortedBy { it.length }

        for (key in fuzzyKeys) {
            val value = bundle.get(key)
            val parsed = parseRpuidCandidate(value)
            if (!parsed.isNullOrBlank()) {
                return parsed
            }
        }

        for (key in bundle.keySet()) {
            val value = bundle.get(key)
            if (value is Bundle) {
                val nested = extractRpuidFromBundle(value, exactOnly = false)
                if (!nested.isNullOrBlank()) {
                    return nested
                }
            }
        }

        return null
    }

    private fun preferredRpuidKeys(): List<String> = listOf(
        "de.eso.radio.radioplayerId",
        "de.eso.radio.rpuid",
        "de.eso.radio.RADIOPLAYER_ID",
        "radioplayerId",
        "rpuid",
        "radioPlayerId",
        "radioplayer_id",
        "radioplayerid",
        "radio_player_id",
        "de.eso.radio.stationId",
        "stationId",
        "station_id",
        "radioStationId",
        "radio_station_id"
    )

    private fun isLikelyRpuidKey(key: String): Boolean {
        val normalized = key.lowercase().replace("[^a-z0-9]".toRegex(), "")
        val hasRpuidMarker = normalized.contains("rpuid")
        val hasRadioplayerMarker = normalized.contains("radioplayer")
        val hasStationIdMarker = normalized.contains("station") && normalized.contains("id")
        return hasRpuidMarker || hasRadioplayerMarker || hasStationIdMarker
    }

    private fun readMetadataExtras(controller: MediaController): Bundle? {
        val metadata = controller.metadata ?: return null
        return runCatching {
            metadata.javaClass.methods
                .firstOrNull { it.name == "getExtras" && it.parameterCount == 0 }
                ?.invoke(metadata) as? Bundle
        }.getOrNull()
    }

    private fun parseRpuidCandidate(value: Any?): String? {
        return when (value) {
            is String -> {
                val sanitized = value.trim()
                when {
                    sanitized.isEmpty() -> null
                    sanitized.equals("null", ignoreCase = true) -> null
                    sanitized.any { it.isDigit() } -> sanitized
                    else -> null
                }
            }
            is Number -> value.toString().trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            is Bundle -> {
                value.keySet().asSequence()
                    .mapNotNull { key -> parseRpuidCandidate(value.get(key)) }
                    .firstOrNull { it.isNotBlank() }
            }
            else -> null
        }?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    private fun fetchImageBytes(url: String?): ByteArray? {
        if (url.isNullOrBlank()) {
            return null
        }

        return runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.inputStream.use { it.readBytes() }
        }.getOrNull()
    }

    private fun fetchStationImageBytes(rpuid: String): ByteArray? {
        if (rpuid.isBlank()) {
            return null
        }

        val closestImageResult = AutomotiveSDK.services.radioStationService
            .getClosestRadioStationImageBySizeAsync(rpuid, 512, 512)
            .get()

        var imageUrl: String? = null
        closestImageResult.fold(
            onSuccess = { image ->
                imageUrl = invokeString(image, "getMultimediaUrl")
                    .ifBlank { invokeString(image, "getUrl") }
            },
            onFailure = {
                log.warn(this::fetchStationImageBytes.name) {
                    "getClosestRadioStationImageBySizeAsync failed: ${it.message}"
                }
            }
        )

        if (imageUrl.isNullOrBlank()) {
            val imageListResult = AutomotiveSDK.services.radioStationService
                .getRadioStationImagesAsync(rpuid)
                .get()

            imageListResult.fold(
                onSuccess = { images ->
                    if (images is Iterable<*>) {
                        val firstImage = images.firstOrNull { it != null }
                        imageUrl = firstImage?.let {
                            invokeString(it, "getMultimediaUrl")
                                .ifBlank { invokeString(it, "getUrl") }
                        }
                    }
                },
                onFailure = {
                    log.warn(this::fetchStationImageBytes.name) {
                        "getRadioStationImagesAsync failed: ${it.message}"
                    }
                }
            )
        }

        return fetchImageBytes(imageUrl)
    }

    private fun invokeNoArg(receiver: Any?, method: String): Any? {
        if (receiver == null) return null
        return runCatching {
            receiver.javaClass.methods
                .firstOrNull { it.name == method && it.parameterCount == 0 }
                ?.invoke(receiver)
        }.getOrNull()
    }

    internal fun invokeString(receiver: Any?, method: String): String {
        return invokeNoArg(receiver, method)?.toString().orEmpty()
    }
}

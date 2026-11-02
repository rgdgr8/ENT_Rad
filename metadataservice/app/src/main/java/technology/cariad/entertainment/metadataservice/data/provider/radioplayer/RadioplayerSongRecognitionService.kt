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
import java.util.Collections
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.IdentityHashMap

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

        val rpuid = resolveRpuidFromActiveMediaBrowser()
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


    private fun resolveRpuidFromActiveMediaBrowser(): String? {
        val mediaSessionManager = runCatching {
            appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        }.getOrNull() ?: return null

        val activeSessions = runCatching {
            mediaSessionManager.getActiveSessions(null)
        }.getOrNull() ?: return null

        val activeIdentity = resolveActiveTunerIdentity(activeSessions) ?: return null
        val browserRoots = activeSessions.flatMap { controller ->
            listOfNotNull(
                readCurrentMediaItem(controller),
                readQueue(controller)
            )
        }
        val browserRpuid = findRpuidInTree(browserRoots, activeIdentity)
        if (!browserRpuid.isNullOrBlank()) {
            return browserRpuid
        }

        val sessionRoots = activeSessions.flatMap { controller ->
            listOfNotNull(readMetadataExtras(controller), controller.metadata)
        }
        return findRpuidInTree(sessionRoots, activeIdentity)
    }

    private data class StationIdentity(
        val bearer: String? = null,
        val service: String? = null,
        val mediaId: String? = null
    ) {
        fun matches(other: StationIdentity): Boolean {
            return listOf(bearer to other.bearer, service to other.service, mediaId to other.mediaId)
                .any { (expected, actual) ->
                    !expected.isNullOrBlank() && !actual.isNullOrBlank() && expected == actual
                }
        }
    }

    private fun resolveActiveTunerIdentity(activeSessions: List<MediaController>): StationIdentity? {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        return activeSessions.asSequence()
            .flatMap { controller ->
                sequenceOf(
                    readCurrentMediaItem(controller),
                    readMetadataExtras(controller),
                    controller.metadata
                )
            }
            .mapNotNull { root -> findStationIdentity(root, visited, 0) }
            .firstOrNull()
    }

    private fun extractStationIdentity(value: Any?): StationIdentity? {
        if (value == null) return null
        if (value is Bundle) {
            return StationIdentity(
                bearer = firstBundleValue(value, "bearer", "stationBearer", "radioBearer"),
                service = firstBundleValue(value, "serviceId", "stationServiceId", "dabServiceId", "sid"),
                mediaId = firstBundleValue(value, "mediaId", "media_id", "android.media.metadata.MEDIA_ID")
            ).takeIf { it.bearer != null || it.service != null || it.mediaId != null }
        }

        return StationIdentity(
            bearer = firstObjectValue(value, "getBearer", "getStationBearer", "getRadioBearer"),
            service = firstObjectValue(value, "getServiceId", "getStationServiceId", "getDabServiceId", "getSid"),
            mediaId = firstObjectValue(value, "getMediaId")
        ).takeIf { it.bearer != null || it.service != null || it.mediaId != null }
    }

    private fun findStationIdentity(value: Any?, visited: MutableSet<Any>, depth: Int): StationIdentity? {
        if (value == null || depth > 12 || !visited.add(value)) return null
        return extractStationIdentity(value)
            ?: treeChildren(value).asSequence()
                .mapNotNull { child -> findStationIdentity(child, visited, depth + 1) }
                .firstOrNull()
    }

    private fun firstBundleValue(bundle: Bundle, vararg keys: String): String? {
        return keys.asSequence()
            .mapNotNull { bundle.get(it)?.toString() }
            .map(::normalizeIdentityValue)
            .firstOrNull { it.isNotBlank() }
    }

    private fun firstObjectValue(value: Any, vararg methods: String): String? {
        return methods.asSequence()
            .mapNotNull { invokeNoArg(value, it)?.toString() }
            .map(::normalizeIdentityValue)
            .firstOrNull { it.isNotBlank() }
    }

    private fun normalizeIdentityValue(value: String): String {
        return value.trim().lowercase()
    }

    private fun readCurrentMediaItem(controller: MediaController): Any? {
        return runCatching {
            controller.javaClass.methods
                .firstOrNull { it.name == "getCurrentMediaItem" && it.parameterCount == 0 }
                ?.invoke(controller)
        }.getOrNull()
    }

    private fun readQueue(controller: MediaController): Any? {
        return runCatching {
            controller.javaClass.methods
                .firstOrNull { it.name == "getQueue" && it.parameterCount == 0 }
                ?.invoke(controller)
        }.getOrNull()
    }

    private fun readMetadataExtras(controller: MediaController): Bundle? {
        val metadata = controller.metadata ?: return null
        return runCatching {
            metadata.javaClass.methods
                .firstOrNull { it.name == "getExtras" && it.parameterCount == 0 }
                ?.invoke(metadata) as? Bundle
        }.getOrNull()
    }

    private fun findRpuidInTree(roots: List<Any?>, activeIdentity: StationIdentity): String? {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        return roots.asSequence()
            .mapNotNull { searchRpuid(it, activeIdentity, null, visited, 0) }
            .firstOrNull()
    }

    private fun searchRpuid(
        value: Any?,
        activeIdentity: StationIdentity,
        inheritedIdentity: StationIdentity?,
        visited: MutableSet<Any>,
        depth: Int
    ): String? {
        if (value == null || depth > 12 || !visited.add(value)) return null

        val identity = extractStationIdentity(value) ?: inheritedIdentity
        if (identity != null && activeIdentity.matches(identity)) {
            extractExactRpuid(value)?.let { rpuid ->
                log.info("RPUID_FETCH_CONFIRMATION") {
                    "Resolved RadioPlayerId=$rpuid using active tuner identity"
                }
                return rpuid
            }
        }

        return treeChildren(value).asSequence()
            .mapNotNull { child -> searchRpuid(child, activeIdentity, identity, visited, depth + 1) }
            .firstOrNull()
    }

    private fun extractExactRpuid(value: Any): String? {
        if (value is Bundle) {
            return exactRpuidKeys.asSequence()
                .mapNotNull { key -> parseRpuidCandidate(value.get(key)) }
                .firstOrNull()
        }

        return exactRpuidMethods.asSequence()
            .mapNotNull { method -> parseRpuidCandidate(invokeNoArg(value, method)) }
            .firstOrNull()
    }

    private val exactRpuidKeys = listOf(
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

    private val exactRpuidMethods = listOf(
        "getRadioplayerId",
        "getRpuid",
        "getRadioPlayerId",
        "getStationId",
        "getRadioStationId"
    )

    private fun parseRpuidCandidate(value: Any?): String? {
        return when (value) {
            is String -> {
                val sanitized = value.trim()
                when {
                    sanitized.isEmpty() -> null
                    sanitized.equals("null", ignoreCase = true) -> null
                    else -> sanitized
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

    private fun treeChildren(value: Any): List<Any> {
        return when (value) {
            is Bundle -> value.keySet().mapNotNull { value.get(it) }
            is Iterable<*> -> value.filterNotNull()
            is Array<*> -> value.filterNotNull()
            else -> listOfNotNull(
                invokeNoArg(value, "getDescription"),
                invokeNoArg(value, "getExtras"),
                invokeNoArg(value, "getMetadata"),
                invokeNoArg(value, "getCurrentMediaItem"),
                invokeNoArg(value, "getQueue"),
                invokeNoArg(value, "getMediaItem"),
                invokeNoArg(value, "getMediaDescription")
            )
        }
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

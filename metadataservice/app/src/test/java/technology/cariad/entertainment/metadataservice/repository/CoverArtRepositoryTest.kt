package technology.cariad.entertainment.metadataservice.repository

import technology.cariad.entertainment.metadataservice.common.network.NetworkInfo
import technology.cariad.entertainment.metadataservice.common.servicelist.Apn
import technology.cariad.entertainment.metadataservice.common.servicelist.HttpOperation
import technology.cariad.entertainment.metadataservice.common.servicelist.ServiceListEntry
import technology.cariad.entertainment.metadataservice.data.database.CachingDatabase
import technology.cariad.entertainment.metadataservice.data.provider.OnlineState
import technology.cariad.entertainment.metadataservice.domain.ICoverArtDataStore
import technology.cariad.entertainment.metadataservice.domain.ICoverArtProvider
import technology.cariad.entertainment.metadataservice.domain.IOnlineStateProvider
import technology.cariad.entertainment.metadataservice.domain.ITimeProvider
import technology.cariad.entertainment.metadataservice.domain.LookupMode
import technology.cariad.entertainment.metadataservice.domain.entity.CoverArtRequestDataEntity
import technology.cariad.entertainment.metadataservice.domain.value.ServiceIdentifier
import technology.cariad.entertainment.metadataservice.mock.android.TransactionMock
import technology.cariad.entertainment.metadataservice.mock.common.log.LoggerMock
import technology.cariad.entertainment.metadataservice.sampledata.SampleImage.Companion.IMAGE_ENTITY_2
import technology.cariad.entertainment.metadataservice.sampledata.SampleImage.Companion.IMAGE_ENTITY_WITH_IMAGE_DATA
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runBlockingTest
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test

private const val FAKE_URI = "uri"

class CoverArtRepositoryTest {

    init {
        LoggerMock()
    }

    private val database: CachingDatabase = mockk(relaxed = true)
    private val coverArtProvider: ICoverArtProvider = mockk(relaxed = true)
    private val coverArtDataStore: ICoverArtDataStore = mockk(relaxed = true)
    private val onlineStateProvider: IOnlineStateProvider = mockk(relaxed = true)
    private val timeProvider: ITimeProvider = mockk(relaxed = true)

    private val networkMock = mockk<NetworkInfo>()
    private lateinit var onlineStateMock: OnlineState
    private val serviceListEntry = ServiceListEntry(
        serviceId = ServiceIdentifier.GRACENOTE_V1,
        enabled = true,
        apn = Apn.APN_VENDOR,
        isTetheringAllowed = false,
        defaultHttpOperation = HttpOperation("default", "https://example.com", "global"),
        httpOperations = null
    )

    private lateinit var coverArtRepository: CoverArtRepository

    @Before
    fun setup() {
        coEvery { coverArtProvider.serviceId } returns ServiceIdentifier.GRACENOTE_V1
        every { onlineStateProvider.disableReasonsFlow(any()) } returns MutableStateFlow(null).asStateFlow()
        every { onlineStateProvider.isOnlineLookupAllowed(any()) } returns LookupMode.FULL
        every { onlineStateProvider.networkFlow(any()) } returns MutableStateFlow(networkMock).asStateFlow()
        every { onlineStateProvider.serviceListFlow(any()) } returns MutableStateFlow(serviceListEntry).asStateFlow()

        coverArtRepository =
            CoverArtRepository(
                coverArtProvider,
                coverArtDataStore,
                onlineStateProvider,
                timeProvider
            )

        TransactionMock(database)
        LoggerMock()
        
        // Initialize onlineStateMock after other setup
        onlineStateMock = mockk<OnlineState>()
    }

    @Test
    fun retrieveCoverArtImage_shouldReturnUriOfAlreadyCachedImage() =
        runBlockingTest {
            every { onlineStateProvider.onlineStateFlow(any(), any()) } returns flow {
                emit(onlineStateMock)
            }
            every { onlineStateMock.networkInfo } returns null

            coEvery { coverArtDataStore.getCoverArt(any()) } returns IMAGE_ENTITY_2

            val uri = coverArtRepository.retrieveCoverArtImage(
                CoverArtRequestDataEntity(
                    artist = "Madonna",
                    album = "Like a Prayer",
                    title = "Cherish",
                    lifeCycleData = null
                )
            )

            Assert.assertEquals(FAKE_URI, uri)

            coVerify { coverArtDataStore.getCoverArt(any()) }
            coVerify(inverse = true) { coverArtProvider.getCoverArtImage(any()) }
        }

    @Test
    fun retrieveCoverArtImage_shouldContinueBestEffortLookupWhenOfflineMode() =
        runBlockingTest {
            every { onlineStateProvider.isOnlineLookupAllowed(any()) } returns LookupMode.OFFLINE

            coEvery { coverArtDataStore.getCoverArt(any()) } returns null
            coEvery { coverArtProvider.getCoverArtImage(any()) } returns IMAGE_ENTITY_2

            val uri = coverArtRepository.retrieveCoverArtImage(
                CoverArtRequestDataEntity(
                    artist = "Madonna",
                    album = "Like a Prayer",
                    title = "Cherish",
                    lifeCycleData = null
                )
            )

            Assert.assertEquals(null, uri)

            coVerify {
                coverArtDataStore.getCoverArt(any())
                coverArtProvider.getCoverArtImage(any())
            }
        }

    @Test
    fun retrieveCoverArtImage_shouldReturnNullIfNotCachedAndOnlineImageDataNotValid() =
        runBlockingTest {
            coEvery { coverArtDataStore.getCoverArt(any()) } returns null
            coEvery { coverArtProvider.getCoverArtImage(any()) } returns IMAGE_ENTITY_2

            val uri = coverArtRepository.retrieveCoverArtImage(
                CoverArtRequestDataEntity(
                    artist = "Madonna",
                    album = "Like a Prayer",
                    title = "Cherish",
                    lifeCycleData = null
                )
            )

            Assert.assertEquals(null, uri)

            coVerify {
                coverArtDataStore.getCoverArt(any())
                coverArtProvider.getCoverArtImage(any())
            }

            coVerify(exactly = 0) {
                coverArtDataStore.addCoverArtLookup(any(), any())
            }

        }

    @Test
    fun retrieveCoverArtImage_shouldReturnValidUriIfNotCachedButOnlineImageDataValid() =
        runBlockingTest {
            coEvery { coverArtDataStore.getCoverArt(any()) } returns null
            coEvery {
                coverArtProvider.getCoverArtImage(any())
            } returns IMAGE_ENTITY_WITH_IMAGE_DATA
            coEvery { coverArtDataStore.addCoverArtLookup(any(), any()) } returns FAKE_URI

            val uri = coverArtRepository.retrieveCoverArtImage(
                CoverArtRequestDataEntity(
                    artist = "Madonna",
                    album = "Like a Prayer",
                    title = "Cherish",
                    lifeCycleData = null
                )
            )

            Assert.assertEquals(FAKE_URI, uri)

            coVerify {
                coverArtDataStore.getCoverArt(any())
                coverArtProvider.getCoverArtImage(any())
                coverArtDataStore.addCoverArtLookup(any(), any())
            }
        }

    @After
    fun tearDown() {
        unmockkAll()
    }
}


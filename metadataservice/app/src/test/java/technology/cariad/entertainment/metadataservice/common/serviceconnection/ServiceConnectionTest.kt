package technology.cariad.entertainment.metadataservice.common.serviceconnection

import android.content.Context
import android.content.Intent
import android.os.IBinder
import technology.cariad.entertainment.metadataservice.common.serviceresolver.ServiceResolver
import technology.cariad.entertainment.metadataservice.mock.common.log.LoggerMock
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifySequence
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test

class ServiceConnectionTest {

    private val contextMock = mockk<Context>(relaxed = true)
    private val serviceConnectionCallbackMock = mockk<ServiceConnectionCallback>(relaxed = true)
    private val serviceResolverMock = mockk<ServiceResolver>()
    private val intentMock = mockk<Intent>(relaxed = true)

    private val serviceConnection =
        ServiceConnection(serviceResolverMock, contextMock, "de.test.servicce")

    private val channelSlot = slot<ProducerScope<IBinder?>>()

    @Before
    fun setup() {
        LoggerMock() // Mock Android logging
        mockkObject(ServiceConnectionCallback)
        every { ServiceConnectionCallback.create(capture(channelSlot)) } returns serviceConnectionCallbackMock

        every { serviceResolverMock.resolve(any()) } returns intentMock
    }

    @Test
    fun test() = runTest {
        every { contextMock.bindService(any(), any(), any<Int>()) } returns true

        val updates = mutableListOf<IBinder?>()
        val job = launch { serviceConnection.serviceConnectionFlow().toList(updates) }
        
        advanceUntilIdle() // Let flow producer run completely

        channelSlot.captured.trySend(null)
        advanceUntilIdle() // Let toList() process the value
        
        channelSlot.captured.close(BindingDiedException())
        advanceUntilIdle() // Let toList() process the close
        
        channelSlot.captured.trySend(null) // This will fail since channel is closed
        advanceUntilIdle() // Let toList() finish

        Assert.assertEquals(2, updates.size)

        job.cancel()
    }

    @Test
    fun serviceConnectionFlow_shouldRetryOnBindingDied() = runTest {
        every { contextMock.bindService(any(), any(), any<Int>()) } returns true

        val job = launch { serviceConnection.serviceConnectionFlow().collect { } }
        
        advanceUntilIdle() // Let flow producer run completely

        channelSlot.captured.close(BindingDiedException())
        advanceUntilIdle() // Let the flow retry after binding died

        verifySequence {
            contextMock.bindService(any(), any(), any<Int>())
            contextMock.unbindService(any())
            contextMock.bindService(any(), any(), any<Int>())
        }

        confirmVerified(contextMock)

        job.cancel()
    }

    @Test(expected = NullBindingException::class)
    fun serviceConnectionFlow_shouldThrowOnNullBinding() = runTest {
        every { contextMock.bindService(any(), any(), any<Int>()) } returns true

        val job = launch { serviceConnection.serviceConnectionFlow().collect { } }
        
        advanceUntilIdle() // Let flow producer run completely
        
        channelSlot.captured.close(NullBindingException())
        job.join()
    }

    @Test
    fun serviceConnectionFlow_shouldUnbindOnClose() = runTest {
        every { contextMock.bindService(any(), any(), any<Int>()) } returns true

        val job = serviceConnection.serviceConnectionFlow().launchIn(this)
        
        advanceUntilIdle() // Let flow producer run completely

        channelSlot.captured.close()

        job.join()

        verify {
            contextMock.unbindService(any())
        }
    }

    @Test
    fun serviceConnectionFlow_shouldNotUnbindWhenBindingFailed() = runTest {
        every { contextMock.bindService(any(), any(), any<Int>()) } returns false

        val job = serviceConnection.serviceConnectionFlow().catch {}.launchIn(this)
        
        advanceUntilIdle() // Let flow producer run completely and binding to fail

        verify(inverse = true) {
            contextMock.unbindService(any())
        }

        verify {
            contextMock.bindService(any(), any(), any<Int>())
        }

        confirmVerified(contextMock)
        
        job.cancel()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }
}


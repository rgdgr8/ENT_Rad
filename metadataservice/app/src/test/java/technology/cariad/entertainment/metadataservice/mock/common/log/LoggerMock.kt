package technology.cariad.entertainment.metadataservice.mock.common.log

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic

/**
 * LOCAL BUILD: Updated LoggerMock for Android Log based implementation.
 * 
 * Mocks framework logging facility for plain unit tests.
 * Instantiate either during setup or inside of test case.
 * It is not necessary to keep a reference to the object.
 */
class LoggerMock {

    init {
        // Mock Android Log static methods
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.wtf(any(), any<String>()) } returns 0
        every { Log.isLoggable(any(), any()) } returns true
    }
}


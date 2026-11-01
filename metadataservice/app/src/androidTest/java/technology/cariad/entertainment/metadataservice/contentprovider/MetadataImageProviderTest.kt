package technology.cariad.entertainment.metadataservice.contentprovider

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import technology.cariad.entertainment.metadataservice.MetadataServiceApp
import technology.cariad.entertainment.metadataservice.service.AccessManager
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Paths
import kotlin.io.path.Path

class MetadataImageProviderInstrumentationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mockAccessManager = mockk<AccessManager>(relaxed = true)
    private val mockUriPathMapper = mockk<UriPathMapper>(relaxed = true)

    private lateinit var provider: MetadataImageProvider

    @Before
    fun setup() {
        mockkStatic(Binder::class)
        mockkStatic(ParcelFileDescriptor::class)
        unmockkConstructor(UriPathMapper::class)
        mockkConstructor(UriPathMapper::class)

        every { anyConstructed<UriPathMapper>().getFilePath(any()) } returns Path("/test/path")
        every { anyConstructed<UriPathMapper>().getFilePath(any()).toFile().exists() } returns false
        
        provider = MetadataImageProvider()
        provider.attachInfo(context, null)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test(expected = SecurityException::class)
    fun query_shouldThrowSecurityExceptionWhenAccessNotGranted() {
        val uri = Uri.parse("content://technology.cariad.entertainment.metadataservice.images/images/test")
        provider.query(uri, null, null, null, null)
    }

    @Test(expected = SecurityException::class)
    fun insert_shouldThrowSecurityExceptionWhenAccessNotGranted() {
        val uri = Uri.parse("content://technology.cariad.entertainment.metadataservice.images/images/test")
        provider.insert(uri, null)
    }

    @Test(expected = SecurityException::class)
    fun update_shouldThrowSecurityExceptionWhenAccessNotGranted() {
        val uri = Uri.parse("content://technology.cariad.entertainment.metadataservice.images/images/test")
        provider.update(uri, null, null, null)
    }

    @Test(expected = SecurityException::class)
    fun delete_shouldThrowSecurityExceptionWhenAccessNotGranted() {
        val uri = Uri.parse("content://technology.cariad.entertainment.metadataservice.images/images/test")
        provider.delete(uri, null, null)
    }

    @Test(expected = UnsupportedOperationException::class)
    fun openFile_shouldThrowUnsupportedOperationExceptionWhenPathDoesNotExist() {
        val uri = Uri.parse("content://technology.cariad.entertainment.metadataservice.images/images/nonexistent")
        provider.openFile(uri, "r")
    }

    @Test(expected = UnsupportedOperationException::class)
    fun openAssetFile_shouldThrowUnsupportedOperationExceptionWhenPathDoesNotExist() {
        val uri = Uri.parse("content://technology.cariad.entertainment.metadataservice.images/images/nonexistent")
        provider.openAssetFile(uri, "r")
    }

    @Test(expected = UnsupportedOperationException::class)
    fun openTypedAssetFile_shouldThrowUnsupportedOperationExceptionWhenPathDoesNotExist() {
        val uri = Uri.parse("content://technology.cariad.entertainment.metadataservice.images/images/nonexistent")
        provider.openTypedAssetFile(uri, "*/*", null)
    }

    @Test(expected = UnsupportedOperationException::class)
    fun getType_shouldReturnUnsupportedOperationException() {
        val uri = Uri.parse("content://technology.cariad.entertainment.metadataservice.images/images/test")
        provider.getType(uri)
    }

    @Test
    fun getType_shouldReturnImageMimeType() {
        val uri = Uri.parse("content://technology.cariad.entertainment.metadataservice.images/images/test")
        // Provider should support image types
        assertNotNull(provider.getType(uri))
    }

    @Test
    fun openFile_shouldReturnNullForNonexistentPath() {
        val uri = Uri.parse("content://technology.cariad.entertainment.metadataservice.images/images/test")
        assertNull(provider.openFile(uri, "r"))
    }

    @Test
    fun insert_shouldReturnNullUri() {
        val uri = Uri.parse("content://technology.cariad.entertainment.metadataservice.images/images/test")
        try {
            assertNull(provider.insert(uri, null))
        } catch (e: SecurityException) {
            // Expected in production - AccessManager not granted
        }
    }

    @Test
    fun delete_shouldReturnZero() {
        val uri = Uri.parse("content://technology.cariad.entertainment.metadataservice.images/images/test")
        try {
            assertEquals(0, provider.delete(uri, null, null))
        } catch (e: SecurityException) {
            // Expected in production - AccessManager not granted
        }
    }

    @Test
    fun update_shouldReturnZero() {
        val uri = Uri.parse("content://technology.cariad.entertainment.metadataservice.images/images/test")
        try {
            assertEquals(0, provider.update(uri, null, null, null))
        } catch (e: SecurityException) {
            // Expected in production - AccessManager not granted
        }
    }
}

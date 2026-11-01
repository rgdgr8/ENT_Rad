package technology.cariad.entertainment.metadataservice.common.servicelist

import technology.cariad.entertainment.metadataservice.domain.IConfigurationProvider
import technology.cariad.entertainment.metadataservice.domain.value.ServiceIdentifier
import java.net.URI

internal fun applyDebugConfig(
    serviceList: List<ServiceListEntry>,
    configurationProvider: IConfigurationProvider
): List<ServiceListEntry> {
    return serviceList.map { entry ->
        if (entry.serviceId == ServiceIdentifier.CONNECTEDRADIO_V2
            && configurationProvider.getConradBackendUrlOverride().isNotEmpty()
        ) {
            val url = configurationProvider.getConradBackendUrlOverride()
            val httpOperation = entry.defaultHttpOperation?.copy(url = rewriteOperationUrl(url, entry.defaultHttpOperation.url))
            val httpOperations = entry.httpOperations?.mapValues { (_, operation) ->
                operation.copy(url = rewriteOperationUrl(url, operation.url))
            }
            entry.copy(defaultHttpOperation = httpOperation, httpOperations = httpOperations)
        } else {
            entry
        }
    }
}

private fun rewriteOperationUrl(overrideBaseUrl: String, originalUrl: String): String {
    return try {
        val baseUri = URI(overrideBaseUrl)
        val originalUri = URI(originalUrl)
        val normalizedBase = baseUri.toString().trimEnd('/')
        val path = originalUri.rawPath?.trimStart('/').orEmpty()
        val query = originalUri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = originalUri.rawFragment?.let { "#$it" }.orEmpty()

        buildString {
            append(normalizedBase)
            if (path.isNotEmpty()) {
                append('/')
                append(path)
            }
            append(query)
            append(fragment)
        }
    } catch (_: IllegalArgumentException) {
        overrideBaseUrl
    }
}

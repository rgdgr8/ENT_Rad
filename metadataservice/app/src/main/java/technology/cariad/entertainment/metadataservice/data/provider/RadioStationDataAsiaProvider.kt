package technology.cariad.entertainment.metadataservice.data.provider

import technology.cariad.entertainment.metadataservice.common.log.PrivacyFlag
import technology.cariad.entertainment.metadataservice.common.io.generateFilename
import technology.cariad.entertainment.metadataservice.common.log.logger
import technology.cariad.entertainment.metadataservice.common.network.NetworkInfo
import technology.cariad.entertainment.metadataservice.common.position.PositionInformation
import technology.cariad.entertainment.metadataservice.data.provider.radioplayer.IRadioplayerService
import technology.cariad.entertainment.metadataservice.data.provider.radioplayer.IRadioplayerStationCallback
import technology.cariad.entertainment.metadataservice.data.provider.radioplayer.RadioplayerPositionData
import technology.cariad.entertainment.metadataservice.data.provider.radioplayer.RadioplayerStationInfo
import technology.cariad.entertainment.metadataservice.domain.IRadioStationDataAsiaProvider
import technology.cariad.entertainment.metadataservice.domain.entity.BearerAsiaEntity
import technology.cariad.entertainment.metadataservice.domain.entity.BearerAsiaType
import technology.cariad.entertainment.metadataservice.domain.entity.ImageEntity
import technology.cariad.entertainment.metadataservice.domain.entity.ImageEntity.Companion.ImageType
import technology.cariad.entertainment.metadataservice.domain.entity.ProviderIdentifierEntity
import technology.cariad.entertainment.metadataservice.domain.entity.StationAsiaEntity
import technology.cariad.entertainment.metadataservice.domain.value.ServiceIdentifier
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class RadioStationDataAsiaProvider(
    override val serviceId: ServiceIdentifier,
    private val radioplayerService: IRadioplayerService
) : IRadioStationDataAsiaProvider {

    private val log by logger()

    /**
     * @see [IRadioStationDataAsiaProvider.getDataForBearer]
     */
    override suspend fun getDataForBearer(
        bearer: BearerAsiaEntity, position: PositionInformation
    ): StationAsiaEntity = suspendCoroutine { cont ->

        val callback = object : IRadioplayerStationCallback {
            override fun onRadioStationData(
                name: String,
                providerId: String,
                imgData: ByteArray
            ) {
                log.info(this::onRadioStationData.name, PrivacyFlag.GNSS) {
                    "bearer=$bearer position=$position: " +
                            "result name=$name, providerId=$providerId, image size=${imgData.size}"
                }

                val image = if (imgData.isNotEmpty()) {
                    ImageEntity(
                        fileName = generateFilename("jpg"),
                        imageData = imgData.toList(),
                        imageType = ImageType.LOGO
                    )
                } else null

                val providerIdentifier = if (providerId.isNotEmpty()) {
                    ProviderIdentifierEntity(
                        ProviderIdentifierEntity.Companion.OriginType.RADIOPLAYER,
                        providerId
                    )
                } else null

                cont.resume(
                    StationAsiaEntity(name, image, providerIdentifier),
                )
            }
        }

        val band = when (bearer.type) {
            BearerAsiaType.AM -> "am"
            BearerAsiaType.FM -> "fm"
        }

        val lat = "%.6f".format(position.latitude)
        val long = "%.6f".format(position.longitude)

        val mcc = bearer.mcc.toInt()

        log.info("getDataForBearer", PrivacyFlag.GNSS) {
            "bearer=$bearer position=$position"
        }

        radioplayerService.requestRadioStationData(
            RadioplayerPositionData(lat, long),
            RadioplayerStationInfo(
                Band = band,
                Frequency = bearer.frequencyKhz
            ),
            mcc,
            callback
        )
    }

    /**
     * @see [IRadioStationDataAsiaProvider.selectNetwork]
     */
    override fun selectNetwork(networkInfo: NetworkInfo?) {
        log.info(this::selectNetwork.name) {
            "set network to: networkInfo=$networkInfo"
        }

        radioplayerService.updateApnConfigEntry(
            GnServiceRequestNr.RADIOSTATION,
            networkInfo?.interfaceName
        )
    }
}


package technology.cariad.entertainment.metadataservice

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.MainThread
import androidx.preference.PreferenceManager
import androidx.work.WorkInfo
import org.radioplayer.automotiveSDK.ConsentManagementModule.CmpConsent
import org.radioplayer.automotiveSDK.VehicleInfo
import org.radioplayer.automotive.core.AutomotiveSDK
import org.radioplayer.automotive.core.AutomotiveSDKConfig
import org.radioplayer.automotive.core.AutomotiveSDKDefaultConfig
import org.radioplayer.automotive.core.DataPlatformSDKConfig
import org.radioplayer.automotive.core.tasks.TaskListener
import org.radioplayer.automotive.core.tasks.constants.Tasks
import technology.cariad.entertainment.metadataservice.common.assetmanager.AssetManager
import technology.cariad.entertainment.metadataservice.common.codingprovider.CodingManager
import technology.cariad.entertainment.metadataservice.common.codingprovider.data.MediaCodingValues
import technology.cariad.entertainment.metadataservice.external.MagicAdapter
import technology.cariad.entertainment.metadataservice.common.extendedstorage.ExtendedStorageManager
import technology.cariad.entertainment.metadataservice.common.log.IRCLogger
import technology.cariad.entertainment.metadataservice.common.log.IRCLogger.ESM_NOT_AVAILABLE_ERROR
import technology.cariad.entertainment.metadataservice.common.log.Logger
import technology.cariad.entertainment.metadataservice.common.log.logger
import technology.cariad.entertainment.metadataservice.service.AppComponentBuilder
import technology.cariad.entertainment.metadataservice.service.DomainComponentBuilder
import technology.cariad.entertainment.metadataservice.service.config.DemoConfigProvider
import technology.cariad.entertainment.metadataservice.service.config.MODULE_CONFIG
import technology.cariad.entertainment.metadataservice.service.di.AppComponent
import technology.cariad.entertainment.metadataservice.service.di.DomainComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.nio.file.Paths
import java.util.UUID

@MainThread
open class MetadataServiceApp : Application() {

    private val log by logger()

    private lateinit var assetManager: AssetManager

    private val scope = CoroutineScope(Dispatchers.Main)

    companion object {
        @Volatile
        var isInitialized = false
        lateinit var app: MetadataServiceApp
        lateinit var appComponent: AppComponent
        lateinit var domainComponent: Map<String, DomainComponent>
        lateinit var config: DemoConfigProvider
    }

    /**
     * @see Application.attachBaseContext
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        Logger.init(this)

        if (!getProcessName().contains("demo")) {

            log.info(this::attachBaseContext.name) {
                "initialize metadata service in headless profile"
            }

            app = this
            assetManager = AssetManager(this)

            initializeExternalPartition()

        } else {
            log.debug(this::attachBaseContext.name) { "init demo app in user profile" }
        }
    }

    private fun initializeCodingManager(storageRoot: String) {
        val codingManager =  CodingManager(this)
        codingManager.initialize {

            log.info(this::initializeCodingManager.name) {
                "coding available - country code=${MediaCodingValues.countryOfSales?.countryCode}"
            }

            initializeApplication(storageRoot)
        }
    }

    private fun initializeExternalPartition() {
        ExtendedStorageManager(this).initialize { storageRoot ->
            if (storageRoot != null) {
                val gnSrc = "gracenote"
                val gnDest = "${Paths.get(storageRoot, MODULE_CONFIG.gracenoteDatabaseFolder)}"
                val defaultDest = "${Paths.get(storageRoot, MODULE_CONFIG.gracenoteFallbackTruststoreFolder)}"
                assetManager.installAsset("truststore_gracenote.pem", gnSrc, defaultDest)
                assetManager.installAsset("gn_dsp.gcl", gnSrc, gnDest)
                initializeCodingManager(storageRoot)
            } else {
                log.fatal(this::initializeExternalPartition.name) {
                    "extended storage not available"
                }
                IRCLogger.appError(ESM_NOT_AVAILABLE_ERROR)
            }
        }
    }

    private fun initializeApplication(storageRoot: String) {
        //MagicAdapter init must be called before MDS uses SL or DR
        MagicAdapter().init(this)
        initializeRadioplayerSdk(storageRoot)
        appComponent = AppComponentBuilder.build(this, storageRoot)
        config = DemoConfigProvider(PreferenceManager.getDefaultSharedPreferences(this))
        AppComponentBuilder.initialize(appComponent, scope)
        appComponent.getTrustStoreClient().prepareStorageRoot(appComponent.getFileStorageRoot())
        appComponent.getTrustStoreClient().subscribeTrustStore(false)
        domainComponent = DomainComponentBuilder.build(appComponent, config)

        isInitialized = true

        log.info(this::initializeApplication.name) { "initialization successful" }
    }

    private fun initializeRadioplayerSdk(storageRoot: String) {
        runCatching { AutomotiveSDK.services }
            .onSuccess {
                log.info(this::initializeRadioplayerSdk.name) {
                    "Radioplayer SDK already initialized"
                }
            }
            .onFailure {
                runCatching {
                    val sdkConfig = AutomotiveSDKConfig(
                        "metadata-service",
                        "",
                        "",
                        BuildConfig.APPLICATION_ID,
                        BuildConfig.VERSION_NAME,
                        AutomotiveSDKDefaultConfig.BASE_URL,
                        "cariad"
                    )
                    val dataPlatformConfig = DataPlatformSDKConfig(
                        AutomotiveSDKDefaultConfig.BASE_URL,
                        "",
                        VehicleInfo("automotive", "cariad", 2026),
                        CmpConsent.essentialOnly()
                    )
                    val taskListener = object : TaskListener {
                        override fun onWorkerStateChange(
                            id: UUID,
                            task: Tasks,
                            state: WorkInfo.State
                        ) {
                            log.info("RadioplayerTaskState") {
                                "task=$task state=$state id=$id"
                            }
                        }
                    }

                    AutomotiveSDK.init(
                        storageRoot,
                        taskListener,
                        sdkConfig,
                        dataPlatformConfig
                    )
                    log.info(this::initializeRadioplayerSdk.name) {
                        "Radioplayer SDK initialization requested"
                    }
                }.onFailure { initializationError ->
                    log.error(this::initializeRadioplayerSdk.name) {
                        "Radioplayer SDK initialization failed: ${initializationError.message}"
                    }
                }
            }
    }

    /**
     * @see Application.onCreate
     */
    override fun onCreate() {
        super.onCreate()
        logAppVersion()
    }

    private fun logAppVersion() {
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = packageInfo.versionName ?: run {
                log.debug(this::logAppVersion.name) { "Version info unavailable: package versionName is null" }
                return
            }
            val versionParts = versionName.split(".")
            if (versionParts.size < 3) {
                log.debug(this::logAppVersion.name) { "Version info unavailable: unparseable versionName='$versionName'" }
                return
            }
            val versionMajor = versionParts[0].toShort()
            val versionMinor = versionParts[1].toShort()
            val versionPatch = versionParts[2].substringBefore("-").toShort()
            IRCLogger.appVersionInfo(versionMajor, versionMinor, versionPatch)
            log.info(this::logAppVersion.name) {
                "Version info:  $versionName"
            }
        } catch (e: PackageManager.NameNotFoundException) {
            log.debug(this::onCreate.name) {
                "Version info retrieval failed: ${e.message}"
            }
        } catch (e: java.lang.NumberFormatException) {
            log.debug(this::onCreate.name) {
                "Version conversion to number format failed: ${e.message}"
            }
        }
    }
}


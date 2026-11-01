package technology.cariad.entertainment.metadataservice.data.provider.radioplayer

enum class RadioplayerRecognitionStatus(val value: Int) {
    UNKNOWN(0),
    STOPPED(1),
    RUNNING(2),
    ERROR(3);

    companion object {
        fun getByValue(value: Int) = values().firstOrNull { it.value == value } ?: UNKNOWN
    }
}

package com.volkswagenag.storagemanager

import android.content.Context
import android.util.Log

/**
 * LOCAL BUILD STUB: Replaces com.volkswagenag.storagemanager.ExtStorageManager
 *
 * Kept outside active source sets so testbench/dev builds can bind to the real
 * OEM StorageManager library instead of always falling back to internal storage.
 */
class ExtStorageManager(private val context: Context) {
    private val tag = "mds/ESMStub"

    fun getUsageFolder(partition: String, accessMode: Int): Map<Int, String>? {
        Log.d(tag, "getUsageFolder(partition=$partition, accessMode=$accessMode) - STUB: Using internal storage")
        return mapOf(accessMode to context.filesDir.absolutePath)
    }
}
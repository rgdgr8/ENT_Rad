package com.volkswagenag.storagemanager

import android.content.Context
import android.util.Log

/**
 * LOCAL BUILD STUB: Replaces com.volkswagenag.storagemanager.ExtStorageManagerFactory
 *
 * Kept outside active source sets so testbench/dev builds can bind to the real
 * OEM StorageManager library instead of always falling back to internal storage.
 */
object ExtStorageManagerFactory {
    private const val TAG = "mds/ESMFactoryStub"

    fun createExtStorageManager(
        context: Context,
        callback: (ExtStorageManager) -> Unit
    ) {
        Log.i(TAG, "createExtStorageManager() - STUB: Creating stub manager")
        val manager = ExtStorageManager(context)
        callback(manager)
    }
}
package dev.dmigrate.core.version

import java.util.Properties

object VersionInfo {

    private const val VERSION_RESOURCE = "dmigrate-version.properties"
    private const val VERSION_KEY = "version"
    private const val UNKNOWN_VERSION = "unknown"

    val PRODUCT_VERSION: String by lazy { loadVersion() }

    private fun loadVersion(): String {
        val properties = Properties()
        val version = VersionInfo::class.java.classLoader
            .getResourceAsStream(VERSION_RESOURCE)
            ?.use { input ->
                properties.load(input)
                properties.getProperty(VERSION_KEY)?.trim()
            }
            ?.takeUnless { it.isNullOrBlank() || '$' in it || '{' in it || '}' in it }
        return version ?: UNKNOWN_VERSION
    }
}

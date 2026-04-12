package dev.dmigrate.driver.data

/**
 * Geworfen von [SchemaSync.disableTriggers], wenn der Dialekt den
 * Trigger-Disable-Modus nicht sicher unterstützt (MySQL, SQLite).
 */
class UnsupportedTriggerModeException(message: String) :
    RuntimeException(message)

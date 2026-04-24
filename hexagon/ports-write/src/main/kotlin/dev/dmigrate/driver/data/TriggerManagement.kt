package dev.dmigrate.driver.data

import java.sql.Connection

/**
 * Dialect-specific trigger management during import.
 *
 * Only dialects that support disabling/asserting triggers implement
 * this interface. Currently only PostgreSQL provides this capability.
 *
 * Lifecycle:
 * - [disableTriggers] is called from `openTable()` when `triggerMode=disable`
 * - [enableTriggers] is called from `finishTable()` and the `close()` fallback
 * - [assertNoUserTriggers] is called from `openTable()` when `triggerMode=strict`
 */
interface TriggerManagement {

    /**
     * Disables user-side triggers for a table during import.
     *
     * On PostgreSQL this runs `ALTER TABLE ... DISABLE TRIGGER USER`
     * in its own mini-transaction outside the chunk transactions.
     */
    fun disableTriggers(conn: Connection, table: String)

    /**
     * Pre-flight check for `triggerMode=strict`: aborts with a clear
     * exception if user triggers exist on the target table.
     * Does not modify trigger state.
     */
    fun assertNoUserTriggers(conn: Connection, table: String)

    /**
     * Re-enables previously disabled triggers. Must be idempotent.
     *
     * On PostgreSQL this runs `ALTER TABLE ... ENABLE TRIGGER USER`
     * in its own mini-transaction, symmetrical to [disableTriggers].
     */
    fun enableTriggers(conn: Connection, table: String)
}

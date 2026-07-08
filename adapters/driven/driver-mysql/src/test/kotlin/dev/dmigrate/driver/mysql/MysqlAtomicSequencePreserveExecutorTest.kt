package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.migration.preserve.AtomicProtectedExecutionResult
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveBatch
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import java.sql.Connection
import java.sql.SQLException

/**
 * Atomic-Preserve Phase B.3 (2026-05-31): unit-level pins for the
 * MySQL executor's pure-Kotlin branches. The driver-bound paths
 * (lock-and-probe, restore, timeout reset) live in
 * `MysqlAtomicSequencePreserveExecutorIntegrationTest` — they need a
 * real testcontainers MySQL because `SET SESSION
 * innodb_lock_wait_timeout` and `SELECT … FOR UPDATE` semantics are
 * inherently driver-bound.
 *
 * This spec covers the contract surfaces that do NOT touch the
 * connection: empty-batch short-circuit and the `require` precondition
 * on [MysqlAtomicSequencePreserveExecutor.execute]'s
 * `lockTimeoutMillis` argument.
 */
class MysqlAtomicSequencePreserveExecutorTest : FunSpec({

    val executor = MysqlAtomicSequencePreserveExecutor()
    val unusedConnection = object : Connection by NoopConnection() {}

    test("empty batch short-circuits with Applied(emptyList()) — connection is never touched") {
        val batch = AtomicSequencePreserveBatch(
            requests = emptyList(),
            protectedOperationIds = listOf(ProtectedOperationId("AlterSequenceCurrentValue")),
            internalFollowUpIds = emptyList(),
        )
        var callbackInvoked = false
        val result = executor.execute(JdbcDatabaseConnection(unusedConnection), batch, lockTimeoutMillis = 5_000) { _, _ ->
            callbackInvoked = true
            AtomicProtectedExecutionResult.Succeeded(0)
        }
        result.shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
        result.refs shouldBe emptyList()
        callbackInvoked shouldBe false
    }

    test("lockTimeoutMillis must be > 0") {
        val batch = AtomicSequencePreserveBatch(
            requests = emptyList(),
            protectedOperationIds = emptyList(),
            internalFollowUpIds = emptyList(),
        )
        shouldThrow<IllegalArgumentException> {
            executor.execute(JdbcDatabaseConnection(unusedConnection), batch, lockTimeoutMillis = 0L) { _, _ ->
                AtomicProtectedExecutionResult.Succeeded(0)
            }
        }
        shouldThrow<IllegalArgumentException> {
            executor.execute(JdbcDatabaseConnection(unusedConnection), batch, lockTimeoutMillis = -1L) { _, _ ->
                AtomicProtectedExecutionResult.Succeeded(0)
            }
        }
    }

    test("classifyLockSqlException maps ER_LOCK_WAIT_TIMEOUT (1205) → LockTimeout") {
        val ref = SequenceObjectRef("seq", null, RenameProjectionDialect.MYSQL)
        val e = SQLException("Lock wait timeout exceeded", "HY000", 1205)
        val result = MysqlAtomicSequencePreserveExecutor.classifyLockSqlException(ref, e)
        result.shouldBeInstanceOf<AtomicSequencePreserveResult.LockTimeout>()
        result.refs shouldBe listOf(ref)
    }

    test("classifyLockSqlException maps ER_LOCK_DEADLOCK (1213) → LockTimeout") {
        val ref = SequenceObjectRef("seq", null, RenameProjectionDialect.MYSQL)
        val e = SQLException("Deadlock found", "40001", 1213)
        val result = MysqlAtomicSequencePreserveExecutor.classifyLockSqlException(ref, e)
        result.shouldBeInstanceOf<AtomicSequencePreserveResult.LockTimeout>()
        result.refs shouldBe listOf(ref)
    }

    test("classifyLockSqlException maps ER_NO_SUCH_TABLE (1146) → NotFound") {
        val ref = SequenceObjectRef("seq", null, RenameProjectionDialect.MYSQL)
        val e = SQLException("Table 'dmg_sequences' doesn't exist", "42S02", 1146)
        val result = MysqlAtomicSequencePreserveExecutor.classifyLockSqlException(ref, e)
        result.shouldBeInstanceOf<AtomicSequencePreserveResult.NotFound>()
        result.refs shouldBe listOf(ref)
    }

    test("classifyLockSqlException maps unknown error codes → Failed(ref, cause)") {
        val ref = SequenceObjectRef("seq", null, RenameProjectionDialect.MYSQL)
        val e = SQLException("Some other driver error", "HY000", 9999)
        val result = MysqlAtomicSequencePreserveExecutor.classifyLockSqlException(ref, e)
        result.shouldBeInstanceOf<AtomicSequencePreserveResult.Failed>()
        result.ref shouldBe ref
        result.cause shouldBe e
    }
})

/**
 * Minimal [Connection] stub for the empty-batch / require-validation
 * paths — the executor short-circuits before any JDBC call, so a no-op
 * proxy is enough. Every method throws `NotImplementedError` so a future
 * regression that *does* reach into the connection surfaces immediately.
 */
private class NoopConnection : Connection {
    override fun <T : Any?> unwrap(iface: Class<T>?): T = unsupported()
    override fun isWrapperFor(iface: Class<*>?): Boolean = unsupported()
    override fun close(): Unit = unsupported()
    override fun createStatement(): java.sql.Statement = unsupported()
    override fun prepareStatement(sql: String?): java.sql.PreparedStatement = unsupported()
    override fun prepareCall(sql: String?): java.sql.CallableStatement = unsupported()
    override fun nativeSQL(sql: String?): String = unsupported()
    override fun setAutoCommit(autoCommit: Boolean): Unit = unsupported()
    override fun getAutoCommit(): Boolean = unsupported()
    override fun commit(): Unit = unsupported()
    override fun rollback(): Unit = unsupported()
    override fun isClosed(): Boolean = unsupported()
    override fun getMetaData(): java.sql.DatabaseMetaData = unsupported()
    override fun setReadOnly(readOnly: Boolean): Unit = unsupported()
    override fun isReadOnly(): Boolean = unsupported()
    override fun setCatalog(catalog: String?): Unit = unsupported()
    override fun getCatalog(): String = unsupported()
    override fun setTransactionIsolation(level: Int): Unit = unsupported()
    override fun getTransactionIsolation(): Int = unsupported()
    override fun getWarnings(): java.sql.SQLWarning? = unsupported()
    override fun clearWarnings(): Unit = unsupported()
    override fun createStatement(resultSetType: Int, resultSetConcurrency: Int): java.sql.Statement = unsupported()
    override fun prepareStatement(sql: String?, resultSetType: Int, resultSetConcurrency: Int): java.sql.PreparedStatement = unsupported()
    override fun prepareCall(sql: String?, resultSetType: Int, resultSetConcurrency: Int): java.sql.CallableStatement = unsupported()
    override fun getTypeMap(): MutableMap<String, Class<*>> = unsupported()
    override fun setTypeMap(map: MutableMap<String, Class<*>>?): Unit = unsupported()
    override fun setHoldability(holdability: Int): Unit = unsupported()
    override fun getHoldability(): Int = unsupported()
    override fun setSavepoint(): java.sql.Savepoint = unsupported()
    override fun setSavepoint(name: String?): java.sql.Savepoint = unsupported()
    override fun rollback(savepoint: java.sql.Savepoint?): Unit = unsupported()
    override fun releaseSavepoint(savepoint: java.sql.Savepoint?): Unit = unsupported()
    override fun createStatement(
        resultSetType: Int,
        resultSetConcurrency: Int,
        resultSetHoldability: Int,
    ): java.sql.Statement = unsupported()
    override fun prepareStatement(
        sql: String?,
        resultSetType: Int,
        resultSetConcurrency: Int,
        resultSetHoldability: Int,
    ): java.sql.PreparedStatement = unsupported()
    override fun prepareCall(
        sql: String?,
        resultSetType: Int,
        resultSetConcurrency: Int,
        resultSetHoldability: Int,
    ): java.sql.CallableStatement = unsupported()
    override fun prepareStatement(sql: String?, autoGeneratedKeys: Int): java.sql.PreparedStatement = unsupported()
    override fun prepareStatement(sql: String?, columnIndexes: IntArray?): java.sql.PreparedStatement = unsupported()
    override fun prepareStatement(sql: String?, columnNames: Array<out String>?): java.sql.PreparedStatement = unsupported()
    override fun createClob(): java.sql.Clob = unsupported()
    override fun createBlob(): java.sql.Blob = unsupported()
    override fun createNClob(): java.sql.NClob = unsupported()
    override fun createSQLXML(): java.sql.SQLXML = unsupported()
    override fun isValid(timeout: Int): Boolean = unsupported()
    override fun setClientInfo(name: String?, value: String?): Unit = unsupported()
    override fun setClientInfo(properties: java.util.Properties?): Unit = unsupported()
    override fun getClientInfo(name: String?): String = unsupported()
    override fun getClientInfo(): java.util.Properties = unsupported()
    override fun createArrayOf(typeName: String?, elements: Array<out Any>?): java.sql.Array = unsupported()
    override fun createStruct(typeName: String?, attributes: Array<out Any>?): java.sql.Struct = unsupported()
    override fun setSchema(schema: String?): Unit = unsupported()
    override fun getSchema(): String = unsupported()
    override fun abort(executor: java.util.concurrent.Executor?): Unit = unsupported()
    override fun setNetworkTimeout(executor: java.util.concurrent.Executor?, milliseconds: Int): Unit = unsupported()
    override fun getNetworkTimeout(): Int = unsupported()
    private fun unsupported(): Nothing = error("NoopConnection: executor reached into the connection — empty-batch short-circuit broken")
}

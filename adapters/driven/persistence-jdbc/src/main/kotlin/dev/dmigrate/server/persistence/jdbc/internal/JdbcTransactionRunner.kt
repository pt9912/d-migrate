package dev.dmigrate.server.persistence.jdbc.internal

import java.sql.Connection
import javax.sql.DataSource

/**
 * Adapter-internes Transaktions-Primitive fuer den persistence-jdbc-Adapter.
 *
 * Borgt eine Connection aus der DataSource, schaltet `autoCommit = false`,
 * fuehrt den Block aus, committet bei Erfolg, rollbackt bei Throwable.
 * Die Connection wird ueber `use {}` zwingend geschlossen (im
 * Pool-Pfad: an HikariCP zurueckgegeben).
 *
 * Nicht als Hexagon-Port exponiert — Cross-Port-Atomicity laeuft ueber
 * `JobStartTransaction` und die contract-test-gesicherten Adapter-Methoden,
 * die intern `inTransaction` nutzen. Plan-Ref: ImpPlan-0.9.6-E2.md § 3.5.
 */
internal class JdbcTransactionRunner(
    private val dataSource: DataSource,
) {
    fun <T> inTransaction(block: (Connection) -> T): T {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                return result
            } catch (primary: Throwable) {
                try {
                    conn.rollback()
                } catch (rollback: Throwable) {
                    primary.addSuppressed(rollback)
                }
                throw primary
            }
        }
    }
}

package dev.dmigrate.server.persistence.jdbc.internal

import java.sql.Connection
import javax.sql.DataSource

/**
 * Adapter-Transaktions-Primitive fuer den persistence-jdbc-Adapter.
 *
 * Borgt eine Connection aus der DataSource, schaltet `autoCommit = false`,
 * fuehrt den Block aus, committet bei Erfolg, rollbackt bei Throwable.
 * Die Connection wird ueber `use {}` zwingend geschlossen (im
 * Pool-Pfad: an HikariCP zurueckgegeben).
 *
 * Nicht als Hexagon-Port exponiert — Cross-Port-Atomicity laeuft ueber
 * `JobStartTransaction` und die contract-test-gesicherten Adapter-Methoden,
 * die intern `inTransaction` nutzen. Plan-Ref: ImpPlan-0.9.6-E2.md § 3.5.
 *
 * Sichtbarkeit: `public class` innerhalb des Adapter-Moduls
 * (Carve-out gegenueber Plan-§-3.5-Wortlaut „internal/package-private").
 * Begruendung: AP E2.6 (`JdbcJobStartTransaction`) komponiert den
 * Runner mit `JdbcIdempotencyStore` und `JdbcJobStore` ueber Modul-
 * grenzen (Bootstrap in `adapters:driving:mcp`); reine Modul-internal-
 * Sichtbarkeit wuerde den Aufbau eines `JobExecutorBundle`-Aequivalents
 * fuer Persistenz blockieren. „Nicht als Port exponiert" bleibt
 * gewahrt — der Runner taucht in keinem `hexagon:ports-common`-
 * Interface auf.
 */
class JdbcTransactionRunner(
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

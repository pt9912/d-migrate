package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.RawTextSandboxPort
import dev.dmigrate.driver.RawTextSandboxResult
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.asJdbc
import java.sql.Connection

/**
 * Der Wegwerf-Sandkasten fuer PostgreSQL.
 *
 * Legt ein eigenes Schema an, wendet das Soll dort an, liest zurueck, was der
 * Server daraus gemacht hat — und **rollt alles zurueck**. Das Ziel wird dabei
 * nicht beruehrt.
 *
 * **Zurueckrollen statt aufraeumen**, und das ist der Grund, warum
 * ausgerechnet PostgreSQL das kann: dort ist DDL transaktional. Ein
 * abgebrochener Lauf hinterlaesst nichts, es gibt keine Aufraeumpflicht und
 * kein Zeitfenster, in dem ein fremdes Schema herumsteht. Bei Oracle waere
 * beides der Fall — dort committet DDL implizit, und ein Schema IST ein
 * Benutzer, den ein Migrationsnutzer ohnehin nicht anlegen darf.
 *
 * Der Schemaname traegt ein erkennbares Praefix. Er sollte nie zu sehen sein
 * (die Transaktion faellt zurueck), aber wenn doch, ist erkennbar, woher er
 * kommt.
 */
class PostgresRawTextSandbox(
    private val schemaReader: PostgresSchemaReader = PostgresSchemaReader(),
    private val generator: PostgresDdlGenerator = PostgresDdlGenerator(),
) : RawTextSandboxPort {

    override fun deparse(
        desired: SchemaDefinition,
        pool: ConnectionPool,
        options: SchemaReadOptions,
    ): RawTextSandboxResult {
        val name = "$SCHEMA_PREFIX${System.nanoTime()}"
        return pool.borrow().asJdbc().use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                applyInSandbox(conn, name, desired)
                RawTextSandboxResult.Deparsed(schemaReader.read(conn, options).schema)
            } catch (e: Exception) {
                // Ein Sandkasten, der raet, waere schlimmer als keiner: was
                // sich dort nicht anwenden laesst, beantwortet die Frage nicht.
                // Der Grund wird genannt, nicht verschluckt.
                RawTextSandboxResult.Unavailable(
                    "the desired schema could not be applied in a sandbox schema: " +
                        (e.message?.lines()?.firstOrNull() ?: e::class.simpleName.orEmpty()),
                )
            } finally {
                // Auch im Erfolgsfall: der Sandkasten soll nichts hinterlassen.
                runCatching { conn.rollback() }
                runCatching { conn.autoCommit = previousAutoCommit }
            }
        }
    }

    private fun applyInSandbox(conn: Connection, schemaName: String, desired: SchemaDefinition) {
        conn.createStatement().use { stmt ->
            stmt.execute("""CREATE SCHEMA "$schemaName"""")
            // Der erzeugte DDL-Text nennt keine Schemas; der Suchpfad legt
            // fest, wo er landet.
            stmt.execute("""SET LOCAL search_path TO "$schemaName"""")
            for (statement in generator.generate(desired, DdlGenerationOptions()).statements) {
                val sql = statement.sql.trim()
                if (sql.isEmpty() || sql.startsWith("--")) continue
                stmt.execute(sql)
            }
        }
    }

    private companion object {
        const val SCHEMA_PREFIX = "dmg_sandbox_"
    }
}

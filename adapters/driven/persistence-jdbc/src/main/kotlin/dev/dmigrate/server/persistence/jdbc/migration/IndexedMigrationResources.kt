package dev.dmigrate.server.persistence.jdbc.migration

import org.flywaydb.core.api.ClassProvider
import org.flywaydb.core.api.Location
import org.flywaydb.core.api.ResourceProvider
import org.flywaydb.core.api.migration.JavaMigration
import org.flywaydb.core.api.resource.LoadableResource
import org.flywaydb.core.internal.resource.classpath.ClassPathResource
import java.nio.charset.StandardCharsets

/**
 * Flyway-Ressourcen aus einem Index statt aus dem Classpath-Scan.
 *
 * Flyways Scanner kennt das Protokoll `resource:` nicht, unter dem ein GraalVM-Native-Image seine
 * Ressourcen ausliefert (`Unable to scan location: /db/migration (unsupported protocol: resource)`).
 * Er findet dort keine Migration; Flyway legt nur die History-Tabelle an, und der Server startet gegen
 * eine leere Datenbank (Issue #84). Dieser Provider liest die Namen aus `db/migration/migrations.index`
 * (beim Bau aus den `.sql`-Dateien erzeugt, siehe `build.gradle.kts`) und braucht weder Scan noch
 * Verzeichnislisting. Auf der JVM ergibt das dieselben Ressourcen wie der Scanner.
 */
internal class IndexedMigrationResources(
    /** Ort ohne Praefix, etwa `db/migration`. */
    private val locationPath: String,
    private val classLoader: ClassLoader,
) : ResourceProvider {

    private val location: Location = Location.fromPath("classpath:", locationPath)

    private val resources: List<LoadableResource> by lazy {
        indexNames().map { name ->
            ClassPathResource(location, "$locationPath/$name", classLoader, StandardCharsets.UTF_8)
        }
    }

    override fun getResource(name: String): LoadableResource? =
        resources.firstOrNull { it.filename == name || it.relativePath == name }

    override fun getResources(prefix: String, suffixes: Array<String>): Collection<LoadableResource> =
        resources.filter { resource ->
            resource.filename.startsWith(prefix) && suffixes.any { resource.filename.endsWith(it) }
        }

    private fun indexNames(): List<String> {
        val indexPath = "$locationPath/$INDEX_NAME"
        val stream = classLoader.getResourceAsStream(indexPath)
            ?: error("Migrationsindex '$indexPath' fehlt im Classpath (Bau: generateMigrationIndex)")
        return stream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            reader.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        }
    }

    companion object {
        const val INDEX_NAME: String = "migrations.index"
    }
}

/** Keine Java-Migrationen: Flyway sucht sonst per Classpath-Scan danach und scheitert im Native-Image dort. */
internal object NoJavaMigrations : ClassProvider<JavaMigration> {
    override fun getClasses(): Collection<Class<out JavaMigration>> = emptyList()
}

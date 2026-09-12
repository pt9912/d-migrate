package dev.dmigrate.test.images

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.MeasuredServerVersions
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.utility.DockerImageName

/**
 * Der Faehigkeits-Pin und die Bilder der Integrationssuite muessen dieselbe
 * Version nennen.
 *
 * `DialectCapabilities.forTarget(dialect, null)` antwortet fuer die
 * **aktuellste gemessene** Version — das ist die Eignerentscheidung fuer den
 * Fall „Zielversion unbekannt" (ein Dateiziel hat keine). „Gemessen" heisst
 * genau: die Version, gegen die diese Suite laeuft. Duerften beide unabhaengig
 * wandern, stuende der Default irgendwann fuer eine Version, gegen die nichts
 * mehr geprueft wird — und der Pin waere wieder eine Behauptung.
 *
 * Wer ein Bild anhebt, hebt deshalb hier mit an. Diese Spec ist die Stelle, an
 * der das auffaellt.
 *
 * **Kein Bildname steht hier als Text.** Verglichen wird gegen den Tag, den
 * [TestImages] fuehrt — sonst waere dies die zweite Stelle mit Bildnamen, und
 * `make test-images-gate` faende sie zu Recht.
 */
class MeasuredServerVersionsPinTest : FunSpec({

    /** `repo/name:TAG@sha256:…` → `TAG`. */
    fun tagOf(image: DockerImageName): String =
        image.asCanonicalNameString().substringAfter(':').substringBefore('@')

    /** Die fuehrende Zahl eines Tags: `18-alpine` → `18`, `23-slim-faststart` → `23`. */
    fun majorOf(image: DockerImageName): String = tagOf(image).takeWhile { it.isDigit() }

    test("the PostgreSQL pin matches the image the suite runs against") {
        withClue(tagOf(TestImages.POSTGRESQL)) {
            majorOf(TestImages.POSTGRESQL) shouldBe MeasuredServerVersions.POSTGRESQL.major.toString()
        }
    }

    test("the MySQL pin matches the image the suite runs against") {
        val pin = MeasuredServerVersions.MYSQL

        withClue(tagOf(TestImages.MYSQL)) {
            tagOf(TestImages.MYSQL) shouldBe "${pin.major}.${pin.minor}.${pin.patch}"
        }
    }

    test("the Oracle pin matches the image the suite runs against") {
        withClue(tagOf(TestImages.ORACLE)) {
            majorOf(TestImages.ORACLE) shouldBe MeasuredServerVersions.ORACLE.major.toString()
        }
    }

    test("SQL Server and SQLite have no pin, because they have no version type yet") {
        MeasuredServerVersions.of(DatabaseDialect.MSSQL) shouldBe null
        MeasuredServerVersions.of(DatabaseDialect.SQLITE) shouldBe null
    }
})

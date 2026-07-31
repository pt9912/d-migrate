package dev.dmigrate.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException

/**
 * `cli-spec.md` ordnet „Ausgabepfad nicht beschreibbar" dem Exit-Code 7
 * (`LOCAL_ERROR`) zu. Bis 1.0.0-RC2 verliess eine solche [IOException] den
 * Prozess als roher Stacktrace — unentdeckt, weil das publizierte Image als
 * root lief und deshalb nie „permission denied" bekam (ADR 0041).
 */
class LocalIoErrorMessageTest : FunSpec({

    test("AccessDenied nennt den Pfad und den --user-Ausweg") {
        val msg = localIoErrorMessage(AccessDeniedException("/work/out.sql"))

        msg shouldContain "/work/out.sql"
        msg shouldContain "--user"
        msg shouldContain "uid 10001"
    }

    test("AccessDenied-Meldung traegt keinen Stacktrace-Rest") {
        val msg = localIoErrorMessage(AccessDeniedException("/work/out.sql"))

        msg shouldNotContain "java.nio"
        msg shouldNotContain "at "
    }

    test("NoSuchFile fragt nach dem Zielverzeichnis statt nach Rechten") {
        val msg = localIoErrorMessage(NoSuchFileException("/work/fehlt/out.sql"))

        msg shouldContain "/work/fehlt/out.sql"
        msg shouldContain "Zielverzeichnis"
        msg shouldNotContain "--user"
    }

    test("generische IOException faellt auf ihre Meldung zurueck") {
        localIoErrorMessage(IOException("Datentraeger voll")) shouldBe "I/O-Fehler: Datentraeger voll"
    }

    test("IOException ohne Meldung nennt wenigstens den Typ") {
        localIoErrorMessage(IOException()) shouldBe "I/O-Fehler: IOException"
    }
})

package dev.dmigrate.format.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.charset.StandardCharsets

class ImportOptionsTest : FunSpec({

    test("defaults are spelled out per §3.5.2 / §3.7.1") {
        val opts = ImportOptions()
        opts.triggerMode shouldBe TriggerMode.FIRE
        opts.csvNoHeader shouldBe false
        opts.csvNullString shouldBe ""
        opts.encoding shouldBe StandardCharsets.UTF_8
        opts.reseedSequences shouldBe true
        opts.disableFkChecks shouldBe false
        opts.truncate shouldBe false
        opts.onConflict shouldBe OnConflict.ABORT
        opts.onError shouldBe OnError.ABORT
    }

    test("encoding can be null for --encoding auto path") {
        val opts = ImportOptions(encoding = null)
        opts.encoding shouldBe null
    }

    test("copy is available via data class") {
        val base = ImportOptions()
        val upsert = base.copy(onConflict = OnConflict.UPDATE)
        upsert.onConflict shouldBe OnConflict.UPDATE
        // base stays untouched
        base.onConflict shouldBe OnConflict.ABORT
    }

    test("all trigger modes are constructable") {
        ImportOptions(triggerMode = TriggerMode.FIRE).triggerMode shouldBe TriggerMode.FIRE
        ImportOptions(triggerMode = TriggerMode.DISABLE).triggerMode shouldBe TriggerMode.DISABLE
        ImportOptions(triggerMode = TriggerMode.STRICT).triggerMode shouldBe TriggerMode.STRICT
    }

    test("all on-conflict modes are constructable") {
        OnConflict.values().size shouldBe 3
        ImportOptions(onConflict = OnConflict.SKIP).onConflict shouldBe OnConflict.SKIP
        ImportOptions(onConflict = OnConflict.UPDATE).onConflict shouldBe OnConflict.UPDATE
    }

    test("all on-error modes are constructable") {
        OnError.values().size shouldBe 3
        ImportOptions(onError = OnError.LOG).onError shouldBe OnError.LOG
        ImportOptions(onError = OnError.SKIP).onError shouldBe OnError.SKIP
    }

    test("csvNullString defaults to empty string (symmetric to 0.3.0 export)") {
        // Symmetry with ExportOptions.csvNullString — the empty-string
        // default intentionally loses the "" vs NULL distinction unless
        // the user sets a sentinel explicitly.
        ImportOptions().csvNullString shouldBe ""
        ImportOptions().csvNullString shouldBe ExportOptions().csvNullString
    }
})

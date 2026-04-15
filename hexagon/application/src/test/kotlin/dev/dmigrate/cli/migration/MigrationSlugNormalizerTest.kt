package dev.dmigrate.cli.migration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MigrationSlugNormalizerTest : FunSpec({

    test("simple name") {
        MigrationSlugNormalizer.normalize("MyApp") shouldBe "myapp"
    }

    test("name with spaces") {
        MigrationSlugNormalizer.normalize("My Cool App") shouldBe "my_cool_app"
    }

    test("name with special chars") {
        MigrationSlugNormalizer.normalize("my-app_v2.0") shouldBe "my_app_v2_0"
    }

    test("name with consecutive special chars") {
        MigrationSlugNormalizer.normalize("my---app") shouldBe "my_app"
    }

    test("leading/trailing special chars stripped") {
        MigrationSlugNormalizer.normalize("--my-app--") shouldBe "my_app"
    }

    test("empty name falls back to migration") {
        MigrationSlugNormalizer.normalize("") shouldBe "migration"
    }

    test("only special chars falls back to migration") {
        MigrationSlugNormalizer.normalize("---") shouldBe "migration"
    }

    test("unicode chars normalized") {
        MigrationSlugNormalizer.normalize("Über-Schema") shouldBe "ber_schema"
    }

    test("already clean name unchanged") {
        MigrationSlugNormalizer.normalize("shop") shouldBe "shop"
    }
})

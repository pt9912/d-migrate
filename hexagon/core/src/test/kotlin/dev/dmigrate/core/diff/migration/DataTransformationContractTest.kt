package dev.dmigrate.core.diff.migration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DataTransformationContractTest : FunSpec({

    test("F.1 risks default to no automatic data transformation") {
        OperationRisk.SAFE.dataTransformation shouldBe DataTransformationContract.NONE
        OperationRisk(dataLossPossible = true).dataTransformation shouldBe DataTransformationContract.NONE
    }

    test("F.1 automatic data transformations require an explicit model contract") {
        val automatic = DataTransformationContract.automatic(
            modelVersion = "data-transformation.v1",
            modelId = "backfill-users-email",
            description = "Backfill users.email from a reviewed source column",
        )

        automatic.mode shouldBe DataTransformationMode.AUTOMATIC
        automatic.modelVersion shouldBe "data-transformation.v1"
        automatic.modelId shouldBe "backfill-users-email"
    }

    test("F.1 transformations without a model remain manual") {
        val manual = DataTransformationContract.manualRequired("Backfill requires an operator-supplied model")

        manual.mode shouldBe DataTransformationMode.MANUAL_REQUIRED
        manual.modelVersion shouldBe null
        manual.modelId shouldBe null
    }

    test("F.1 automatic transformations reject missing model identity") {
        shouldThrow<IllegalArgumentException> {
            DataTransformationContract.automatic(
                modelVersion = "",
                modelId = "backfill-users-email",
            )
        }
        shouldThrow<IllegalArgumentException> {
            DataTransformationContract.automatic(
                modelVersion = "data-transformation.v1",
                modelId = "",
            )
        }
    }
})

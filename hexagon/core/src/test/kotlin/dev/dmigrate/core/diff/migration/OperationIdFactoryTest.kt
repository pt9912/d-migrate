package dev.dmigrate.core.diff.migration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith

class OperationIdFactoryTest : FunSpec({

    val tableRef = DiffObjectRef(DiffObjectType.TABLE, listOf("orders"))
    val columnRef = DiffObjectRef(DiffObjectType.COLUMN, listOf("orders", "status"))

    context("makeId") {
        test("is deterministic — same inputs yield same ID") {
            val a = OperationIdFactory.makeId("AddColumn", columnRef, "varchar(64)")
            val b = OperationIdFactory.makeId("AddColumn", columnRef, "varchar(64)")
            a shouldBe b
        }

        test("operationKind is the leading segment") {
            OperationIdFactory.makeId("CreateTable", tableRef, "")
                .shouldStartWith("CreateTable:TABLE:")
        }

        test("differs by operationKind, even on the same path + payload") {
            val add = OperationIdFactory.makeId("AddColumn", columnRef, "p")
            val drop = OperationIdFactory.makeId("DropColumn", columnRef, "p")
            add shouldNotBe drop
        }

        test("differs by objectRef path") {
            val a = OperationIdFactory.makeId("AddColumn", columnRef, "p")
            val b = OperationIdFactory.makeId(
                "AddColumn",
                DiffObjectRef(DiffObjectType.COLUMN, listOf("orders", "name")),
                "p",
            )
            a shouldNotBe b
        }

        test("differs by objectType, even with the same path[0]") {
            val a = OperationIdFactory.makeId(
                "DropTable", DiffObjectRef(DiffObjectType.TABLE, listOf("x")), "p",
            )
            val b = OperationIdFactory.makeId(
                "DropTable", DiffObjectRef(DiffObjectType.VIEW, listOf("x")), "p",
            )
            a shouldNotBe b
        }

        test("differs by payloadCanonical") {
            val a = OperationIdFactory.makeId("AddColumn", columnRef, "varchar(64)")
            val b = OperationIdFactory.makeId("AddColumn", columnRef, "varchar(128)")
            a shouldNotBe b
        }

        test("path component order matters") {
            val a = OperationIdFactory.makeId(
                "AddConstraint",
                DiffObjectRef(DiffObjectType.CONSTRAINT, listOf("a", "fk")),
                "",
            )
            val b = OperationIdFactory.makeId(
                "AddConstraint",
                DiffObjectRef(DiffObjectType.CONSTRAINT, listOf("a", "kf")),
                "",
            )
            a shouldNotBe b
        }
    }

    context("disambiguate") {
        test("leaves singletons unchanged") {
            val out = OperationIdFactory.disambiguate(
                listOf("a:T:1:1" to 0, "b:T:2:2" to 1),
            )
            out shouldBe listOf("a:T:1:1", "b:T:2:2")
        }

        test("appends #2/#3 to colliding IDs in position order") {
            val out = OperationIdFactory.disambiguate(
                listOf(
                    "x:T:0:0" to 0,
                    "x:T:0:0" to 1,
                    "x:T:0:0" to 2,
                ),
            )
            out shouldBe listOf("x:T:0:0", "x:T:0:0#2", "x:T:0:0#3")
        }

        test("preserves input order in the output even if positions are out of order") {
            val out = OperationIdFactory.disambiguate(
                listOf(
                    "x:T:0:0" to 5,
                    "y:T:1:1" to 1,
                    "x:T:0:0" to 2,
                ),
            )
            // Position 1 comes first chronologically (y), then position 2 (first x),
            // then position 5 (second x → #2).
            out shouldBe listOf("x:T:0:0#2", "y:T:1:1", "x:T:0:0")
        }
    }
})

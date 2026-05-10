package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintReferenceDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.ReferentialAction
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.DependencyInfo
import dev.dmigrate.core.model.ViewDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Pinning tests for [CanonicalPayload] — the per-entity canonical
 * string projection used by [OperationIdFactory] to derive stable
 * Operation-IDs. The format is a hard contract: any change here
 * invalidates the IDs in shipped rollback artefacts, so each branch
 * is covered explicitly.
 *
 * Tests are organised by surface (`table`, `column`, `constraint`,
 * `index`, `customType`, `view`) plus the private helpers reachable
 * via those surfaces (every `NeutralType` variant, every
 * `DefaultValue` subtype, every `ColumnGeneration` variant,
 * `Reference` with both `onDelete` and `onUpdate` populated, etc.).
 */
class CanonicalPayloadTest : FunSpec({

    // ── column / type / default / generation / reference ──

    test("column projects type, required, unique, default, ref, gen with default-empty markers") {
        val col = ColumnDefinition(NeutralType.Integer)
        CanonicalPayload.column(col) shouldContain "type=integer"
        CanonicalPayload.column(col) shouldContain "required=false"
        CanonicalPayload.column(col) shouldContain "unique=false"
        CanonicalPayload.column(col) shouldContain "default="
        CanonicalPayload.column(col) shouldContain "ref="
        CanonicalPayload.column(col) shouldContain "gen="
    }

    test("neutralType covers every variant via column projection") {
        val cases: Map<NeutralType, String> = mapOf(
            NeutralType.Identifier() to "identifier",
            NeutralType.Identifier(autoIncrement = true) to "identifier(auto)",
            NeutralType.Text() to "text",
            NeutralType.Text(maxLength = 100) to "text(100)",
            NeutralType.Char(8) to "char(8)",
            NeutralType.Float(FloatPrecision.SINGLE) to "float(single)",
            NeutralType.Float(FloatPrecision.DOUBLE) to "float(double)",
            NeutralType.Decimal(10, 2) to "decimal(10,2)",
            NeutralType.DateTime() to "datetime",
            NeutralType.DateTime(timezone = true) to "datetime(tz)",
            NeutralType.Enum() to "enum",
            NeutralType.Enum(values = listOf("a", "b")) to "enum(a,b)",
            NeutralType.Enum(refType = "color_t") to "enum(ref:color_t)",
            NeutralType.Array("integer") to "array(integer)",
            NeutralType.Geometry(GeometryType.of("point")) to "geometry(point)",
            NeutralType.Geometry(GeometryType.of("polygon"), srid = 4326) to "geometry(polygon,4326)",
            NeutralType.Integer to "integer",
            NeutralType.SmallInt to "smallint",
            NeutralType.BigInteger to "biginteger",
            NeutralType.BooleanType to "boolean",
            NeutralType.Date to "date",
            NeutralType.Time to "time",
            NeutralType.Uuid to "uuid",
            NeutralType.Json to "json",
            NeutralType.Xml to "xml",
            NeutralType.Binary to "binary",
            NeutralType.Email to "email",
        )
        for ((type, expected) in cases) {
            CanonicalPayload.column(ColumnDefinition(type)) shouldContain "type=$expected"
        }
    }

    test("defaultValue projects every DefaultValue subtype with its prefix") {
        val cases = mapOf<DefaultValue?, String>(
            null to "default=",
            DefaultValue.StringLiteral("x") to "default=str:x",
            DefaultValue.NumberLiteral(0) to "default=num:0",
            DefaultValue.BooleanLiteral(true) to "default=bool:true",
            DefaultValue.FunctionCall("now") to "default=fn:now",
            DefaultValue.SequenceNextVal("seq_x") to "default=seq:seq_x",
        )
        for ((dv, expected) in cases) {
            CanonicalPayload.column(ColumnDefinition(NeutralType.Integer, default = dv)) shouldContain expected
        }
    }

    test("reference projects table/column and only the populated optional fields") {
        val onDeleteOnly = ReferenceDefinition(
            table = "users", column = "id",
            onDelete = ReferentialAction.CASCADE,
        )
        CanonicalPayload.column(ColumnDefinition(NeutralType.Integer, references = onDeleteOnly)) shouldContain
            "ref=table=users,column=id,onDelete=CASCADE"
        val onUpdateOnly = ReferenceDefinition(
            table = "users", column = "id",
            onUpdate = ReferentialAction.RESTRICT,
        )
        CanonicalPayload.column(ColumnDefinition(NeutralType.Integer, references = onUpdateOnly)) shouldContain
            "ref=table=users,column=id,onUpdate=RESTRICT"
        // no actions
        val plainRef = ReferenceDefinition(table = "users", column = "id")
        CanonicalPayload.column(ColumnDefinition(NeutralType.Integer, references = plainRef)) shouldContain
            "ref=table=users,column=id"
    }

    test("generation projects ColumnGeneration.Identity in all permutations") {
        val identityAlways = ColumnGeneration.Identity(mode = IdentityMode.ALWAYS)
        CanonicalPayload.column(
            ColumnDefinition(NeutralType.Identifier(), generation = identityAlways),
        ) shouldContain "gen=identity:mode=ALWAYS"

        val identityFull = ColumnGeneration.Identity(
            mode = IdentityMode.BY_DEFAULT,
            sequenceName = "id_seq",
            legacySerialSyntax = true,
        )
        val out = CanonicalPayload.column(
            ColumnDefinition(NeutralType.Identifier(), generation = identityFull),
        )
        out shouldContain "gen=identity:mode=BY_DEFAULT"
        out shouldContain "sequence=id_seq"
        out shouldContain "legacy_serial=true"

        // null generation → "gen="
        CanonicalPayload.column(ColumnDefinition(NeutralType.Integer)) shouldContain "gen="
    }

    // ── constraint ──

    test("constraint projects all four nullable fields with empty-string markers when absent") {
        // No PRIMARY_KEY in ConstraintType — modelled separately via TableDefinition.primaryKey.
        val unique = ConstraintDefinition(name = "uq_email", type = ConstraintType.UNIQUE)
        val out = CanonicalPayload.constraint(unique)
        out shouldContain "constraint=uq_email"
        out shouldContain "type=UNIQUE"
        out shouldContain "columns="
        out shouldContain "expr="
        out shouldContain "ref="
    }

    test("constraint projects FK with referenced table[columns] form") {
        val fk = ConstraintDefinition(
            name = "fk_orders_users",
            type = ConstraintType.FOREIGN_KEY,
            columns = listOf("user_id"),
            references = ConstraintReferenceDefinition(table = "users", columns = listOf("id")),
        )
        CanonicalPayload.constraint(fk) shouldContain "ref=users[id]"
        CanonicalPayload.constraint(fk) shouldContain "columns=user_id"
    }

    test("constraint projects CHECK with expression") {
        val chk = ConstraintDefinition(
            name = "chk_age",
            type = ConstraintType.CHECK,
            expression = "age >= 0",
        )
        CanonicalPayload.constraint(chk) shouldContain "expr=age >= 0"
    }

    // ── index ──

    test("index projects name, columns, type, unique, where") {
        val idx = IndexDefinition(
            name = "ix_email",
            columns = listOf(IndexColumn("email")),
            type = IndexType.BTREE,
            unique = true,
            where = "email IS NOT NULL",
        )
        val out = CanonicalPayload.index(idx)
        out shouldContain "index=ix_email"
        out shouldContain "columns=email"
        out shouldContain "type=BTREE"
        out shouldContain "unique=true"
        out shouldContain "where=email IS NOT NULL"
    }

    test("index handles unnamed index and missing where as empty strings") {
        val idx = IndexDefinition(
            name = null,
            columns = listOf(IndexColumn("a"), IndexColumn("b")),
            type = IndexType.HASH,
            unique = false,
            where = null,
        )
        val out = CanonicalPayload.index(idx)
        out shouldContain "index="
        out shouldContain "columns=a,b"
        out shouldContain "type=HASH"
        out shouldContain "where="
    }

    // ── table (composes column + constraint + index in deterministic order) ──

    test("table emits columns in lexicographic key order regardless of insertion order") {
        val cols = linkedMapOf(
            "z" to ColumnDefinition(NeutralType.Integer),
            "a" to ColumnDefinition(NeutralType.Integer),
            "m" to ColumnDefinition(NeutralType.Integer),
        )
        val t = TableDefinition(columns = cols, primaryKey = listOf("a"))
        val out = CanonicalPayload.table(t)
        // a < m < z. Each column appears as `<SEP><name>=type=...`; checking
        // the index of the per-name "a=type=", "m=type=", "z=type=" tokens
        // is sufficient to pin the projection's sort order.
        val ai = out.indexOf("a=type=")
        val mi = out.indexOf("m=type=")
        val zi = out.indexOf("z=type=")
        (ai in 0 until mi && mi < zi) shouldBe true
    }

    test("table includes primary-key list, constraints count, and indices count") {
        val t = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer)),
            primaryKey = listOf("id"),
            constraints = listOf(
                ConstraintDefinition(name = "chk_x", type = ConstraintType.CHECK, expression = "1=1"),
            ),
            indices = listOf(
                IndexDefinition(name = "ix_id", columns = listOf(IndexColumn("id")), type = IndexType.BTREE),
            ),
        )
        val out = CanonicalPayload.table(t)
        out shouldContain "pk=id"
        out shouldContain "constraints[1]"
        out shouldContain "indices[1]"
    }

    // ── customType ──

    test("customType projects ENUM with values list") {
        val t = CustomTypeDefinition(
            kind = CustomTypeKind.ENUM,
            values = listOf("red", "green", "blue"),
        )
        val out = CanonicalPayload.customType(t)
        out shouldContain "kind=ENUM"
        out shouldContain "values=red,green,blue"
        // No fields → fields[0] marker.
        out shouldContain "fields[0]"
    }

    test("customType projects DOMAIN with base/precision/scale/check") {
        val t = CustomTypeDefinition(
            kind = CustomTypeKind.DOMAIN,
            baseType = "decimal",
            precision = 12,
            scale = 2,
            check = "VALUE > 0",
        )
        val out = CanonicalPayload.customType(t)
        out shouldContain "kind=DOMAIN"
        out shouldContain "base=decimal"
        out shouldContain "precision=12"
        out shouldContain "scale=2"
        out shouldContain "check=VALUE > 0"
    }

    test("customType projects COMPOSITE with named fields in lexicographic order") {
        val t = CustomTypeDefinition(
            kind = CustomTypeKind.COMPOSITE,
            fields = linkedMapOf(
                "z" to ColumnDefinition(NeutralType.Integer),
                "a" to ColumnDefinition(NeutralType.Text()),
            ),
        )
        val out = CanonicalPayload.customType(t)
        out shouldContain "fields[2]"
        // "a" must come before "z" in the projection — same `<SEP>name=` rule
        // as the column projection inside table().
        (out.indexOf("a=type=text") < out.indexOf("z=type=integer")) shouldBe true
    }

    // ── view (with and without dependencies) ──

    test("view without dependencies projects materialized/refresh/query/source_dialect, no deps_* markers") {
        val v = ViewDefinition(
            query = "SELECT 1",
            materialized = false,
            refresh = null,
            sourceDialect = "postgresql",
            dependencies = null,
        )
        val out = CanonicalPayload.view(v)
        out shouldContain "materialized=false"
        out shouldContain "refresh="
        out shouldContain "query=SELECT 1"
        out shouldContain "source_dialect=postgresql"
        out shouldNotContain "deps_tables="
        out shouldNotContain "deps_views="
        out shouldNotContain "deps_columns["
    }

    test("view with dependencies emits deps_tables, deps_views, deps_columns sorted") {
        val v = ViewDefinition(
            query = "SELECT u.id FROM users u JOIN orders o ON o.user_id = u.id",
            materialized = true,
            refresh = "ON COMMIT",
            sourceDialect = "postgresql",
            dependencies = DependencyInfo(
                tables = listOf("users", "orders"),
                views = listOf("v_active"),
                columns = mapOf(
                    "users" to listOf("id", "email"),
                    "orders" to listOf("user_id"),
                ),
            ),
        )
        val out = CanonicalPayload.view(v)
        out shouldContain "materialized=true"
        out shouldContain "refresh=ON COMMIT"
        // Tables sorted lexicographically: orders < users.
        out shouldContain "deps_tables=orders,users"
        out shouldContain "deps_views=v_active"
        out shouldContain "deps_columns[2]"
        // Per-table column lists sorted (orders first by lex, then users; columns within sorted).
        out shouldContain "orders=user_id"
        out shouldContain "users=email,id"
    }

    test("view with empty dependencies-collections still emits the deps_* markers") {
        val v = ViewDefinition(
            query = "SELECT 1",
            materialized = false,
            refresh = null,
            sourceDialect = null,
            dependencies = DependencyInfo(
                tables = emptyList(),
                views = emptyList(),
                columns = emptyMap(),
            ),
        )
        val out = CanonicalPayload.view(v)
        out shouldContain "deps_tables="
        out shouldContain "deps_views="
        out shouldContain "deps_columns[0]"
    }
})

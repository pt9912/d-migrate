package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith

class MigrationFingerprintTest : FunSpec({

    fun schema(
        name: String = "App",
        version: String = "1",
        tables: Map<String, TableDefinition> = emptyMap(),
        sequences: Map<String, SequenceDefinition> = emptyMap(),
    ) = SchemaDefinition(name = name, version = version, tables = tables, sequences = sequences)

    test("project starts with the algorithm identifier") {
        MigrationFingerprint.project(schema()).shouldStartWith("algorithm=schema-fingerprint-v10\n")
    }

    // v3: identifier-implied PK canonicalisation
    // (docs/planning/done/migrate-postcompare-identifier-pk-drift.md)

    test("implicit identifier PK fingerprints identically to explicit primary_key") {
        // Soll: PK nur implizit über `identifier` (kein primary_key) — wie ein
        // hand-/CLI-Schema. Reverse: PK explizit materialisiert. Müssen gleich hashen.
        val implicit = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier(autoIncrement = true)),
                "label" to ColumnDefinition(NeutralType.Text()),
            ),
        )))
        val explicit = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier(autoIncrement = true)),
                "label" to ColumnDefinition(NeutralType.Text()),
            ),
            primaryKey = listOf("id"),
        )))
        MigrationFingerprint.compute(implicit) shouldBe MigrationFingerprint.compute(explicit)
        MigrationFingerprint.project(implicit) shouldContain "primary_key=id\n"
    }

    test("ambiguous: multiple identifier columns without primary_key do NOT derive a PK") {
        val twoIds = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf(
                "a" to ColumnDefinition(NeutralType.Identifier(autoIncrement = true)),
                "b" to ColumnDefinition(NeutralType.Identifier(autoIncrement = true)),
            ),
        )))
        // Keine implizite PK abgeleitet → leer; unterscheidet sich von explizitem PK.
        MigrationFingerprint.project(twoIds) shouldContain "primary_key=\n"
        val explicitA = schema(tables = mapOf("t" to TableDefinition(
            columns = twoIds.tables.getValue("t").columns,
            primaryKey = listOf("a"),
        )))
        MigrationFingerprint.compute(twoIds) shouldNotBe MigrationFingerprint.compute(explicitA)
    }

    test("explicit PK diverging from the identifier column stays distinct (no false match)") {
        val pkOnOther = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier(autoIncrement = true)),
                "code" to ColumnDefinition(NeutralType.Text()),
            ),
            primaryKey = listOf("code"),
        )))
        // Nicht-leerer PK wird verbatim genutzt → NICHT auf `id` kanonisiert.
        MigrationFingerprint.project(pkOnOther) shouldContain "primary_key=code\n"
    }

    // v7: dialektbewusste Kanonisierung
    // (docs/planning/done/postcompare-type-canonicalization-slice.md)

    test("v7: type canonicalizer folds dialect-flattened types onto one fingerprint") {
        fun withType(t: NeutralType) = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("val" to ColumnDefinition(t)),
        )))
        // SQLite-artige Faltung als dialektneutrale Test-Projektion.
        val sqliteLike: (NeutralType) -> NeutralType = { t ->
            when (t) {
                NeutralType.SmallInt, NeutralType.BigInteger, NeutralType.BooleanType -> NeutralType.Integer
                is NeutralType.DateTime, NeutralType.Uuid, NeutralType.Json -> NeutralType.Text()
                is NeutralType.Decimal -> NeutralType.Float()
                else -> t
            }
        }
        MigrationFingerprint.compute(withType(NeutralType.SmallInt), sqliteLike) shouldBe
            MigrationFingerprint.compute(withType(NeutralType.Integer), sqliteLike)
        MigrationFingerprint.compute(withType(NeutralType.Decimal(10, 2)), sqliteLike) shouldBe
            MigrationFingerprint.compute(withType(NeutralType.Float()), sqliteLike)
        MigrationFingerprint.compute(withType(NeutralType.DateTime()), sqliteLike) shouldBe
            MigrationFingerprint.compute(withType(NeutralType.Text()), sqliteLike)
        // Identity-Default: ohne Kanonisierer bleiben dieselben Schemata verschieden.
        MigrationFingerprint.compute(withType(NeutralType.SmallInt)) shouldNotBe
            MigrationFingerprint.compute(withType(NeutralType.Integer))
    }

    test("v7: named single-column UNIQUE folds onto the column flag (comparator parity)") {
        val named = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf(
                "a" to ColumnDefinition(NeutralType.Text()),
                "b" to ColumnDefinition(NeutralType.Text()),
            ),
            constraints = listOf(dev.dmigrate.core.model.ConstraintDefinition(
                name = "uq_a", type = dev.dmigrate.core.model.ConstraintType.UNIQUE, columns = listOf("a"),
            )),
        )))
        val flagged = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf(
                "a" to ColumnDefinition(NeutralType.Text(), unique = true),
                "b" to ColumnDefinition(NeutralType.Text()),
            ),
        )))
        MigrationFingerprint.compute(named) shouldBe MigrationFingerprint.compute(flagged)
    }

    test("v7: multi-column UNIQUE stays a distinct named constraint (no fold)") {
        val multi = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf(
                "a" to ColumnDefinition(NeutralType.Text()),
                "b" to ColumnDefinition(NeutralType.Text()),
            ),
            constraints = listOf(dev.dmigrate.core.model.ConstraintDefinition(
                name = "uq_ab", type = dev.dmigrate.core.model.ConstraintType.UNIQUE, columns = listOf("a", "b"),
            )),
        )))
        val flags = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf(
                "a" to ColumnDefinition(NeutralType.Text(), unique = true),
                "b" to ColumnDefinition(NeutralType.Text(), unique = true),
            ),
        )))
        MigrationFingerprint.compute(multi) shouldNotBe MigrationFingerprint.compute(flags)
        MigrationFingerprint.project(multi) shouldContain "constraint=uq_ab"
    }

    test("v7: named single-column FK folds onto the column reference, name-insensitively") {
        fun child(cols: Map<String, ColumnDefinition>, constraints: List<dev.dmigrate.core.model.ConstraintDefinition> = emptyList()) =
            schema(tables = mapOf(
                "parent" to TableDefinition(
                    columns = mapOf("id" to ColumnDefinition(NeutralType.Integer)),
                    primaryKey = listOf("id"),
                ),
                "child" to TableDefinition(columns = cols, constraints = constraints),
            ))
        fun fkConstraint(name: String) = dev.dmigrate.core.model.ConstraintDefinition(
            name = name, type = dev.dmigrate.core.model.ConstraintType.FOREIGN_KEY,
            columns = listOf("parent_id"),
            references = dev.dmigrate.core.model.ConstraintReferenceDefinition(table = "parent", columns = listOf("id")),
        )
        // Authored: Spalten-Level `references:`. Reverse: benannter Constraint (fk_0 / *_fkey).
        val authored = child(mapOf("parent_id" to ColumnDefinition(
            NeutralType.Integer,
            references = dev.dmigrate.core.model.ReferenceDefinition(table = "parent", column = "id"),
        )))
        val reversedSqlite = child(mapOf("parent_id" to ColumnDefinition(NeutralType.Integer)), listOf(fkConstraint("fk_0")))
        val reversedPg = child(mapOf("parent_id" to ColumnDefinition(NeutralType.Integer)), listOf(fkConstraint("child_parent_id_fkey")))
        MigrationFingerprint.compute(authored) shouldBe MigrationFingerprint.compute(reversedSqlite)
        MigrationFingerprint.compute(reversedSqlite) shouldBe MigrationFingerprint.compute(reversedPg)
        // Divergierende Signatur bleibt ein eigener Constraint (kein Fold).
        val diverging = child(
            mapOf("parent_id" to ColumnDefinition(
                NeutralType.Integer,
                references = dev.dmigrate.core.model.ReferenceDefinition(table = "parent", column = "id"),
            )),
            listOf(dev.dmigrate.core.model.ConstraintDefinition(
                name = "fk_other", type = dev.dmigrate.core.model.ConstraintType.FOREIGN_KEY,
                columns = listOf("parent_id"),
                references = dev.dmigrate.core.model.ConstraintReferenceDefinition(table = "parent", columns = listOf("code")),
            )),
        )
        MigrationFingerprint.compute(diverging) shouldNotBe MigrationFingerprint.compute(authored)
        MigrationFingerprint.project(diverging) shouldContain "constraint=fk_other"
    }

    test("v7: required projects as effective value for PK columns (PK implies NOT NULL)") {
        fun pkTable(required: Boolean) = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = required)),
            primaryKey = listOf("id"),
        )))
        MigrationFingerprint.compute(pkTable(false)) shouldBe MigrationFingerprint.compute(pkTable(true))
        // Nicht-PK-Spalten bleiben streng.
        fun plain(required: Boolean) = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("val" to ColumnDefinition(NeutralType.Text(), required = required)),
        )))
        MigrationFingerprint.compute(plain(false)) shouldNotBe MigrationFingerprint.compute(plain(true))
    }

    test("v7: CHECK expressions hash in comparator-canonical form (CRLF/trim parity)") {
        fun withCheck(expr: String) = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("a" to ColumnDefinition(NeutralType.Integer)),
            constraints = listOf(dev.dmigrate.core.model.ConstraintDefinition(
                name = "chk_a", type = dev.dmigrate.core.model.ConstraintType.CHECK, expression = expr,
            )),
        )))
        // Reverse-Reader können Zeilenenden/Randwhitespace anders liefern als das
        // Soll-YAML — der Comparator kanonisiert das (ConstraintDiffContract), der
        // Fingerprint muss dieselbe Form hashen.
        MigrationFingerprint.compute(withCheck("a > 0\r\n")) shouldBe
            MigrationFingerprint.compute(withCheck("a > 0"))
        MigrationFingerprint.compute(withCheck("a > 0")) shouldNotBe
            MigrationFingerprint.compute(withCheck("a > 1"))
    }

    test("v7: constraint on a nonexistent column is NOT folded away") {
        val ghost = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("a" to ColumnDefinition(NeutralType.Text())),
            constraints = listOf(dev.dmigrate.core.model.ConstraintDefinition(
                name = "uq_ghost", type = dev.dmigrate.core.model.ConstraintType.UNIQUE, columns = listOf("ghost"),
            )),
        )))
        MigrationFingerprint.project(ghost) shouldContain "constraint=uq_ghost"
    }

    test("index column prefix length is part of the fingerprint (prefix-length slice)") {
        fun docs(prefix: Int?) = schema(
            tables = mapOf(
                "docs" to TableDefinition(
                    columns = mapOf("body" to ColumnDefinition(NeutralType.Text())),
                    indices = listOf(
                        IndexDefinition(name = "idx_body", columns = listOf(IndexColumn("body", prefixLength = prefix)))
                    ),
                )
            )
        )
        MigrationFingerprint.compute(docs(null)) shouldNotBe MigrationFingerprint.compute(docs(100))
    }

    test("compute returns 64 hex chars (SHA-256)") {
        val fp = MigrationFingerprint.compute(schema())
        fp.length shouldBe 64
        fp.all { it in "0123456789abcdef" } shouldBe true
    }

    test("identical schemas yield identical fingerprints") {
        val a = schema(name = "A", version = "1")
        val b = schema(name = "A", version = "1")
        MigrationFingerprint.compute(a) shouldBe MigrationFingerprint.compute(b)
    }

    test("name and version are NOT part of the fingerprint (content-only hash)") {
        val a = schema(name = "A", version = "1")
        val b = schema(name = "B", version = "2")
        MigrationFingerprint.compute(a) shouldBe MigrationFingerprint.compute(b)
    }

    test("description / encoding / locale are NOT part of the fingerprint") {
        // Same B+ rationale as name/version — these are reporting metadata,
        // not observable database state. The reverse reader does not surface
        // them onto SchemaDefinition, so a YAML that customised them would
        // otherwise drift against any real DB.
        val plain = SchemaDefinition(name = "App", version = "1")
        val annotated = SchemaDefinition(
            name = "App",
            version = "1",
            description = "annotated copy",
            encoding = "latin1",
            locale = "de_DE",
        )
        MigrationFingerprint.compute(plain) shouldBe MigrationFingerprint.compute(annotated)
    }

    test("content differences yield different fingerprints") {
        val emptyA = schema()
        val withTable = schema(
            tables = mapOf(
                "t" to TableDefinition(columns = mapOf("c" to ColumnDefinition(NeutralType.Integer))),
            ),
        )
        MigrationFingerprint.compute(emptyA) shouldNotBe MigrationFingerprint.compute(withTable)
    }

    test("table key order does not affect the fingerprint (deterministic sort)") {
        val t = TableDefinition(columns = mapOf("id" to ColumnDefinition(NeutralType.Integer)))
        val a = schema(tables = mapOf("orders" to t, "users" to t))
        val b = schema(tables = mapOf("users" to t, "orders" to t))
        MigrationFingerprint.compute(a) shouldBe MigrationFingerprint.compute(b)
    }

    test("column key order within a table does not affect the fingerprint") {
        val t1 = TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(NeutralType.Integer),
                "name" to ColumnDefinition(NeutralType.Text()),
            ),
        )
        val t2 = TableDefinition(
            columns = linkedMapOf(
                "name" to ColumnDefinition(NeutralType.Text()),
                "id" to ColumnDefinition(NeutralType.Integer),
            ),
        )
        MigrationFingerprint.compute(schema(tables = mapOf("t" to t1))) shouldBe
            MigrationFingerprint.compute(schema(tables = mapOf("t" to t2)))
    }

    test("primary-key list order DOES affect the fingerprint (PK order is semantic)") {
        val t1 = TableDefinition(
            columns = mapOf("a" to ColumnDefinition(NeutralType.Integer), "b" to ColumnDefinition(NeutralType.Integer)),
            primaryKey = listOf("a", "b"),
        )
        val t2 = TableDefinition(
            columns = mapOf("a" to ColumnDefinition(NeutralType.Integer), "b" to ColumnDefinition(NeutralType.Integer)),
            primaryKey = listOf("b", "a"),
        )
        MigrationFingerprint.compute(schema(tables = mapOf("t" to t1))) shouldNotBe
            MigrationFingerprint.compute(schema(tables = mapOf("t" to t2)))
    }

    test("reverse-marker schemas fold onto the same fingerprint as a plain schema") {
        // Since name/version are excluded from the fingerprint, reverse-marker
        // provenance is automatically irrelevant — no normalization needed
        // at fingerprint time.
        val reverse = SchemaDefinition(
            name = ReverseScopeCodec.postgresName("db", "public"),
            version = ReverseScopeCodec.REVERSE_VERSION,
        )
        val plain = SchemaDefinition(name = "App", version = "1")
        MigrationFingerprint.compute(reverse) shouldBe MigrationFingerprint.compute(plain)
    }

    test("project includes index columns and unique flag") {
        val t = TableDefinition(
            columns = mapOf("c" to ColumnDefinition(NeutralType.Integer)),
            indices = listOf(
                IndexDefinition(name = "ix_c", columns = listOf(IndexColumn("c")), type = IndexType.BTREE, unique = true),
            ),
        )
        val out = MigrationFingerprint.project(schema(tables = mapOf("t" to t)))
        out shouldContain "ix_c"
        out shouldContain "unique=true"
    }

    test("ALGORITHM constant is the version-9 string") {
        MigrationFingerprint.ALGORITHM shouldBe "schema-fingerprint-v10"
    }

    test("child-local partition indices are projected (AP2a)") {
        val out = MigrationFingerprint.project(schema(tables = mapOf(
            "t" to TableDefinition(
                columns = mapOf("c" to ColumnDefinition(NeutralType.Integer)),
                partitioning = PartitionConfig(
                    PartitionType.RANGE, listOf("c"),
                    listOf(PartitionDefinition(
                        name = "p1",
                        to = listOf(PartitionBound.Value("1")),
                        indices = listOf(IndexDefinition(name = "idx_p1_c", columns = listOf(IndexColumn("c")))),
                    )),
                ),
            ),
        )))
        out shouldContain "partition_index=idx_p1_c"
        out shouldContain "unique=false"
    }

    // v4: partitioning is projected (AP4 / ADR 0019).

    test("non-partitioned table projects partitioning=none") {
        val out = MigrationFingerprint.project(schema(tables = mapOf(
            "t" to TableDefinition(columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier()))),
        )))
        out shouldContain "partitioning=none"
    }

    test("partitioned table projects strategy, key and child bounds; order-independent") {
        val children = listOf(
            PartitionDefinition(
                name = "p1",
                from = listOf(PartitionBound.MinValue),
                to = listOf(PartitionBound.Value("'2022-02-01'")),
            ),
            PartitionDefinition(
                name = "p2",
                from = listOf(PartitionBound.Value("'2022-02-01'")),
                to = listOf(PartitionBound.MaxValue),
            ),
        )
        fun projectWith(parts: List<PartitionDefinition>) = MigrationFingerprint.project(schema(tables = mapOf(
            "t" to TableDefinition(
                columns = mapOf("created_at" to ColumnDefinition(NeutralType.DateTime())),
                partitioning = PartitionConfig(PartitionType.RANGE, listOf("created_at"), parts),
            ),
        )))

        val out = projectWith(children)
        out shouldContain "partitioning=RANGE"
        out shouldContain "key=created_at"
        out shouldContain "partition=p1"
        out shouldContain "from=MINVALUE"
        out shouldContain "to=MAXVALUE"
        // Children sorted by name → declaration order does not affect the projection.
        projectWith(children.reversed()) shouldBe out
    }

    test("null vs empty bound list project differently (agrees with comparator)") {
        fun projectFrom(from: List<PartitionBound>?) = MigrationFingerprint.project(schema(tables = mapOf(
            "t" to TableDefinition(
                columns = mapOf("c" to ColumnDefinition(NeutralType.Integer)),
                partitioning = PartitionConfig(
                    PartitionType.RANGE, listOf("c"),
                    listOf(PartitionDefinition(name = "p", from = from, to = listOf(PartitionBound.Value("1")))),
                ),
            ),
        )))
        // null -> "from=", empty list -> "from=<empty>"; the comparator treats
        // null != emptyList, so the fingerprint must not collapse them.
        projectFrom(null) shouldNotBe projectFrom(emptyList())
        projectFrom(emptyList()) shouldContain "from=<empty>"
    }

    test("HASH partition projects modulus and remainder") {
        val out = MigrationFingerprint.project(schema(tables = mapOf(
            "t" to TableDefinition(
                columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier())),
                partitioning = PartitionConfig(
                    PartitionType.HASH, listOf("id"),
                    listOf(PartitionDefinition(name = "h0", modulus = 4, remainder = 1)),
                ),
            ),
        )))
        out shouldContain "modulus=4"
        out shouldContain "remainder=1"
    }

    // ── Branch coverage for type / default / generation projections ──

    test("project covers every NeutralType variant") {
        val variants = mapOf(
            "c01" to NeutralType.Identifier(),
            "c02" to NeutralType.Identifier(autoIncrement = true),
            "c03" to NeutralType.Text(),
            "c04" to NeutralType.Text(maxLength = 64),
            "c05" to NeutralType.Char(8),
            "c06" to NeutralType.Integer,
            "c07" to NeutralType.SmallInt,
            "c08" to NeutralType.BigInteger,
            "c09" to NeutralType.Float(dev.dmigrate.core.model.FloatPrecision.SINGLE),
            "c10" to NeutralType.Float(dev.dmigrate.core.model.FloatPrecision.DOUBLE),
            "c11" to NeutralType.Decimal(10, 2),
            "c12" to NeutralType.BooleanType,
            "c13" to NeutralType.DateTime(),
            "c14" to NeutralType.DateTime(timezone = true),
            "c15" to NeutralType.Date,
            "c16" to NeutralType.Time,
            "c17" to NeutralType.Uuid,
            "c18" to NeutralType.Json,
            "c19" to NeutralType.Xml,
            "c20" to NeutralType.Binary,
            "c21" to NeutralType.Email,
            "c22" to NeutralType.Enum(),
            "c23" to NeutralType.Enum(values = listOf("a", "b")),
            "c24" to NeutralType.Enum(refType = "color_t"),
            "c25" to NeutralType.Array("integer"),
            "c26" to NeutralType.Geometry(dev.dmigrate.core.model.GeometryType.of("point")),
            "c27" to NeutralType.Geometry(dev.dmigrate.core.model.GeometryType.of("polygon"), srid = 4326),
        )
        val table = TableDefinition(
            columns = variants.mapValues { (_, t) -> ColumnDefinition(t) },
        )
        val out = MigrationFingerprint.project(schema(tables = mapOf("t" to table)))
        out shouldContain "type=identifier(auto)"
        out shouldContain "type=text(64)"
        out shouldContain "type=char(8)"
        out shouldContain "type=float(single)"
        out shouldContain "type=decimal(10,2)"
        out shouldContain "type=datetime(tz)"
        out shouldContain "type=enum(ref:color_t)"
        out shouldContain "type=enum(a,b)"
        out shouldContain "type=array(integer)"
        out shouldContain "type=geometry"
    }

    test("project covers every DefaultValue variant") {
        val str = dev.dmigrate.core.model.DefaultValue.StringLiteral("x")
        val num = dev.dmigrate.core.model.DefaultValue.NumberLiteral(0)
        val bool = dev.dmigrate.core.model.DefaultValue.BooleanLiteral(true)
        val fn = dev.dmigrate.core.model.DefaultValue.FunctionCall("now")
        val seq = dev.dmigrate.core.model.DefaultValue.SequenceNextVal("seq_x")
        val table = TableDefinition(
            columns = mapOf(
                "s" to ColumnDefinition(NeutralType.Text(), default = str),
                "n" to ColumnDefinition(NeutralType.Integer, default = num),
                "b" to ColumnDefinition(NeutralType.BooleanType, default = bool),
                "f" to ColumnDefinition(NeutralType.DateTime(), default = fn),
                "q" to ColumnDefinition(NeutralType.Integer, default = seq),
            ),
        )
        val out = MigrationFingerprint.project(schema(tables = mapOf("t" to table)))
        out shouldContain "default=str:x"
        out shouldContain "default=num:0"
        out shouldContain "default=bool:true"
        out shouldContain "default=fn:now"
        out shouldContain "default=seq:seq_x"
    }

    test("project covers identity-generation variants") {
        val table = TableDefinition(
            columns = mapOf(
                "a" to ColumnDefinition(
                    NeutralType.Identifier(),
                    generation = dev.dmigrate.core.model.ColumnGeneration.Identity(
                        mode = dev.dmigrate.core.model.IdentityMode.ALWAYS,
                    ),
                ),
                "b" to ColumnDefinition(
                    NeutralType.Identifier(),
                    generation = dev.dmigrate.core.model.ColumnGeneration.Identity(
                        mode = dev.dmigrate.core.model.IdentityMode.BY_DEFAULT,
                        sequenceName = "id_seq",
                        legacySerialSyntax = true,
                    ),
                ),
            ),
        )
        val out = MigrationFingerprint.project(schema(tables = mapOf("t" to table)))
        out shouldContain "generation=identity:mode=ALWAYS"
        out shouldContain "sequence=id_seq"
        out shouldContain "legacy_serial=true"
    }

    test("project covers reference with onDelete/onUpdate") {
        val table = TableDefinition(
            columns = mapOf(
                "user_id" to ColumnDefinition(
                    NeutralType.Integer,
                    references = dev.dmigrate.core.model.ReferenceDefinition(
                        table = "users",
                        column = "id",
                        onDelete = dev.dmigrate.core.model.ReferentialAction.CASCADE,
                        onUpdate = dev.dmigrate.core.model.ReferentialAction.RESTRICT,
                    ),
                ),
            ),
        )
        val out = MigrationFingerprint.project(schema(tables = mapOf("t" to table)))
        out shouldContain "table=users"
        out shouldContain "onDelete=CASCADE"
        out shouldContain "onUpdate=RESTRICT"
    }

    test("project covers views, sequences, functions, procedures, triggers") {
        val s = SchemaDefinition(
            name = "App",
            version = "1",
            views = mapOf(
                "v1" to dev.dmigrate.core.model.ViewDefinition(
                    query = "SELECT 1",
                    materialized = true,
                    refresh = "ON COMMIT",
                    sourceDialect = "postgresql",
                ),
            ),
            sequences = mapOf(
                "s1" to dev.dmigrate.core.model.SequenceDefinition(
                    start = 10,
                    increment = 2,
                    minValue = 1,
                    maxValue = 100,
                    cycle = true,
                    cache = 5,
                ),
            ),
            functions = mapOf(
                "f1" to dev.dmigrate.core.model.FunctionDefinition(language = "plpgsql", body = "SELECT 1", sourceDialect = "postgresql"),
            ),
            procedures = mapOf(
                "p1" to dev.dmigrate.core.model.ProcedureDefinition(language = "plpgsql", body = "BEGIN END", sourceDialect = "postgresql"),
            ),
            triggers = mapOf(
                "t1" to dev.dmigrate.core.model.TriggerDefinition(
                    table = "orders",
                    event = dev.dmigrate.core.model.TriggerEvent.INSERT,
                    timing = dev.dmigrate.core.model.TriggerTiming.AFTER,
                    condition = "NEW.id > 0",
                    body = "INSERT INTO log VALUES (1)",
                    sourceDialect = "postgresql",
                ),
            ),
        )
        val out = MigrationFingerprint.project(s)
        out shouldContain "view=v1"
        out shouldContain "materialized=true"
        out shouldContain "refresh=ON COMMIT"
        out shouldContain "sequence=s1"
        out shouldContain "increment=2"
        out shouldContain "function=f1"
        out shouldContain "language=plpgsql"
        out shouldContain "procedure=p1"
        out shouldContain "trigger=t1"
        out shouldContain "event=INSERT"
        out shouldContain "timing=AFTER"
    }

    test("project covers constraint with references and CHECK expression") {
        val table = TableDefinition(
            columns = mapOf("a" to ColumnDefinition(NeutralType.Integer)),
            constraints = listOf(
                dev.dmigrate.core.model.ConstraintDefinition(
                    name = "fk_orders_users",
                    type = dev.dmigrate.core.model.ConstraintType.FOREIGN_KEY,
                    columns = listOf("user_id"),
                    references = dev.dmigrate.core.model.ConstraintReferenceDefinition(table = "users", columns = listOf("id")),
                ),
                dev.dmigrate.core.model.ConstraintDefinition(
                    name = "chk_age",
                    type = dev.dmigrate.core.model.ConstraintType.CHECK,
                    expression = "age >= 0",
                ),
            ),
        )
        val out = MigrationFingerprint.project(schema(tables = mapOf("t" to table)))
        out shouldContain "constraint=fk_orders_users"
        out shouldContain "users[id]"
        out shouldContain "expr=age >= 0"
    }

    test("project covers custom types") {
        val s = SchemaDefinition(
            name = "App",
            version = "1",
            customTypes = mapOf(
                "color_t" to dev.dmigrate.core.model.CustomTypeDefinition(
                    kind = dev.dmigrate.core.model.CustomTypeKind.ENUM,
                    values = listOf("red", "green"),
                ),
                "money_t" to dev.dmigrate.core.model.CustomTypeDefinition(
                    kind = dev.dmigrate.core.model.CustomTypeKind.DOMAIN,
                    baseType = "decimal",
                    precision = 12,
                    scale = 2,
                    check = "VALUE > 0",
                ),
            ),
        )
        val out = MigrationFingerprint.project(s)
        out shouldContain "custom_type=color_t"
        out shouldContain "kind=ENUM"
        out shouldContain "values=red,green"
        out shouldContain "custom_type=money_t"
        out shouldContain "base=decimal"
        out shouldContain "precision=12"
        out shouldContain "check=VALUE > 0"
    }
})

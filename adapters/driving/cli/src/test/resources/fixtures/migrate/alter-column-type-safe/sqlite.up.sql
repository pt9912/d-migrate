PRAGMA foreign_keys = OFF;
BEGIN IMMEDIATE;
CREATE TABLE "users__dmg_rebuild_edb701ec" (
    "id" INTEGER NOT NULL,
    "age" INTEGER,
    PRIMARY KEY ("id")
);
INSERT INTO "users__dmg_rebuild_edb701ec" ("id", "age") SELECT "id", CAST("age" AS INTEGER) FROM "users";
DROP TABLE "users";
ALTER TABLE "users__dmg_rebuild_edb701ec" RENAME TO "users";
PRAGMA foreign_key_check;
COMMIT;
PRAGMA foreign_keys = ON;

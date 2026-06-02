PRAGMA foreign_keys = OFF;
BEGIN IMMEDIATE;
CREATE TABLE "users__dmg_rebuild_edb701ec" (
    "age" INTEGER,
    "id" INTEGER NOT NULL,
    PRIMARY KEY ("id")
);
INSERT INTO "users__dmg_rebuild_edb701ec" ("age", "id") SELECT CAST("age" AS INTEGER), "id" FROM "users";
DROP TABLE "users";
ALTER TABLE "users__dmg_rebuild_edb701ec" RENAME TO "users";
PRAGMA foreign_key_check;
COMMIT;
PRAGMA foreign_keys = ON;

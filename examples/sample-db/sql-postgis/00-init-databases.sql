-- Sample-DB-Harness PostGIS initdb (Phase 5, VA1-Live-Smoke).
-- Läuft einmalig beim ersten Volume-Init des postgis-Service, als POSTGRES_USER
-- gegen POSTGRES_DB. Legt nur die Quell-/Ziel-DBs an; CREATE EXTENSION postgis
-- und die Geometrie-Tabellen erzeugt smoke-spatial.sh pro Lauf (idempotent).
CREATE DATABASE geo_pg_src;
CREATE DATABASE geo_pg_target;

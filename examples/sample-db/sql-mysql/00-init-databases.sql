-- Sample-DB-Harness MySQL initdb — Plan: docs/planning/in-progress/sample-db-integration-harness.md
--
-- Läuft einmalig beim ersten Volume-Init des mysql-Service
-- (docker-entrypoint-initdb.d), als root.
-- MYSQL_DATABASE=sakila legt die Quell-DB bereits an und grantet sie
-- MYSQL_USER. Hier wird nur das zweite Ziel `pagila_target` (für den
-- Cross-Dialect-Flow Pagila PG→MySQL, Phase 2) nachgelegt + gegrantet.
-- Befüllt werden beide erst vom Smoke-Skript.
CREATE DATABASE IF NOT EXISTS pagila_target
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON pagila_target.* TO 'dmigrate'@'%';
FLUSH PRIVILEGES;

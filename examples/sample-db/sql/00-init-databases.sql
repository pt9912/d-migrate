-- Sample-DB-Harness initdb — Plan: docs/planning/in-progress/sample-db-integration-harness.md
--
-- Läuft einmalig beim ersten Volume-Init des postgres-Service
-- (docker-entrypoint-initdb.d), als POSTGRES_USER gegen POSTGRES_DB.
-- Legt die Round-Trip-Datenbanken an; befüllt werden sie erst
-- vom Smoke-Skript (Dump-Load in pagila, Transfer nach pagila_target).
-- sakila_target ist das PG-Ziel für den Cross-Dialect-Flow Sakila MySQL→PG
-- (Phase 2).
CREATE DATABASE pagila;
CREATE DATABASE pagila_target;
CREATE DATABASE sakila_target;
-- Phase 3 (Scale): PG-Ziel für den Employees-Scale-Flow. Der Scale-Smoke
-- legt es bei bestehendem Volume selbst an (DROP/CREATE WITH FORCE).
CREATE DATABASE employees_pg_target;

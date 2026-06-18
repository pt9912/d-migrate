-- Sample-DB-Harness initdb — Plan: docs/planning/next/sample-db-integration-harness.md
--
-- Läuft einmalig beim ersten Volume-Init des postgres-Service
-- (docker-entrypoint-initdb.d), als POSTGRES_USER gegen POSTGRES_DB.
-- Legt die zwei Round-Trip-Datenbanken an; befüllt werden sie erst
-- vom Smoke-Skript (Dump-Load in pagila, Transfer nach pagila_target).
CREATE DATABASE pagila;
CREATE DATABASE pagila_target;

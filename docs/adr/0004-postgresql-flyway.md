# ADR 0004 — PostgreSQL en Flyway

Status: geaccepteerd

## Context

De uiteindelijke workflow moet brongegevens, runs en resultaten duurzaam en zonder bewaartermijn
opslaan.

## Besluit

PvdD krijgt een eigen PostgreSQL 16-database en schema. Alle schemawijzigingen lopen voorwaarts via
Flyway. De technische fundering maakt alleen `application_metadata`; functionele tabellen volgen
pas in fase 2.

## Gevolgen

Migraties zijn reproduceerbaar en testbaar met Testcontainers. Backups worden dagelijks gemaakt en
niet automatisch verwijderd; herstel wordt aantoonbaar getest.

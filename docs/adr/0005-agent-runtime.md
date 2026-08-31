# ADR 0005 — Asynchrone AI via Agent Runtime

Status: geaccepteerd

## Context

AI-opdrachten mogen niet rechtstreeks vanuit de webrequest of vanuit de frontend naar een model.
De bestaande Agent Runtime biedt queueing, idempotentie en tenantisolatie.

## Besluit

PvdD gebruikt de Agent Runtime v1 REST-API met een eigen `APPLICATION_WORK`-tenant en prefix `PVDD`.
Acceptance gebruikt uitsluitend `MOCKED`; productie weigert mocks.

## Gevolgen

De backend moet jobs aanmaken, volgen, resultaten ophalen en annuleren, en restartbestendige status
later functioneel opslaan. Het token blijft een runtime-secret en wordt nooit aan de browser gegeven.

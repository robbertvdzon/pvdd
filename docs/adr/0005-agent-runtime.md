# ADR 0005 — Asynchrone AI via Agent Runtime

Status: geaccepteerd

## Context

AI-opdrachten mogen niet rechtstreeks vanuit de webrequest of vanuit de frontend naar een model.
De bestaande Agent Runtime biedt queueing, idempotentie en tenantisolatie.

## Besluit

PvdD gebruikt de Agent Runtime v2 REST-API met een eigen `APPLICATION_WORK`-tenant. Iedere opdracht
is `STRUCTURED_GENERATION` met expliciete `vendorId`, `model` en `mode`, zonder fallback. Grote
prompts worden eerst hervatbaar als Markdown geüpload en als inputobject aan de job gekoppeld.
Acceptance gebruikt uitsluitend `mock`/`mock`/`MOCK`; productie weigert mocks.

## Gevolgen

De backend maakt jobs aan, volgt ze restartbestendig, haalt resultaten op en kan ze annuleren. Een
idempotentiesleutel voorkomt dubbele jobs bij retries. Het token blijft een runtime-secret en wordt
nooit aan de browser gegeven.

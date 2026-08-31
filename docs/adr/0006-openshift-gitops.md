# ADR 0006 — OpenShift en GitOps

Status: geaccepteerd

## Context

De overige diensten draaien op OpenShift en worden declaratief door Argo CD beheerd. Handmatige
clusterwijzigingen zijn niet reproduceerbaar.

## Besluit

PvdD levert Kustomize-overlays voor acceptance en productie. GitHub Actions bouwt immutable images
en commit imagepins; `robberts-infrastructure` registreert beide Argo CD Applications. De publieke
host is `pvdd.vdzonsoftware.nl`.

## Gevolgen

Git blijft de bron van waarheid, rollback is een Git-wijziging en acceptance gaat vóór productie.
Secrets staan uitsluitend versleuteld in Git. Software Factory wordt pas na de functionele MVP
aangesloten en maakt geen tweede deploypad.

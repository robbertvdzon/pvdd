# ADR 0002 — Flutter web voor de frontend

Status: geaccepteerd

## Context

De applicatie vraagt een compacte beveiligde webinterface en moet qua ontwikkeling aansluiten op
HKH Autopilot, met een Product Factory-geïnspireerde vormgeving.

## Besluit

De frontend wordt een Flutter-webapp met Material 3. CI en Docker gebruiken exact dezelfde gepinde
Flutterversie. De frontend communiceert same-origin met `/api`.

## Gevolgen

Er is één UI-codebase en een bestaand testpatroon. Extra maatregelen zijn nodig tegen verouderde
Flutter-webcaches; die zijn onderdeel van het verplichte cachecontract.

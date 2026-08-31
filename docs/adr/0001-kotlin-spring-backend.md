# ADR 0001 — Kotlin en Spring voor de backend

Status: geaccepteerd

## Context

PvdD moet aansluiten op de bewezen technische basis van HKH Autopilot en op bestaande kennis,
tests, containers en beheerprocessen.

## Besluit

De backend gebruikt Kotlin op JDK 21, Maven, Spring Boot en Spring Modulith. Modules krijgen
expliciete architectuurgrenzen en worden automatisch geverifieerd.

## Gevolgen

We hergebruiken patronen zonder HKH-productlogica te kopiëren. JDK-, Kotlin-, Maven- en
frameworkversies worden reproduceerbaar gepind en samen geüpdatet.

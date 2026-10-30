# Commissie Bestuur: bronvaststelling

Vastgesteld op 2026-10-29 met alleen-lezen GET-verzoeken naar `https://noordholland.bestuurlijkeinformatie.nl`
(startpagina, `Agenda/RetrieveAgendasForYear` en twee `Agenda/Index/<id>`-pagina's). De bron was bereikbaar (HTTP 200).

## Agenda-type

| Commissie | Weergavenaam | `agendatypeId` |
|-----------|--------------|----------------|
| Ruimte    | Ruimte       | `1100617069`   |
| Bestuur   | Bestuur      | `1100617071`   |

Bron van het type-id: het attribuut `data-agendatype-id` op de kalenderitems van de startpagina, waar
"Commissie Bestuur" `1100617071` draagt (ter vergelijking: Leefomgeving `1100617072`, Landelijk gebied
`1100617066`, Presidium `8`, PS-vergadering `1`). `RetrieveAgendasForYear?agendatypeId=1100617071&year=2026`
levert een jaarlijst in dezelfde opbouw als die van Ruimte (`agenda-link`, datum met `sr-only`-jaar).

## Opbouw ten opzichte van Ruimte

Een Bestuur-agenda (26 oktober 2026) en een Ruimte-agenda (22 oktober 2026) zijn met de bestaande `AgendaParser`
ongewijzigd te lezen: koptekst "Commissie Bestuur", tijd uit `.heading3`, A/B/C-categorieën en stukken via
`/Document/...`-links, gepubliceerd. Bestuur: 20 agendapunten (A 9, B 5, C 4, overig 2) met 28 documenten;
Ruimte: 9 punten met 9 documenten.

## Afwijkingen

Geen afwijkende opbouw. Er is geen aparte parser nodig. Verschillen zijn alleen kwantitatief (Bestuur heeft
meer punten, en C-punten hebben stukken); de C-verrijking via rapportpagina's is niet apart onderzocht.
De discovery voor Bestuur is sinds story hkh-362 geïmplementeerd (zie microservice-specificatie §4.2);
zonder agenda-type blijft de commissie overgeslagen.

## Configuratie

- Productie: `1100617071` (zelfde bron-id als acceptatie), via `deploy/overlays/production/configmap.yaml`. Sinds 30 oktober 2026 actief; eerder stond Bestuur in productie bewust uit en werd het bij discovery overgeslagen.
- Acceptatie: `1100617071` (het vastgelegde bron-id; de bronmock `meeting-source-mock` serveert onder dat type een synthetische Bestuur-reeks, zie `docs/acceptatiemock-bestuur-scenarios.md`), via `deploy/overlays/acceptance/configmap.yaml`. (Het eerder gebruikte synthetische id `9000000001` is alleen bedoeld voor een niet-bereikbare bron en is niet meer in gebruik.)
- De oude property `pvdd.meeting-source.agenda-type-id` blijft een alias voor Ruimte; een expliciete
  lijstwaarde `pvdd.meeting-source.commissies[n].agenda-type-id` voor Ruimte wint.

Leespaden: sinds story hkh-383 accepteren de leesroutes de queryparameter `commissie` (standaard `RUIMTE`);
zie microservice-specificatie §5.2.

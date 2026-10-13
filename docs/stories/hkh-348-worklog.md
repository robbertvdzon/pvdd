# hkh-348 — worklog

## hkh-352 — documentation (2026-10-13)

### Doel
Controleren of de documentatie klopt met de volledige storydiff: de doorstuurroute
`AcceptanceScenarioController` voor de acceptatiescenariobediening op de ACC-origin.

### Bijgewerkt
- Geen inhoudelijke wijzigingen nodig: `docs/technical-integrations.md`, sectie
  'Meerdere-vergaderingenscenario's (alleen acceptatie)', is in de developmentsubtaak al bijgewerkt
  (basis-URL, allowlist per methode en pad, sessie en CSRF, curlvoorbeeld, mock intern bereikbaar,
  route bestaat niet in productie en lokaal) en klopt met de code en tests.
- Deze worklog toegevoegd.

### Bewust niet gewijzigd
- `README.md`: verwijst al naar `docs/technical-integrations.md` en `/api/scenarios`.
- `docs/microservice-specificatie.md` en `docs/factory/*`: alleen acceptatieroute, geen wijziging van
  productiegedrag, filters, limieten of AI-aanroepen.
- `docs/stappenplannen/*`: uitvoeringsplannen, geen beschrijving van huidig gedrag.

### Verificatie
- `bash tools/verify-documentation.sh` uitgevoerd; alleen `docs/` is geraakt.

# Agenttoegang

Open `/api/auth/agent-login` op dezelfde origin als de frontend, of gebruik
`POST /api/auth/agent-session`, header `X-AI-Access-Token`, JSON `{"email":"toegestane-gebruiker"}`.
De response bevat een normale applicatiesessie; rechten blijven door de applicatie gecontroleerd.

Per omgeving configureer je `AI_ACCESS_TOKEN` (minimaal 32 willekeurige tekens), `AI_ACCESS_EMAILS`
(expliciete bestaande/toegestane identiteiten) en `AI_ACCESS_ALLOWED_ORIGINS` (exacte frontendorigins; voor PR-hostnamen uitsluitend `{pr}` als numeriek gedeelte).
Zonder token staat de ingang uit. Er worden bij aanmelden geen accounts of rollen aangemaakt.
Gebruik op test/acceptatie aparte accounts en tokens. Alleen de testtoken mag als bijvoorbeeld
`PVDD__ACCEPTANCE_AGENT_TOKEN` in Agent Runtime komen.
Productietokens, databasecredentials en signing secrets blijven buiten de runtime. Productie-login
mag uitsluitend in een begeleide Codex/Claude-taak na Robberts expliciete toestemming. Onderzoek
standaard via alleen-lezen databasequeries en OpenShift-logs; gebruik de browser voor zichtbaar gedrag.

Tokenwaarden mogen niet in URLs, logs, terminaluitvoer, prompts of screenshots belanden. Voor
browserautomatisering leest een lokaal helperproces de geselecteerde testcredential en plaatst de
sessie in dezelfde browsercontext; geef de ruwe token nooit aan het model terug.

## Begeleid productieonderzoek

Gebruik eerst alleen-lezen databasequeries en OpenShift-logs/status. Voor expliciet geautoriseerde
productielogin staat in `robberts-infrastructure/tools/copy-agent-access-token.py` een helper die de
token alleen naar het macOS-klembord schrijft. Plak rechtstreeks in het gemaskeerde loginveld en
wis het klembord na gebruik. De applicatie-ingang verleent uitsluitend bestaande rechten.

Acceptatie gebruikt dezelfde cookie-/CSRF-authenticatie als productie. De vroegere automatische
acceptatie-bypass is in de deployment uitgeschakeld. Het synthetische account is
`acceptance-tester@pvdd.invalid`; gebruik `PVDD__ACCEPTANCE_AGENT_TOKEN` voor aanmelden.
De publieke rooktest controleert dat een niet-ingelogde sessie HTTP 401 krijgt.

## Productadviseur: expliciet geautoriseerde leestoegang voor PvdD

Robbert heeft op 16 september 2026 een beperkte uitzondering toegestaan voor de PvdD-productadviseur.
`AI_READ_ACCESS_TOKEN` is een aparte capability; `X-AI-Read-Token` autoriseert alleen de expliciet
beoordeelde GET/HEAD-routes in `ProductionReadAccessFilter`. `AI_READ_ACCESS_EMAIL` moet een bestaande
toegestane identiteit zijn. Deze token werkt niet op de loginroutes, maakt geen sessie en kan geen
analyse starten, instellingen wijzigen of andere mutaties uitvoeren. Nieuwe routes zijn standaard
ontoegankelijk; zo staan de leesroutes `GET /api/meetings/{id}`, `GET /api/meetings?state=past` en
`GET /api/agenda-items/{id}/advice-versions` er bewust nog niet in en geven zij met deze token
`403`. Gebruik voor productieonderzoek
`GET /api/meetings/next` en `GET /api/meetings/{id}/agenda-items`. De gewone `AI_ACCESS_TOKEN`
blijft buiten Agent Runtime.

Alleen de Product Factory-productadviseur krijgt `PVDD__PRODUCTION_READ_ONLY_TOKEN`; testers en
Software Factory krijgen hem niet. Lees eerst gericht de API; gebruik voor daadwerkelijke visuele
controle Playwright met een route-handler die de header uitsluitend op de exacte productie-origin
`https://pvdd.vdzonsoftware.nl` en `/api/` toevoegt. Gebruik geen globale extraHTTPHeaders: externe
resources mogen de token nooit ontvangen. Toon de token niet en zet hem niet in afbeeldingen.

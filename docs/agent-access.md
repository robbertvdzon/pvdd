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
ontoegankelijk: een route doet pas mee nadat zij expliciet is beoordeeld en aan de padlijst van de
filter is toegevoegd.

Sinds 18 september 2026 horen ook de drie beoordeelde archief-leesroutes bij die lijst:
`GET /api/meetings` (de vergaderinglijst), `GET /api/meetings/{id}` (de vergaderkop) en
`GET /api/agenda-items/{id}/advice-versions` (de bewaarde adviesversies). Samen met de al langer
toegestane `GET /api/meetings/next` en `GET /api/meetings/{id}/agenda-items` is dat het complete
archiefbeeld voor productieonderzoek. Het mechanisme zelf is daarbij niet veranderd.

Wat daarbij hoort te weten:

- De filter matcht exact op het pad zonder querystring, dus
  `GET /api/meetings?state=past&limit=20&cursor=…` is toegestaan, maar `/api/meetings/`,
  `/api/meetings;a=b`, `/api/meetings/not-a-uuid`, `/api/meetings/{id}/extra` en
  `…/advice-versions/` leveren `403`.
- Alleen `GET` en `HEAD` komen erdoor; elke andere methode op dezelfde paden levert `403`.
- Een leeg, te lang of onjuist token, een uitgeschakelde capability of een niet-toegestaan
  e-mailadres levert `401`. Ontbreekt de header `X-AI-Read-Token`, dan slaat deze filter zichzelf
  over en blijft de gewone sessiecontrole gelden; ongeauthenticeerd verkeer krijgt dan `401` en
  geen archiefgegevens.
- Elk antwoord van deze capability krijgt `Cache-Control: no-store` en zet nooit een `Set-Cookie`.

De gewone `AI_ACCESS_TOKEN` blijft buiten Agent Runtime.

Alleen de Product Factory-productadviseur krijgt `PVDD__PRODUCTION_READ_ONLY_TOKEN`; testers en
Software Factory krijgen hem niet. Lees eerst gericht de API; gebruik voor daadwerkelijke visuele
controle Playwright met een route-handler die de header uitsluitend op de exacte productie-origin
`https://pvdd.vdzonsoftware.nl` en `/api/` toevoegt. Gebruik geen globale extraHTTPHeaders: externe
resources mogen de token nooit ontvangen. Toon de token niet en zet hem niet in afbeeldingen.

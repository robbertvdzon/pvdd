# Worklog hkh-291 — Meekijkende adviseur krijgt alleen-lezen toegang tot het archief

## hkh-292 — development (2026-09-18)

### Doel
De bestaande, gescopeerde alleen-lezen leescapaciteit (`X-AI-Read-Token`) dekt na deze taak ook de
drie beoordeelde archief-leesroutes: de vergaderinglijst, de vergaderkop en de bewaarde
adviesversies. Het mechanisme zelf — tokencontrole, e-mailcontrole, GET/HEAD-only,
`Cache-Control: no-store`, geen sessie — verandert niet. Backend-only, uitsluitend de module `auth`.

### Stap 0 — aannames geverifieerd in de checkout
1. **De bestanden en de stijl kloppen met de storybeschrijving.**
   `ProductionReadAccessFilter.kt` heeft de companion-constante `UUID`
   (`[0-9a-fA-F]{8}-…-[0-9a-fA-F]{12}`) en een `private val paths = listOf(Regex(...), …)` met
   `fun allowedPath(path: String) = paths.any { it.matches(path) }`. De bestaande entries gebruiken
   **geen** expliciete `^`/`$`-ankers, omdat `Regex.matches` sowieso de volledige tekenreeks eist.
   De drie nieuwe patronen volgen die conventie letterlijk. Geen afwijking van de aanname.
2. **De testklasse gebruikt een directe filteraanroep**, niet MockMvc:
   `filter.doFilter(MockHttpServletRequest(...), MockHttpServletResponse()) { req, _ -> … }` met de
   bestaande constanten `token = "read-only-token-".repeat(4)`, `AuthConfig("test-client",
   "production")` en `AuthConfig.ALLOWED_EMAILS.first()`. Die opzet is overgenomen; er is geen
   nieuwe testopzet, geen Spring-context, geen testcontainer en geen fixture bijgekomen.
3. **De drie onderliggende leesroutes staan al op de branchbasis**: `DashboardController` heeft
   `@GetMapping("/meetings")` (hkh-270), `@GetMapping("/meetings/{id}")` (hkh-263) en
   `@GetMapping("/agenda-items/{id}/advice-versions")` (hkh-277). De filterwijziging is overigens
   route-agnostisch: de filter beslist op pad en methode, niet op het bestaan van een controller.

### Gerealiseerd — `backend/src/main/kotlin/nl/vdzon/pvdd/auth/ProductionReadAccessFilter.kt`
Drie regels toegevoegd, achteraan in de bestaande `paths`-lijst, zonder bestaande entries te
herschrijven of te herordenen:

```kotlin
Regex("/api/meetings"),
Regex("/api/meetings/$UUID"),
Regex("/api/agenda-items/$UUID/advice-versions"),
```

- De `UUID`-constante is hergebruikt en niet geherdefinieerd of verbreed; hoofdletters in de
  UUID-positie volgen dus precies wat die constante vandaag toestaat.
- Er is geen private padconstante toegevoegd: de klasse hanteert dat patroon niet.
- `/api/meetings` matcht exact, dus géén afsluitende slash, géén `;`-parameter en géén extra
  segment. `/api/meetings/next` en `/api/meetings/{id}/agenda-items` blijven onder hun eigen
  bestaande patronen vallen; het nieuwe `<uuid>`-patroon vangt `next` niet op (geen UUID).
- Er wordt gematcht op `request.requestURI`, dus een querystring telt niet mee.
- Niets anders gewijzigd: tokencontrole, e-mailcontrole, lengte-/leegcontrole, methodecontrole,
  `Cache-Control: no-store`, `shouldNotFilter`, sessiegedrag, `@Order`/filterregistratie en de
  bestaande toegestane paden zijn ongemoeid. Geen nieuwe configuratiesleutel, `@Value`-binding,
  bean, rol, authority of logstatement; `AI_READ_ACCESS_TOKEN` en `AI_READ_ACCESS_EMAIL` blijven
  ongewijzigd. `ApiAuthenticationFilter` is niet aangeraakt.

### Testvoorziening — `ProductionReadAccessFilterTest.kt` (4 → 10 tests)
Uitsluitend toevoegingen, in de bestaande stijl; de vier bestaande tests behouden hun bestaande
regels en assertie-opzet. Nieuwe gedeelde constanten staan in een `private companion object`:
`ARCHIVE_UUID`, `CURSOR` (een base64url-cursor, geen geheim) en
`ARCHIVE_PATHS` = de drie nieuwe paden. Twee nieuwe helpers: `assertAllowed(request)` en
`assertNoTokenLeak(method, path, provided, status)`; de bestaande `assertDenied` is ongewijzigd
hergebruikt.

| AC | Test |
| --- | --- |
| 1 | `only reviewed reads reach the application and no user session is issued` — de drie nieuwe paden zijn aan de bestaande positieve lijst toegevoegd (GET, keten voortgezet, beide requestattributen gezet, geen `Set-Cookie`) |
| 1, 6 | `the archive reads are allowed for GET and HEAD and stay uncacheable` — pad ⨯ methode (GET, HEAD) over de drie nieuwe paden; per geval: keten voortgezet, status niet 401 en niet 403, `Cache-Control: no-store`, geen `Set-Cookie` |
| 5 (regressie) | dezelfde test draait ook op `/api/meetings/next` en `/api/meetings/<uuid>/agenda-items`; die blijven toegestaan |
| 2 | `the meeting list stays allowed when the archive query parameters are supplied` — `state=past&limit=20&cursor=<base64>` als querystring én als parameters gezet, met de assertie dat `requestURI` `/api/meetings` blijft; naast de case zonder querystring uit de positieve lijst |
| 4 | `no method other than GET or HEAD reaches the archive reads` — `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS` ⨯ de drie nieuwe paden, met geldig token en toegestaan e-mailadres: 403 en keten niet voortgezet |
| 5 | `only the exact archive paths are allowed` — `/api/meetings/`, `/api/meetings;a=b`, `/api/meetings/<uuid>/`, `/api/meetings/not-a-uuid`, `/api/meetings/<uuid>/extra`, `/api/agenda-items/<uuid>/advice-versions/`, `/api/agenda-items/<uuid>/advice-versions;a=b` en `…/advice-versions/1`: telkens 403 |
| 3 | `disabled wrong token and disallowed identity fail closed` — zes toegevoegde regels op de nieuwe paden: onjuist token, leeg token, te lang token (4097 tekens), uitgeschakelde capability (`AI_READ_ACCESS_TOKEN` leeg) en niet-toegestaan e-mailadres: 401 en keten niet voortgezet |
| 7 | `a refused archive request never repeats the read token` — bij een 401 en bij twee 403's bevat noch de body, noch de `errorMessage`/reason, noch enige responseheader de tokenwaarde; daarnaast `no-store` aanwezig en geen `Set-Cookie` |
| 10 | `without the read header the archive paths keep their existing authentication path` — zonder `X-AI-Read-Token` slaat de filter zichzelf over: keten ongewijzigd voortgezet, geen requestattribuut gezet, geen `Cache-Control` door deze filter |

AC6, AC7(a) en het whitelistdeel van AC10 zijn diff-criteria: de branchdiff toont uitsluitend drie
toegevoegde regels in `paths`, geen wijziging in `ApiAuthenticationFilter.shouldNotFilter`
(`/api/version` + auth-routes) en geen nieuwe bean, sleutel, rol of logregel.

### Reproduceerbare commando's voor de tester
- Volledig vangnet backend: `cd backend && mvn -B --no-transfer-progress clean verify`.
- Alleen deze filtertest: `cd backend && mvn -B --no-transfer-progress
  -Dtest=ProductionReadAccessFilterTest -DfailIfNoSpecifiedTests=false test`.
- Er is geen database, geen Docker, geen preview, geen externe koppeling en geen credential nodig.
  `DatabaseIntegrationTest` wordt zonder Docker en zonder `PVDD_TEST_DATABASE_URL` overgeslagen; dat
  raakt deze story niet, omdat de wijziging geen data aanraakt.
  `PVDD__ACCEPTANCE_AGENT_TOKEN` is niet gebruikt en komt in geen test, log of uitvoer voor.

### Bewijs uit deze ronde
- `mvn -B --no-transfer-progress -Dtest=ProductionReadAccessFilterTest -DfailIfNoSpecifiedTests=false
  test` → 10 tests, 0 failures, 0 errors, 0 skipped (was 4 tests).
- `mvn -B --no-transfer-progress clean verify` in `backend/` → `BUILD SUCCESS`.
- `bash tools/verify-documentation.sh` → `Documentatie en repositoryhygiëne zijn in orde.`
- **Mutatiecontrole per toegevoegd patroon.** Met `Regex("/api/meetings")` verwijderd falen precies
  drie tests (de positieve lijst, de GET/HEAD-test en de querystringtest); met
  `Regex("/api/meetings/$UUID")` verwijderd falen precies twee tests; met
  `Regex("/api/agenda-items/$UUID/advice-versions")` verwijderd falen precies twee tests. Elk van de
  drie regels wordt dus echt door een test gedekt. Daarna is het bestand byte-identiek hersteld.

### Beperking (geen bevinding)
Er is geen previewtemplate en geen preview-URL toegewezen, en de acceptatieomgeving volgt `main` en
bevat deze wijziging pas ná merge. Al het bewijs vóór merge komt daarom uit de JVM-tests op de
storybranch plus de branchdiff. De rookcontrole met het echte leestoken (`GET` op de drie paden →
200 met `no-store` en zonder `Set-Cookie`, `POST` → 403) kan pas na deployment en is nooit een
merge-poort; de tokenwaarde wordt daarbij niet gedeeld, gelogd of in een verslag opgenomen.

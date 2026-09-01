# Functionele specificatie

Dit document is de compacte ontwikkelvertaling van de normatieve
[microservicespecificatie](../microservice-specificatie.md). Bij verschil gaat die specificatie
voor. De MVP is een read-only Commissie-assistent voor de eerstvolgende vergadering van commissie
Ruimte van Provincie Noord-Holland.

## Gebruikersflow

Na Google-login toont de app de eerstvolgende vergadering, bronstatus, laatste controle en
analysevoortgang. Dezelfde orkestratie draait dagelijks om 05:00 uur in `Europe/Amsterdam` en via
**Nu controleren**. Zonder toekomstige vergadering, bij hetzelfde reeds succesvol verwerkte
bron-ID én dezelfde canonieke inhoud, of bij een ongepubliceerde agenda zonder zichtbare
verwerkbare stukken ontstaan geen documentdownloads en geen AI-jobs. Zichtbare voorlopige stukken
worden direct gedownload en geanalyseerd; het voorlopige advies wordt bij een nieuwe bronversie
gericht vervangen.

## Vergadering en agenda

De importer ontdekt de vroegste toekomstige vergadering via iBabs-agendatype `1100617069`; een
vergadering-UUID wordt niet hardcoded. Hij bewaart bron-ID, datum/tijd, commissie, locatie,
bron-URL, bronhash, controle-/importtijd en status. Voor elk agendapunt bewaart hij hiërarchie,
volgorde, nummer, semantische categorie A/B/C, titel, toelichting, behandelvoorstel, bronmetadata en
importstatus. Opening, pauze en sluiting blijven zichtbaar maar zijn niet inhoudelijk voor AI.

C-items komen uit de tabel onder de C-sectie en worden verrijkt via `/Reports/Item/...`. De
viewerlinks onder `/Agenda/Document/...` en `/Reports/Document/...` worden op basis van het
gevalideerde document-ID omgezet naar de openbare bytesroute `/Document/View/{documentId}`. De
categorie komt altijd uit de actuele sectiehiërarchie, niet alleen uit het agendanummer.

## Documenten

Alleen expliciet toegestane HTTPS-hosts worden gelezen. Aantal, individuele en totale grootte,
redirects, retries en time-outs zijn begrensd. MIME en magic bytes moeten overeenkomen. PDF, platte
tekst, HTML en DOCX worden als tekst per pagina of sectie opgeslagen met bron-ID, URL, bestandsnaam,
SHA-256, MIME, grootte, ophaaltijd en extractiestatus. HTML wordt gesanitized en scripts, macro's en
documentinstructies worden nooit uitgevoerd. Een scan zonder tekst krijgt `OCR_REQUIRED`; een
gedeeltelijk of onleesbaar dossier heet nooit volledig ingelezen.

## A- en B-advies

De AI krijgt voor ieder inhoudelijk A/B-punt deze richtinggevende vragen mee:

1. **Waar gaat het over?** — feitelijke samenvatting;
2. **Wat vinden we ervan?** — politieke beoordeling met programmapassages;
3. **Wat kunnen/willen we ermee in de commissie?** — handelingsopties en doel;
4. **Welke punten willen we maken en wat willen we van de gedeputeerde?** — concrete verzoeken;
5. **Welke technische vragen gaan we stellen?** — feitelijke vragen over ontbrekende informatie.

Het eindresultaat is één vrij Markdowndocument. Deze indeling en eventuele bronverwijzingen worden
niet technisch afgedwongen; voor de MVP vertrouwen we op de AI-uitvoering.

## C-advies

Voor ieder C-punt vraagt de prompt of bespreking en verplaatsing naar B wenselijk is, met een
bruikbare motivering. De AI is vrij in de Markdownindeling; er is geen apart C-responseschema.

## Politiek kader

Het Noord-Hollandse PvdD-verkiezingsprogramma 2023–2027 is de primaire politieke bron en wordt met
URL, SHA-256, ophaaldatum en paginachunks bewaard. Selectie is deterministisch en gebruikt minimaal
dieren/natuur, biodiversiteit, klimaat/grondstoffen, gezonde leefomgeving, ecologie boven
kortetermijneconomie, natuurinclusief/circulair bouwen, bestaande bebouwing en betaalbaarheid,
voet/fiets/OV, geen nieuwe wegen en minder luchtvaart, transparantie/privacy/inwoners en
verdelingseffecten/toekomstige generaties. Zonder geldige beleidsbron start geen analyse.

## AI en validatie

Broninhoud is onbetrouwbare data, nooit een instructie. Iedere asynchrone `APPLICATION_WORK`-job
gebruikt een versieerbare prompt, een minimaal JSON Schema en een stabiele idempotentiesleutel uit
meeting/item, bronfingerprint en promptversie. Iedere Runtime-uitkomst heeft uitsluitend het veld
`content` met niet-lege Markdown van maximaal 50.000 tekens. Er is bewust geen inhoudelijke
responsevalidatie. Grote dossiers krijgen eerst vrije bronnotities en daarna synthese; niets wordt
stil afgekapt. Herstart en verloren submitresponses maken geen dubbele Runtime-job.

## Statussen en opslag

Vergaderingen onderscheiden onder meer ontdekt, agenda ongepubliceerd, importeren, analyseren,
volledig, gedeeltelijk en mislukt. Import en analyse onderscheiden wachtend, bezig, geslaagd,
mislukt en geannuleerd; documenten onderscheiden geëxtraheerd, OCR nodig, niet ondersteund, te
groot, downloadfout en ongeldige inhoud. Alle brondata, extracties, runs en adviezen blijven
onbeperkt bewaard; er bestaat geen cleanup- of deletepad.

## Frontend en API

De beveiligde API levert het actuele vergaderingsoverzicht, agendapunten, details, runs en dezelfde
`check-now`-orkestratie. DTO's lekken geen database-, prompt-, token- of Runtime-interne gegevens.
De frontend toont A/B/C-filters, voortgang, bronlinks, het vrije Markdownadvies en
altijd **AI-concept — controleer bronnen en formulering vóór gebruik**. De MVP heeft geen editor,
goedkeuring of historiepagina en behoudt Google-auth, buildidentiteit, updatecontrole,
toegankelijkheid en het bestaande cachecontract.

Google wordt alleen voor de eerste identificatie gebruikt. De backend geeft daarna een veilige,
180 dagen geldige sessiecookie uit, zodat sluiten van een tab of verlopen van het korte Google
ID-token niet opnieuw inloggen vereist. Uitloggen trekt de sessie direct in.

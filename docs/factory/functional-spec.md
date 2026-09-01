# Functionele specificatie

Dit document is de compacte ontwikkelvertaling van de normatieve
[microservicespecificatie](../microservice-specificatie.md). Bij verschil gaat die specificatie
voor. De MVP is een read-only Commissie-assistent voor de eerstvolgende vergadering van commissie
Ruimte van Provincie Noord-Holland.

## Gebruikersflow

Na Google-login toont de app de eerstvolgende vergadering, bronstatus, laatste controle en
analysevoortgang. Dezelfde orkestratie draait dagelijks om 05:00 uur in `Europe/Amsterdam` en via
**Nu controleren**. Zonder toekomstige vergadering, bij hetzelfde reeds succesvol verwerkte
bron-ID of bij een ongepubliceerde agenda ontstaan geen documentdownloads en geen AI-jobs. Alleen
een volledig geïmporteerde en geanalyseerde vergadering wordt als laatst succesvol verwerkt
vastgelegd.

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

Ieder inhoudelijk A/B-punt bevat exact:

1. **Waar gaat het over?** — feitelijke samenvatting;
2. **Wat vinden we ervan?** — politieke beoordeling met programmapassages;
3. **Wat kunnen/willen we ermee in de commissie?** — handelingsopties en doel;
4. **Welke punten willen we maken en wat willen we van de gedeputeerde?** — concrete verzoeken;
5. **Welke technische vragen gaan we stellen?** — feitelijke vragen over ontbrekende informatie.

Elk onderdeel heeft citaties naar uitsluitend meegegeven vergaderstukken en beleidspassages.

## C-advies

Ieder C-punt bevat een binair besluit **bespreken en verplaatsen naar B**, een korte motivering,
urgentie laag/middel/hoog, het gewenste commissiedoel, bij een positief advies een kernvraag, en
citaties. Bespreken wordt alleen geadviseerd bij politieke meerwaarde; niet ieder C-stuk gaat
automatisch naar B.

## Politiek kader

Het Noord-Hollandse PvdD-verkiezingsprogramma 2023–2027 is de primaire politieke bron en wordt met
URL, SHA-256, ophaaldatum en paginachunks bewaard. Selectie is deterministisch en gebruikt minimaal
dieren/natuur, biodiversiteit, klimaat/grondstoffen, gezonde leefomgeving, ecologie boven
kortetermijneconomie, natuurinclusief/circulair bouwen, bestaande bebouwing en betaalbaarheid,
voet/fiets/OV, geen nieuwe wegen en minder luchtvaart, transparantie/privacy/inwoners en
verdelingseffecten/toekomstige generaties. Zonder geldige beleidsbron start geen analyse.

## AI en validatie

Broninhoud is onbetrouwbare data, nooit een instructie. Iedere asynchrone `APPLICATION_WORK`-job
gebruikt een versieerbare prompt, strikt JSON Schema en een stabiele idempotentiesleutel uit
meeting/item, bronfingerprint en promptversie. De backend valideert vereiste velden, lengtes,
lokale item-ID's, bron-ID's en pagina's. Vrije tekst, onbekende bronnen en gedeeltelijke resultaten
worden niet getoond. Grote dossiers krijgen eerst bronnotities en daarna synthese; niets wordt stil
afgekapt. Herstart en verloren submitresponses maken geen dubbele Runtime-job.

## Statussen en opslag

Vergaderingen onderscheiden onder meer ontdekt, agenda ongepubliceerd, importeren, analyseren,
volledig, gedeeltelijk en mislukt. Import en analyse onderscheiden wachtend, bezig, geslaagd,
mislukt en geannuleerd; documenten onderscheiden geëxtraheerd, OCR nodig, niet ondersteund, te
groot, downloadfout en ongeldige inhoud. Alle brondata, extracties, runs en adviezen blijven
onbeperkt bewaard; er bestaat geen cleanup- of deletepad.

## Frontend en API

De beveiligde API levert het actuele vergaderingsoverzicht, agendapunten, details, runs en dezelfde
`check-now`-orkestratie. DTO's lekken geen database-, prompt-, token- of Runtime-interne gegevens.
De frontend toont A/B/C-filters, voortgang, bronlinks, vijfdelige A/B-details, C-bespreekadvies en
altijd **AI-concept — controleer bronnen en formulering vóór gebruik**. De MVP heeft geen editor,
goedkeuring of historiepagina en behoudt Google-auth, buildidentiteit, updatecontrole,
toegankelijkheid en het bestaande cachecontract.

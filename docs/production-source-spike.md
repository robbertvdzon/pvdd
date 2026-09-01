# Productiebronspike en OCR-besluit

Datum: 1 september 2026

## Uitvoering

De read-only spike is uitgevoerd met:

```bash
cd backend
PVDD_LIVE_SOURCE_SPIKE=true mvn -q -Dtest=LiveSourceSpikeTest test
```

De test gebruikt de productieguard en de openbare iBabs-bron, maar opent geen database en maakt
geen Agent Runtime-job. De normale CI slaat deze live test over.

## Waargenomen vergadering

- bron-ID: `a3de1271-fd63-4e24-8a38-6a6df474ec9d`;
- datum: 14 september 2026, 18:30–22:30 Europe/Amsterdam;
- bron: <https://noordholland.bestuurlijkeinformatie.nl/Agenda/Index/a3de1271-fd63-4e24-8a38-6a6df474ec9d>;
- uitkomst: `AgendaUnpublished`;
- reden: de pagina meldt dat de agenda op 3 september wordt gepubliceerd;
- reeds zichtbaar: vijf inhoudelijke C-items met samen zes documenten.

De parser is tijdens de spike aangepast aan twee daadwerkelijk waargenomen iBabs-details:

1. de vergaderkop staat in `#maincontent`, naast eerdere jaaroverzichten in dezelfde `main`;
2. agenda- en reportdocumentlinks openen een HTML-viewer; de bytes staan op de openbare route
   `/Document/View/{documentId}`.

De expliciete publicatiemelding heeft voorrang op reeds zichtbare C-items. Daardoor start de
productieworkflow vóór 3 september geen documentimport of AI-analyse en wordt dezelfde vergadering
bij een volgende controle opnieuw beoordeeld.

## Documentinventaris

| Stuk | Type | Grootte | Pagina's | Tekens | Status |
|---|---:|---:|---:|---:|---|
| Brief Fietsersbond Bergen | PDF | 146.225 B | 2 | 4.615 | `EXTRACTED` |
| Brief Rijkswaterstaat Houtribdijk | PDF | 123.744 B | 2 | 4.148 | `EXTRACTED` |
| Brief geluidproductieplafonds | PDF | 1.773.130 B | 3 | 5.945 | `EXTRACTED` |
| Ontwerp GPPs | PDF | 235.318 B | 7 | 16.788 | `EXTRACTED` |
| Zienswijze TOP Texel | PDF | 171.382 B | 8 | 23.648 | `EXTRACTED` |
| Zienswijze Heemschut NH | PDF | 534.204 B | 5 | 12.269 | `EXTRACTED` |

De vijf C-items en zes documentkoppelingen komen overeen met de openbare bronpagina. Alle zes
bestanden zijn als PDF herkend en leverden tekst per pagina. Er is geen brondata in een database
geschreven en er is geen AI-opdracht uitgevoerd.

## OCR-besluit

Voor de actuele eerstvolgende vergadering is OCR niet nodig: `OCR_REQUIRED = 0`. OCR wordt daarom
niet aan de MVP toegevoegd. Deze beslissing betekent niet dat scans stilzwijgend mogen worden
overgeslagen. Een toekomstig document zonder extraheerbare tekst blijft `OCR_REQUIRED`, maakt de
import `PARTIAL` en blokkeert de analyse totdat een afzonderlijke OCR-wijziging is geleverd.

# Agent Runtime-aansluiting

PvdD gebruikt Agent Runtime v2 als eigen `APPLICATION_WORK`-tenant. Het bearer-token wordt buiten
de code geconfigureerd en geeft geen repository-, worker- of beheerrechten.

Iedere job heeft `taskType=STRUCTURED_GENERATION` en een expliciete combinatie van `vendorId`,
`model` en `mode`; er is geen modelfallback. Acceptance gebruikt exact `mock`/`mock`/`MOCK`.
Productie gebruikt `anthropic`/`claude-opus-5`/`SUBSCRIPTION` via de interne OpenShift-service.

Acceptance registreert met `PVDD_AGENT_RUNTIME_ACCEPTANCE_FIXTURES_ENABLED=true` vóór elke
nieuwe agendapuntanalyse een exacte v2-fixture met een getypeerd synthetisch antwoord. De daarvoor benodigde
`PVDD_AGENT_RUNTIME_MOCK_CONTROL_TOKEN` is alleen in de acceptance-backend gemount. De consument-
en testcontrolcredential zijn gescheiden. Productie weigert de fixtureflag en testcontrolcredential.
Een nieuwe acceptatierun krijgt een eigen sleutel, zodat een eerdere mislukte Runtime-job na een
schone reset niet opnieuw wordt gebruikt; opnieuw insturen van dezelfde run blijft idempotent.

De volledige prompt wordt als `text/markdown` via de hervatbare upload-API verstuurd en daarna als
`PROMPT`-object aan de job gekoppeld. De kleine jobbody bevat daardoor geen grote bronteksten. De
client ondersteunt create, status, result en cancel. Voor een create wordt eerst op de
idempotentiesleutel gezocht; na een verloren response wordt dezelfde upload/objectreferentie
hergebruikt. Resultaten blijven gevalideerde JSON; eventuele Runtime-artifacts en usagegegevens
zijn beschikbaar in het v2-resultaat. Er komt geen generiek promptveld in de productiefrontend.

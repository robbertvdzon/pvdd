# Lokale configuratie en secrets

Alleen voorbeeldbestanden met lege of aantoonbaar neppe waarden worden gecommit. Werkelijke waarden
staan lokaal in een genegeerd `secrets.env` en in OpenShift uitsluitend als Sealed Secrets.

Benodigde categorieën:

- PostgreSQL-gebruiker en -wachtwoord;
- publieke Google Web OAuth client-ID (geen client secret);
- afzonderlijk PvdD Agent Runtime-token voor acceptance en productie;
- build- en runtimeconfiguratie zoals URLs, provider en model.

Gebruikerssessies hebben geen gedeeld sessiesecret: de backend genereert een willekeurige sleutel,
zet alleen de SHA-256-hash in PostgreSQL en geeft de oorspronkelijke waarde uitsluitend als
`HttpOnly`-cookie aan de browser.

Scripts lezen env-bestanden als data en voeren ze nooit uit met `source`. Tokens worden niet gelogd.

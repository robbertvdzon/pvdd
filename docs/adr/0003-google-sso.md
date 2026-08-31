# ADR 0003 — Google SSO met backendautorisatie

Status: geaccepteerd

## Context

De site is privé voor twee gebruikers. GitHub-login is niet gewenst; bestaande applicaties delen
al een Google Web OAuth-client.

## Besluit

De browser verkrijgt een Google ID-token. De backend valideert RS256/JWKS, issuer, audience,
vervaltijd en `email_verified`, en staat uitsluitend `marchanou@gmail.com` en
`robbertvdzon@gmail.com` toe. Er wordt geen Google client secret gebruikt.

## Gevolgen

Frontendchecks zijn alleen UX; de backend is de autorisatiegrens. Ontbrekende productieconfiguratie
faalt gesloten. De publieke origin moet eenmalig in Google Cloud worden geregistreerd.

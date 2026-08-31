# Agent Runtime-aansluiting

PvdD gebruikt een eigen `APPLICATION_WORK`-tenant met environmentprefix `PVDD`. Het bearer-token
wordt buiten de code geconfigureerd en geeft geen repository-, worker- of beheerrechten.

Acceptance accepteert alleen provider `MOCKED`; productie weigert `MOCKED` en gebruikt uitsluitend
expliciet toegestane echte providers/modellen. De technische client ondersteunt create, status,
result en cancel, met idempotentiesleutels en korte HTTP-time-outs. Er komt geen generiek promptveld
in de productiefrontend.

Het actuele protocol wordt afgeleid van de v1-API in de `agent-runtime`-repository; afwijkingen
worden via contracttests bewaakt.

# hkh-370 worklog

- Agendacontrole `0 0 0 * * *` en standpuntenactualisatie `0 30 1 1 * *` (Europe/Amsterdam); constanten `MeetingCheckScheduler.CRON` en `PolicySyncService.MONTHLY_CRON`.
- Teksten in SettingsService, application.properties, docs en frontend-widgettest aangepast; `check-now`, workflowcode en scheduledCheck-route ongewijzigd.
- Tests: `MeetingCheckSchedulerTest` (cron, zone, vóór 02:00, 90 minuten, grep-controle op oude tijden).
- Na uitrol: scheduledCheck-route op acceptatie aanroepen; echte runs 00:00 en 1e 01:30 in de logs controleren.

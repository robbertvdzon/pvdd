import json
import re
import threading
import unittest
import urllib.error
import urllib.request
from datetime import date, datetime
from http.server import ThreadingHTTPServer
from pathlib import Path
from zoneinfo import ZoneInfo

import server

AMSTERDAM = ZoneInfo("Europe/Amsterdam")


class SourceRevisionFixtureTest(unittest.TestCase):
    def test_runtime_advice_fixtures_match_the_current_result_contract(self):
        fixture_dir = Path(__file__).parent / "fixtures" / "runtime"
        advice_files = (
            "ab-green.json",
            "ab-housing.json",
            "ab-mobility.json",
            "c-mobility.json",
            "c-nature.json",
        )

        for name in advice_files:
            with self.subTest(name=name):
                value = json.loads((fixture_dir / name).read_text(encoding="utf-8"))
                self.assertEqual(
                    {"displayTitle", "shortConclusion", "content"},
                    set(value),
                )
                self.assertTrue(all(isinstance(item, str) and item.strip() for item in value.values()))
                self.assertLessEqual(len(value["displayTitle"]), 160)
                self.assertLessEqual(len(value["shortConclusion"]), 280)

    def test_preview_update_changes_both_agenda_and_report_metadata(self):
        original = server.agenda_for("preview")
        updated = server.agenda_for("preview-new-info")

        self.assertNotEqual(original, updated)
        self.assertIn(b"Aangevulde brief", updated)
        self.assertIn("preview-new-info", server.SCENARIOS)

    def test_item_moved_reorders_complete_sections_without_changing_categories(self):
        html = server.agenda_for("item-moved").decode("utf-8")
        markers = (
            'id="section-b"',
            'id="item-b-mobility"',
            'id="section-a"',
            'id="item-a-housing"',
            'id="section-c"',
        )

        positions = [html.index(marker) for marker in markers]
        self.assertEqual(sorted(positions), positions)
        self.assertIn("B-agenda Mobiliteit", html[positions[0] : positions[2]])
        self.assertIn("A-agenda Wonen", html[positions[2] : positions[4]])


class MeetingScenarioTest(unittest.TestCase):
    """De scenarioroute onder /api/scenarios tegen een echte lokale mockserver."""

    @classmethod
    def setUpClass(cls):
        cls.http = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        cls.base = f"http://127.0.0.1:{cls.http.server_port}"
        threading.Thread(target=cls.http.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.http.shutdown()
        cls.http.server_close()

    def setUp(self):
        self.call("POST", "/api/scenarios/reset")

    def call(self, method, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base + path, data=data, method=method)
        try:
            with urllib.request.urlopen(request) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()

    def select(self, name):
        status, body = self.call("POST", f"/api/scenarios/{name}")
        self.assertEqual(200, status, body)
        return json.loads(body)

    def year_dates(self):
        _, html = self.call("GET", "/Agenda/RetrieveAgendasForYear")
        return re.findall(r'href="(/Agenda/Index/[^"]+)">([^<]+)<', html.decode())

    def test_default_scenario_is_the_single_meeting_of_31_december_2035(self):
        self.assertEqual("een-vergadering", json.loads(self.call("GET", "/api/scenarios")[1])["scenario"])
        self.assertEqual(
            [("/Agenda/Index/acceptance-meeting-v1", "maandag 31 december 2035")], self.year_dates()
        )
        status, agenda = self.call("GET", "/Agenda/Index/acceptance-meeting-v1")
        self.assertEqual(200, status)
        self.assertIn(b"maandag 31 december 2035", agenda)

    def test_every_scenario_returns_its_fixed_meetings_relative_to_the_selection_moment(self):
        expected = {
            "twee-vergaderingen": [0, 3],
            "meer-dan-drie": [0, 2, 5, 8],
            "grens-dag-10-en-11": [0, 10, 11],
            "extra-agenda-niet-gepubliceerd": [0, 3],
            "een-vergadering-mislukt": [0, 2, 4],
        }
        today = server.amsterdam_today()
        for name, offsets in expected.items():
            with self.subTest(name=name):
                overview = self.select(name)
                dates = [date.fromisoformat(m["date"]) for m in overview["meetings"]]
                first = dates[0]
                self.assertGreater(first, today)
                self.assertEqual(offsets, [(d - first).days for d in dates])
                self.assertEqual(len(offsets), len(self.year_dates()))

    def test_no_future_meeting_is_empty_without_the_legacy_fallback(self):
        self.select("twee-vergaderingen")
        overview = self.select("geen-toekomstige-vergadering")
        self.assertEqual([], overview["meetings"])
        self.assertEqual([], self.year_dates())
        self.assertEqual(404, self.call("GET", "/Agenda/Index/acceptance-meeting-v1")[0])
        self.assertEqual([], json.loads(self.call("GET", "/api/scenarios")[1])["meetings"])

    def test_whole_source_failure_fails_year_discovery_without_selecting_a_fake_meeting(self):
        self.select("twee-vergaderingen")
        overview = self.select("gehele-broncontrole-mislukt")
        self.assertEqual([], overview["meetings"])
        for year in (2026, 2027):
            status, body = self.call("GET", f"/Agenda/RetrieveAgendasForYear?year={year}")
            self.assertEqual(503, status)
            self.assertEqual("synthetic_source_failure", json.loads(body)["error"])
        self.assertEqual(200, self.call("GET", "/api/scenarios")[0])
        self.select("twee-vergaderingen")
        self.assertEqual(2, len(self.year_dates()))

    def test_reset_returns_to_the_single_default_meeting(self):
        self.select("meer-dan-drie")
        status, body = self.call("POST", "/api/scenarios/reset")
        self.assertEqual(200, status)
        self.assertEqual("een-vergadering", json.loads(body)["scenario"])
        self.assertEqual(1, len(self.year_dates()))

    def test_unknown_scenario_is_a_client_error_and_keeps_the_current_one(self):
        self.select("twee-vergaderingen")
        status, body = self.call("POST", "/api/scenarios/bestaat-niet")
        self.assertEqual(400, status)
        self.assertIn("twee-vergaderingen", json.loads(body)["allowed"])
        self.assertEqual("twee-vergaderingen", json.loads(self.call("GET", "/api/scenarios")[1])["scenario"])
        self.assertEqual(2, len(self.year_dates()))

    def test_put_selects_a_scenario_too(self):
        status, _ = self.call("PUT", "/api/scenarios/twee-vergaderingen")
        self.assertEqual(200, status)
        self.assertEqual(2, len(self.year_dates()))

    def test_unpublished_scenario_serves_the_prepublished_agenda_for_the_second_meeting(self):
        overview = self.select("extra-agenda-niet-gepubliceerd")
        first, second = (m["id"] for m in overview["meetings"])
        self.assertIn(b"housing", self.call("GET", f"/Agenda/Index/{first}")[1].lower())
        agenda = self.call("GET", f"/Agenda/Index/{second}")[1]
        self.assertIn(b"later gepubliceerd", agenda)

    def test_failing_scenario_gives_http_500_on_the_second_agenda_only(self):
        ids = [m["id"] for m in self.select("een-vergadering-mislukt")["meetings"]]
        self.assertEqual([200, 500, 200], [self.call("GET", f"/Agenda/Index/{i}")[0] for i in ids])

    def test_agenda_fetches_are_counted_per_meeting(self):
        ids = [m["id"] for m in self.select("meer-dan-drie")["meetings"]]
        self.call("GET", f"/Agenda/Index/{ids[0]}")
        self.call("GET", f"/Agenda/Index/{ids[0]}")
        self.call("GET", f"/Agenda/Index/{ids[1]}")
        fetches = json.loads(self.call("GET", "/api/scenarios")[1])["agendaFetches"]
        self.assertEqual({ids[0]: 2, ids[1]: 1}, fetches)
        self.assertNotIn(ids[3], fetches)
        self.select("meer-dan-drie")
        self.assertEqual({}, json.loads(self.call("GET", "/api/scenarios")[1])["agendaFetches"])

    def test_documents_are_unique_per_meeting_and_change_only_when_asked(self):
        ids = [m["id"] for m in self.select("twee-vergaderingen")["meetings"]]
        agenda = self.call("GET", f"/Agenda/Index/{ids[0]}")[1].decode()
        self.assertIn(f'data-document-id="{ids[0]}-doc-housing"', agenda)
        first, second = f"{ids[0]}-doc-housing", f"{ids[1]}-doc-housing"
        before = self.call("GET", f"/Document/View/{first}")[1]
        other = self.call("GET", f"/Document/View/{second}")[1]
        self.assertEqual(before, self.call("GET", f"/Document/View/{first}")[1])

        status, _ = self.call("PUT", f"/api/scenarios/documents/{first}")
        self.assertEqual(200, status)
        self.assertNotEqual(before, self.call("GET", f"/Document/View/{first}")[1])
        self.assertEqual(other, self.call("GET", f"/Document/View/{second}")[1])

        self.call("PUT", f"/api/scenarios/documents/{first}", {"version": 1})
        self.assertEqual(before, self.call("GET", f"/Document/View/{first}")[1])

    def test_document_change_rejects_unknown_documents_and_invalid_versions(self):
        ids = [m["id"] for m in self.select("twee-vergaderingen")["meetings"]]
        self.assertEqual(404, self.call("PUT", "/api/scenarios/documents/doc-onbekend")[0])
        self.assertEqual(400, self.call("PUT", f"/api/scenarios/documents/{ids[0]}-doc-housing", {"version": 0})[0])
        self.assertEqual(400, self.call("PUT", f"/api/scenarios/documents/{ids[0]}-doc-housing", {"version": "x"})[0])

    def test_dates_around_the_end_of_summer_time_stay_calendar_days(self):
        # Laatste zondag van oktober 2026 is 25 oktober; D = 21 oktober, D+10 = 31 oktober, D+11 = 1 november.
        now = datetime(2026, 10, 20, 12, 0, tzinfo=AMSTERDAM)
        meetings = server.build_meetings("grens-dag-10-en-11", 1, now)
        self.assertEqual([date(2026, 10, 21), date(2026, 10, 31), date(2026, 11, 1)], [m["date"] for m in meetings])
        self.assertEqual("zaterdag 31 oktober 2026", server.dutch_date(meetings[1]["date"]))

    def test_amsterdam_day_is_used_just_after_local_midnight(self):
        # 23:30 UTC op 20 oktober is 01:30 lokale tijd op 21 oktober.
        now = datetime(2026, 10, 20, 23, 30, tzinfo=ZoneInfo("UTC"))
        self.assertEqual(date(2026, 10, 21), server.amsterdam_today(now))

    def test_scenario_route_is_absent_when_disabled(self):
        import os

        os.environ["MOCK_SCENARIO_ROUTES"] = "disabled"
        try:
            self.assertEqual(404, self.call("POST", "/api/scenarios/twee-vergaderingen")[0])
            self.assertEqual(404, self.call("GET", "/api/scenarios")[0])
        finally:
            del os.environ["MOCK_SCENARIO_ROUTES"]


RUIMTE_BASELINE_SHA256 = {
    "/Agenda/RetrieveAgendasForYear?agendatypeId=1100617069&year=2026": "0c73bd5678200a44ce472b3ca6d748acea9be32f26a4077275a51a7362413ed6",
    "/Agenda/RetrieveAgendasForYear": "0c73bd5678200a44ce472b3ca6d748acea9be32f26a4077275a51a7362413ed6",
    "/Agenda/Index/acceptance-meeting-v1": "950f033fb1fff305f4b7d726b3e383a95ec9573b951d3a8596a372e5ba792823",
    "/Document/View/doc-housing": "717fe7282766328db66402411a973c35f6c6fb6bee326350039248f740ea63c4",
    "/Reports/Item/report-nature": "695fca443f6eebd6886bc17ed1cf52fdfff56d440cf67425ca990b557949b3e7",
}
BESTUUR_YEAR = "/Agenda/RetrieveAgendasForYear?agendatypeId=1100617071&year=2026"
BESTUUR_AGENDA = "/Agenda/Index/bestuur-meeting-20261130"


class BestuurScenarioTest(MeetingScenarioTest):
    """De synthetische Bestuur-reeks naast de ongewijzigde Ruimte-reeks (erft de Ruimte-tests als regressiecontrole)."""

    def agenda(self):
        return self.call("GET", BESTUUR_AGENDA)

    def test_bestuur_type_is_the_documented_source_type_and_matches_the_acceptance_configuration(self):
        documented = (Path(__file__).resolve().parent.parent / "docs" / "commissie-bestuur-bron.md").read_text(encoding="utf-8")
        self.assertIn("| Bestuur   | Bestuur      | `1100617071`   |", documented)
        self.assertEqual("1100617071", server.BESTUUR_AGENDA_TYPE_ID)
        configmap = (Path(__file__).resolve().parent.parent / "deploy" / "overlays" / "acceptance" / "configmap.yaml").read_text()
        self.assertIn('PVDD_MEETING_BESTUUR_AGENDA_TYPE_ID: "1100617071"', configmap)

    def test_year_list_for_bestuur_has_exactly_the_meeting_of_30_november_2026(self):
        status, html = self.call("GET", BESTUUR_YEAR)
        self.assertEqual(200, status)
        self.assertEqual(
            [("/Agenda/Index/bestuur-meeting-20261130", "maandag 30 november 2026")],
            re.findall(r'href="([^"]+)">([^<]+)<', html.decode()),
        )
        self.assertNotIn(b"href=", self.call("GET", BESTUUR_YEAR.replace("year=2026", "year=2027"))[1])

    def test_meeting_runs_13_to_17_amsterdam_time_which_is_12_to_16_utc(self):
        html = self.agenda()[1].decode()
        self.assertIn('<div class="heading3">13:00 - 17:00</div>', html)
        self.assertIn("<h1>Commissie Bestuur</h1>", html)
        start = datetime(2026, 11, 30, 13, 0, tzinfo=AMSTERDAM).astimezone(ZoneInfo("UTC"))
        end = datetime(2026, 11, 30, 17, 0, tzinfo=AMSTERDAM).astimezone(ZoneInfo("UTC"))
        self.assertEqual((12, 16), (start.hour, end.hour))
        self.assertEqual("maandag 30 november 2026", server.dutch_date(date(2026, 11, 30)))

    def test_default_is_the_visible_meeting_without_a_published_agenda(self):
        status, html = self.agenda()
        self.assertEqual(200, status)
        self.assertIn(b"later gepubliceerd", html)
        self.assertIn(b'<div id="agendaitems"><ol></ol></div>', html)
        self.assertNotIn(b"agenda-item", html)

    def test_published_scenario_has_a_b_c_one_item_without_document_and_readable_documents(self):
        self.select("bestuur-gepubliceerd")
        html = self.agenda()[1].decode()
        for marker in ("A-agenda Bestuur", "B-agenda Bestuur", "C-agenda ingekomen stukken"):
            self.assertIn(marker, html)
        a2 = re.search(r'id="bestuur-item-a2".*?</div></div></li>', html, re.S).group(0)
        self.assertNotIn("data-document-id", a2)
        self.assertIn('data-document-id="bestuur-doc-a1"', html)
        self.assertIn('data-document-id="bestuur-doc-b1"', html)
        self.assertIn('data-entry-id="bestuur-report-c1"', html)
        for path in ("/Document/View/bestuur-doc-a1", "/Document/View/bestuur-doc-b1", "/Document/View/bestuur-doc-c1"):
            self.assertEqual(200, self.call("GET", path)[0], path)
        report = self.call("GET", "/Reports/Item/bestuur-report-c1")[1]
        self.assertIn(b"bestuur-doc-c1", report)

    def test_changed_document_changes_only_that_document_and_only_when_asked(self):
        self.select("bestuur-gewijzigd-stuk")
        first = self.call("GET", "/Document/View/bestuur-doc-a1")[1]
        other = self.call("GET", "/Document/View/bestuur-doc-b1")[1]
        agenda = self.agenda()[1]
        self.assertEqual(first, self.call("GET", "/Document/View/bestuur-doc-a1")[1])
        self.assertEqual(200, self.call("PUT", "/api/scenarios/documents/bestuur-doc-a1")[0])
        self.assertNotEqual(first, self.call("GET", "/Document/View/bestuur-doc-a1")[1])
        self.assertEqual(other, self.call("GET", "/Document/View/bestuur-doc-b1")[1])
        self.assertEqual(agenda, self.agenda()[1])
        self.assertEqual(200, self.call("PUT", "/api/scenarios/documents/bestuur-doc-a1", {"version": 1})[0])
        self.assertEqual(first, self.call("GET", "/Document/View/bestuur-doc-a1")[1])
        # opnieuw kiezen begint weer bij versie 1
        self.call("PUT", "/api/scenarios/documents/bestuur-doc-a1")
        self.select("bestuur-gewijzigd-stuk")
        self.assertEqual(first, self.call("GET", "/Document/View/bestuur-doc-a1")[1])

    def test_source_failure_hits_only_the_bestuur_type(self):
        self.select("bestuur-bronfout")
        self.assertEqual(503, self.call("GET", BESTUUR_YEAR)[0])
        self.assertEqual(500, self.agenda()[0])
        ruimte = self.call("GET", "/Agenda/RetrieveAgendasForYear?agendatypeId=1100617069&year=2026")
        self.assertEqual(200, ruimte[0])
        self.assertEqual(200, self.call("GET", "/Agenda/Index/acceptance-meeting-v1")[0])

    def test_bestuur_selection_does_not_touch_the_ruimte_scenario(self):
        self.select("twee-vergaderingen")
        before = self.year_dates()
        overview = self.select("bestuur-gepubliceerd")
        self.assertEqual("twee-vergaderingen", overview["scenario"])
        self.assertEqual("bestuur-gepubliceerd", overview["bestuur"]["scenario"])
        self.assertEqual(before, self.year_dates())

    def test_reset_returns_bestuur_to_its_unpublished_default(self):
        self.select("bestuur-bronfout")
        overview = json.loads(self.call("POST", "/api/scenarios/reset")[1])
        self.assertEqual("bestuur-niet-gepubliceerd", overview["bestuur"]["scenario"])
        self.assertEqual(200, self.call("GET", BESTUUR_YEAR)[0])

    def test_unknown_scenario_is_rejected_as_before_and_lists_the_bestuur_names(self):
        status, body = self.call("POST", "/api/scenarios/bestuur-bestaat-niet")
        self.assertEqual(400, status)
        value = json.loads(body)
        self.assertEqual("unknown_scenario", value["error"])
        self.assertIn("twee-vergaderingen", value["allowed"])
        self.assertEqual(sorted(server.BESTUUR_SCENARIOS), value["allowedBestuur"])
        self.assertEqual(
            {"bestuur-niet-gepubliceerd", "bestuur-gepubliceerd", "bestuur-gewijzigd-stuk", "bestuur-bronfout"},
            server.BESTUUR_SCENARIOS,
        )

    def test_ruimte_responses_are_byte_identical_to_the_baseline_before_the_bestuur_series(self):
        import hashlib

        for path, expected in RUIMTE_BASELINE_SHA256.items():
            with self.subTest(path=path):
                self.assertEqual(expected, hashlib.sha256(self.call("GET", path)[1]).hexdigest())
        self.select("bestuur-gepubliceerd")
        for path, expected in RUIMTE_BASELINE_SHA256.items():
            with self.subTest(path=path, bestuur="gepubliceerd"):
                self.assertEqual(expected, hashlib.sha256(self.call("GET", path)[1]).hexdigest())

    def test_all_bestuur_fixtures_are_synthetic(self):
        texts = [self.agenda()[1].decode()]
        self.select("bestuur-gepubliceerd")
        texts.append(self.agenda()[1].decode())
        texts.append(self.call("GET", "/Reports/Item/bestuur-report-c1")[1].decode())
        for name in server.BESTUUR_DOCUMENT_FILES.values():
            texts.append((Path(server.FIXTURES) / name).read_text(encoding="utf-8"))
        self.assertTrue(all(("ynthetisch" in text or "later gepubliceerd" in text) for text in texts))
        for text in texts:
            self.assertNotRegex(text.lower(), r"bestuurlijkeinformatie|noordholland\.nl|@[a-z0-9-]+\.[a-z]{2,}|\bmevrouw\b|\bdhr\b")
        for name in server.BESTUUR_DOCUMENT_FILES.values():
            self.assertIn("volledig verzonnen", (Path(server.FIXTURES) / name).read_text(encoding="utf-8"))


class ProductionConfigurationTest(unittest.TestCase):
    """Productie heeft geen mock en geen scenarioroute."""

    root = Path(__file__).resolve().parent.parent

    def test_production_overlay_contains_no_mock_or_scenario_route(self):
        files = sorted((self.root / "deploy" / "overlays" / "production").glob("*.yaml"))
        self.assertTrue(files)
        for path in files:
            text = path.read_text(encoding="utf-8")
            for forbidden in ("meeting-source-mock", "MOCKED", "mock-model", "api/scenarios", "MOCK_SCENARIO_ROUTES"):
                self.assertNotIn(forbidden, text, f"{path.name} bevat {forbidden}")

    def test_production_overlay_does_not_enable_acceptance_environment(self):
        for path in (self.root / "deploy" / "overlays" / "production").glob("*.yaml"):
            text = path.read_text(encoding="utf-8")
            self.assertNotRegex(text, r"PVDD_ENVIRONMENT:\s*[\"']?acceptance", path.name)

    def test_acceptance_overlay_is_the_only_one_with_the_mock(self):
        acceptance = (self.root / "deploy" / "overlays" / "acceptance" / "kustomization.yaml").read_text()
        production = (self.root / "deploy" / "overlays" / "production" / "kustomization.yaml").read_text()
        self.assertIn("mock.yaml", acceptance)
        self.assertNotIn("mock", production)

    def test_bestuur_agenda_type_is_configured_in_acceptance_and_production(self):
        for overlay in ("acceptance", "production"):
            text = (self.root / "deploy" / "overlays" / overlay / "configmap.yaml").read_text(encoding="utf-8")
            self.assertIn('PVDD_MEETING_BESTUUR_AGENDA_TYPE_ID: "1100617071"', text, overlay)
        for path in (self.root / "deploy" / "overlays" / "production").glob("*.yaml"):
            self.assertNotIn("bestuur-", path.read_text(encoding="utf-8"), path.name)

    def test_bestuur_scenarios_use_the_existing_scenario_route_only(self):
        for path in (self.root / "backend" / "src" / "main").rglob("*.kt"):
            self.assertNotIn("bestuur-gepubliceerd", path.read_text(encoding="utf-8"), path.name)

    def test_acceptance_scenario_script_passes_against_the_local_mock_and_stub(self):
        import importlib.util

        spec = importlib.util.spec_from_file_location("bestuur_script", self.root / "tools" / "acceptance-bestuur-scenarios.py")
        script = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(script)
        self.assertEqual([], script.self_test())

    def test_backend_scenario_route_exists_only_for_acceptance(self):
        for path in (self.root / "backend" / "src" / "main").rglob("*.kt"):
            text = path.read_text(encoding="utf-8")
            if "api/scenarios" in text:
                self.assertEqual("AcceptanceScenarioController.kt", path.name)
                self.assertIn('havingValue = "acceptance"', text)


if __name__ == "__main__":
    unittest.main()

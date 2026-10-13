#!/usr/bin/env python3
"""Stateful, synthetic-only source server for the OpenShift acceptance environment."""

import json
import mimetypes
import os
import re
from datetime import date, datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Lock
from urllib.parse import parse_qs, urlsplit
from zoneinfo import ZoneInfo

FIXTURES = Path(__file__).parent / "fixtures"
SCENARIOS = {
    "preview",
    "preview-new-info",
    "published",
    "item-added",
    "item-withdrawn",
    "item-moved",
    "category-changed",
    "metadata-changed",
    "document-added",
    "document-removed",
    "same-url-new-bytes",
    "formatting-only",
}
state = {"scenario": "published"}
state_lock = Lock()

# Meerdere-vergaderingenscenario's (/api/scenarios). Los van de bronrevisiescenario's hierboven.
# Elke regel is (dagen na D, agenda gepubliceerd, agenda geeft bronfout). D is de eerstvolgende
# kalenderdag in Europe/Amsterdam, zodat startsAt altijd na het selectiemoment ligt.
DEFAULT_MEETING_SCENARIO = "een-vergadering"
MEETING_SCENARIOS = {
    "twee-vergaderingen": [(0, True, False), (3, True, False)],
    "meer-dan-drie": [(0, True, False), (2, True, False), (5, True, False), (8, True, False)],
    "grens-dag-10-en-11": [(0, True, False), (10, True, False), (11, True, False)],
    "extra-agenda-niet-gepubliceerd": [(0, True, False), (3, False, False)],
    "een-vergadering-mislukt": [(0, True, False), (2, True, True), (4, True, False)],
    "geen-toekomstige-vergadering": [],
    "gehele-broncontrole-mislukt": [],
    DEFAULT_MEETING_SCENARIO: [],
}
AMSTERDAM = "Europe/Amsterdam"
LEGACY_MEETING_ID = "acceptance-meeting-v1"
LEGACY_DATE_TEXT = "maandag 31 december 2035"
DOCUMENT_FILES = {"doc-housing": "doc-housing.txt", "doc-mobility": "doc-mobility.html"}
WEEKDAYS = ["maandag", "dinsdag", "woensdag", "donderdag", "vrijdag", "zaterdag", "zondag"]
MONTHS = ["januari", "februari", "maart", "april", "mei", "juni", "juli", "augustus", "september", "oktober", "november", "december"]
meeting_state = {"scenario": DEFAULT_MEETING_SCENARIO, "selection": 0, "meetings": [], "versions": {}, "fetches": {}}


def fixture(name: str) -> bytes:
    return (FIXTURES / name).read_bytes()


def scenario() -> str:
    with state_lock:
        return state["scenario"]


def agenda_for(active: str) -> bytes:
    if active in {"preview", "preview-new-info"}:
        html = fixture("agenda-unpublished.html")
        if active == "preview-new-info":
            return html.replace(
                b"Brief over herstel van een natuurverbinding",
                b"Aangevulde brief over versneld herstel van een natuurverbinding",
            )
        return html
    html = fixture("agenda.html").decode("utf-8")
    housing = re.search(r'<li><div class="agenda-item" id="item-a-housing">.*?</div></li>', html, re.S)
    mobility = re.search(r'<li><div class="agenda-item" id="item-b-mobility">.*?</div></li>', html, re.S)
    housing_section = re.search(
        r'<li><div class="agenda-item" id="section-a">.*?</div></li>\s*'
        r'<li><div class="agenda-item" id="item-a-housing">.*?</div></li>',
        html,
        re.S,
    )
    mobility_section = re.search(
        r'<li><div class="agenda-item" id="section-b">.*?</div></li>\s*'
        r'<li><div class="agenda-item" id="item-b-mobility">.*?</div></li>',
        html,
        re.S,
    )
    if active == "item-added":
        addition = """<li><div class="agenda-item" id="item-b-green">
          <div class="panel-heading"><span class="panel-id">2.b</span><span class="panel-title-label">Groene provinciale verbinding</span></div>
          <div class="panel-body"><p>Toelichting: een nieuwe natuurverbinding.</p><p>Behandelvoorstel: bespreken.</p></div>
        </div></li>"""
        html = html.replace('<li><div class="agenda-item" id="section-c">', addition + '<li><div class="agenda-item" id="section-c">')
    elif active == "item-withdrawn" and mobility:
        html = html.replace(mobility.group(0), "")
    elif active == "item-moved" and housing_section and mobility_section:
        # Verplaats de twee volledige categoriesecties. De inhoudelijke punten
        # krijgen zo een andere volgorde, terwijl hun A/B-categorie gelijk blijft.
        html = (
            html.replace(housing_section.group(0), "__HOUSING_SECTION__")
            .replace(mobility_section.group(0), housing_section.group(0))
            .replace("__HOUSING_SECTION__", mobility_section.group(0))
        )
    elif active == "category-changed":
        html = html.replace("B-agenda Mobiliteit", "C-agenda Mobiliteit")
    elif active == "metadata-changed":
        html = html.replace("bespreek betaalbaarheid, groen en materiaalgebruik", "vraag een harde toezegging over groen en betaalbaarheid")
    elif active == "document-added":
        html = html.replace("</div>\n      </div>\n    </div></li>", '<a href="/Document/View/doc-extra" data-document-id="doc-extra">Extra bijlage</a></div>\n      </div>\n    </div></li>', 1)
    elif active == "document-removed":
        html = re.sub(r'<div class="list-attachments"><a[^>]+doc-housing[^>]*>.*?</a></div>', "", html, count=1)
    elif active == "formatting-only":
        html = html.replace("<body><main>", "<body>\n\n  <main>\n")
    return html.encode("utf-8")


def scenario_routes_enabled() -> bool:
    """De scenarioroute bestaat alleen in de acceptatieconfiguratie van de mock."""
    return os.environ.get("MOCK_SCENARIO_ROUTES", "enabled").lower() != "disabled"


def dutch_date(day: date) -> str:
    return f"{WEEKDAYS[day.weekday()]} {day.day} {MONTHS[day.month - 1]} {day.year}"


def amsterdam_today(now: datetime | None = None) -> date:
    return (now or datetime.now(ZoneInfo(AMSTERDAM))).astimezone(ZoneInfo(AMSTERDAM)).date()


def build_meetings(name: str, selection: int, now: datetime | None = None) -> list[dict]:
    """De vaste vergaderingenset van een scenario, relatief aan het selectiemoment."""
    first = amsterdam_today(now) + timedelta(days=1)
    return [
        {
            "id": f"scn-{name}-{selection}-{index + 1}",
            "date": first + timedelta(days=offset),
            "offsetDays": offset,
            "published": published,
            "fails": fails,
        }
        for index, (offset, published, fails) in enumerate(MEETING_SCENARIOS[name])
    ]


def select_meeting_scenario(name: str, now: datetime | None = None) -> bool:
    if name not in MEETING_SCENARIOS:
        return False
    with state_lock:
        meeting_state["selection"] += 1
        meeting_state["scenario"] = name
        meeting_state["meetings"] = build_meetings(name, meeting_state["selection"], now)
        meeting_state["versions"] = {}
        meeting_state["fetches"] = {}
    return True


def meeting_by_id(meeting_id: str) -> dict | None:
    with state_lock:
        return next((m for m in meeting_state["meetings"] if m["id"] == meeting_id), None)


def year_page() -> bytes:
    with state_lock:
        meetings = list(meeting_state["meetings"])
        selected = meeting_state["scenario"]
    if selected == DEFAULT_MEETING_SCENARIO:
        return fixture("year.html")
    if not meetings:
        return fixture("no-meeting.html")
    links = "".join(f'<a href="/Agenda/Index/{m["id"]}">{dutch_date(m["date"])}</a>' for m in meetings)
    return f'<!doctype html>\n<html lang="nl"><body>{links}</body></html>'.encode("utf-8")


def scenario_agenda(meeting: dict) -> bytes:
    html = fixture("agenda.html" if meeting["published"] else "agenda-unpublished.html").decode("utf-8")
    # Documenten zijn per vergadering uniek, zodat een wijziging maar één document raakt.
    html = html.replace("documentId=doc-", f"documentId={meeting['id']}-doc-").replace(
        'data-document-id="doc-', f'data-document-id="{meeting["id"]}-doc-'
    )
    return html.replace(LEGACY_DATE_TEXT, dutch_date(meeting["date"])).replace(LEGACY_MEETING_ID, meeting["id"]).encode("utf-8")


def scenario_document(document_id: str) -> tuple[str, str] | None:
    """(bestandsnaam, vergadering) van een scenariodocument `<vergadering>-doc-<naam>`."""
    with state_lock:
        meetings = list(meeting_state["meetings"])
    for meeting in meetings:
        prefix = f"{meeting['id']}-"
        if document_id.startswith(prefix) and document_id.removeprefix(prefix) in DOCUMENT_FILES:
            return DOCUMENT_FILES[document_id.removeprefix(prefix)], meeting["id"]
    return None


def document_body(document_id: str) -> tuple[bytes, str] | None:
    found = scenario_document(document_id)
    if found is None:
        return None
    name, _ = found
    body = fixture(name)
    with state_lock:
        version = meeting_state["versions"].get(document_id, 1)
    if version > 1:
        body += f"\n<!-- versie {version} -->\n".encode("utf-8")
    return body, name


def count_agenda_fetch(meeting_id: str) -> None:
    with state_lock:
        meeting_state["fetches"][meeting_id] = meeting_state["fetches"].get(meeting_id, 0) + 1


def scenario_overview() -> dict:
    with state_lock:
        return {
            "scenario": meeting_state["scenario"],
            "available": sorted(MEETING_SCENARIOS),
            "meetings": [
                {"id": m["id"], "date": m["date"].isoformat(), "published": m["published"], "fails": m["fails"]}
                for m in meeting_state["meetings"]
            ] if meeting_state["scenario"] != DEFAULT_MEETING_SCENARIO
            else [{"id": LEGACY_MEETING_ID, "date": "2035-12-31", "published": True, "fails": False}],
            "documentVersions": dict(meeting_state["versions"]),
            "agendaFetches": dict(meeting_state["fetches"]),
        }


class Handler(BaseHTTPRequestHandler):
    def handle_scenario_route(self, path: str):
        """POST/PUT onder /api/scenarios; 404 wanneer de route in deze configuratie niet bestaat."""
        if not scenario_routes_enabled():
            return self.respond(404, b"not found", "text/plain")
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        parts = path.removeprefix("/api/scenarios").strip("/").split("/")
        if parts == ["reset"]:
            select_meeting_scenario(DEFAULT_MEETING_SCENARIO)
            return self.respond_json(200, scenario_overview())
        if len(parts) == 1 and parts[0]:
            if not select_meeting_scenario(parts[0]):
                return self.respond_json(400, {"error": "unknown_scenario", "allowed": sorted(MEETING_SCENARIOS)})
            return self.respond_json(200, scenario_overview())
        if len(parts) == 2 and parts[0] == "documents":
            document_id = parts[1]
            if scenario_document(document_id) is None:
                return self.respond_json(404, {"error": "unknown_document"})
            try:
                requested = json.loads(raw).get("version") if raw.strip() else None
                if requested is not None and (not isinstance(requested, int) or isinstance(requested, bool) or requested < 1):
                    raise ValueError
            except (ValueError, AttributeError):
                return self.respond_json(400, {"error": "invalid_version"})
            with state_lock:
                current = meeting_state["versions"].get(document_id, 1)
                meeting_state["versions"][document_id] = requested if requested is not None else current + 1
            return self.respond_json(200, scenario_overview())
        return self.respond(404, b"not found", "text/plain")

    def do_PUT(self):
        path = urlsplit(self.path).path
        if path.startswith("/api/scenarios"):
            return self.handle_scenario_route(path)
        return self.respond(404, b"not found", "text/plain")

    def do_POST(self):
        path = urlsplit(self.path).path
        if path.startswith("/api/scenarios"):
            return self.handle_scenario_route(path)
        prefix = "/fixtures/control/"
        if not path.startswith(prefix):
            return self.respond(404, b"not found", "text/plain")
        requested = path.removeprefix(prefix)
        if requested not in SCENARIOS:
            return self.respond(400, json.dumps({"allowed": sorted(SCENARIOS)}).encode(), "application/json")
        with state_lock:
            state["scenario"] = requested
        return self.respond(200, json.dumps({"scenario": requested}).encode(), "application/json")

    def do_GET(self):
        path = urlsplit(self.path).path
        active = scenario()
        if path in ("/health/live", "/health/ready"):
            return self.respond(200, b'{"status":"UP"}', "application/json")
        if path == "/fixtures/control":
            return self.respond(200, json.dumps({"scenario": active}).encode(), "application/json")
        if path == "/api/scenarios" and scenario_routes_enabled():
            return self.respond_json(200, scenario_overview())
        if path == "/Agenda/RetrieveAgendasForYear":
            with state_lock:
                source_failure = meeting_state["scenario"] == "gehele-broncontrole-mislukt"
            if source_failure:
                return self.respond_json(503, {"error": "synthetic_source_failure"})
            return self.respond(200, year_page(), "text/html; charset=utf-8")
        if path.startswith("/Agenda/Index/"):
            meeting_id = path.removeprefix("/Agenda/Index/")
            count_agenda_fetch(meeting_id)
            with state_lock:
                default_meeting = meeting_state["scenario"] == DEFAULT_MEETING_SCENARIO
            if meeting_id == LEGACY_MEETING_ID and default_meeting:
                return self.respond(200, agenda_for(active), "text/html; charset=utf-8")
            meeting = meeting_by_id(meeting_id)
            if meeting is None:
                return self.respond(404, b"not found", "text/plain")
            if meeting["fails"]:
                return self.respond(500, b'{"error":"synthetic_source_failure"}', "application/json")
            return self.respond(200, scenario_agenda(meeting), "text/html; charset=utf-8")
        if path == "/Reports/Item/report-nature":
            report = fixture("report-nature.html")
            if active == "preview-new-info":
                report = report.replace(
                    b"De brief vraagt om snelle verbinding van versnipperde leefgebieden.",
                    b"De aangevulde brief vraagt om versneld herstel en een concreet uitvoeringsschema.",
                )
            return self.respond(200, report, "text/html; charset=utf-8")
        documents = {
            "/Document/View/doc-housing": "doc-housing-changed.txt" if active == "same-url-new-bytes" else "doc-housing.txt",
            "/Document/View/doc-mobility": "doc-mobility.html",
            "/Document/View/doc-nature": "doc-nature.txt",
            "/Document/View/doc-extra": "doc-nature.txt",
        }
        scenario_doc = document_body(path.removeprefix("/Document/View/")) if path.startswith("/Document/View/") else None
        if scenario_doc is not None:
            return self.respond(200, scenario_doc[0], mimetypes.guess_type(scenario_doc[1])[0] or "text/plain")
        if path in documents:
            return self.respond(200, fixture(documents[path]), mimetypes.guess_type(documents[path])[0] or "text/plain")
        if path == "/fixtures/scenarios/source-error":
            return self.respond(503, b'{"error":"synthetic_source_failure"}', "application/json")
        if path.startswith("/fixtures/"):
            target = (FIXTURES / path.removeprefix("/fixtures/")).resolve()
            if target.is_file() and FIXTURES.resolve() in target.parents:
                return self.respond(200, target.read_bytes(), mimetypes.guess_type(target.name)[0] or "application/octet-stream")
        return self.respond(404, b"not found", "text/plain")

    def respond_json(self, status: int, value: dict):
        return self.respond(status, json.dumps(value).encode("utf-8"), "application/json")

    def respond(self, status: int, body: bytes, content_type: str):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, message, *args):
        print("source-mock", message % args, flush=True)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", int(os.environ.get("PORT", "8080"))), Handler).serve_forever()

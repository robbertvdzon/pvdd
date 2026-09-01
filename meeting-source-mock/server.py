#!/usr/bin/env python3
"""Stateful, synthetic-only source server for the OpenShift acceptance environment."""

import json
import mimetypes
import os
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Lock
from urllib.parse import urlsplit

FIXTURES = Path(__file__).parent / "fixtures"
SCENARIOS = {
    "preview",
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


def fixture(name: str) -> bytes:
    return (FIXTURES / name).read_bytes()


def scenario() -> str:
    with state_lock:
        return state["scenario"]


def agenda_for(active: str) -> bytes:
    if active == "preview":
        return fixture("agenda-unpublished.html")
    html = fixture("agenda.html").decode("utf-8")
    housing = re.search(r'<li><div class="agenda-item" id="item-a-housing">.*?</div></li>', html, re.S)
    mobility = re.search(r'<li><div class="agenda-item" id="item-b-mobility">.*?</div></li>', html, re.S)
    if active == "item-added":
        addition = """<li><div class="agenda-item" id="item-b-green">
          <div class="panel-heading"><span class="panel-id">2.b</span><span class="panel-title-label">Groene provinciale verbinding</span></div>
          <div class="panel-body"><p>Toelichting: een nieuwe natuurverbinding.</p><p>Behandelvoorstel: bespreken.</p></div>
        </div></li>"""
        html = html.replace('<li><div class="agenda-item" id="section-c">', addition + '<li><div class="agenda-item" id="section-c">')
    elif active == "item-withdrawn" and mobility:
        html = html.replace(mobility.group(0), "")
    elif active == "item-moved" and housing and mobility:
        html = html.replace(housing.group(0), "__HOUSING__").replace(mobility.group(0), housing.group(0)).replace("__HOUSING__", mobility.group(0))
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


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        path = urlsplit(self.path).path
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
        if path == "/Agenda/RetrieveAgendasForYear":
            return self.respond(200, fixture("year.html"), "text/html; charset=utf-8")
        if path == "/Agenda/Index/acceptance-meeting-v1":
            return self.respond(200, agenda_for(active), "text/html; charset=utf-8")
        if path == "/Reports/Item/report-nature":
            return self.respond(200, fixture("report-nature.html"), "text/html; charset=utf-8")
        documents = {
            "/Document/View/doc-housing": "doc-housing-changed.txt" if active == "same-url-new-bytes" else "doc-housing.txt",
            "/Document/View/doc-mobility": "doc-mobility.html",
            "/Document/View/doc-nature": "doc-nature.txt",
            "/Document/View/doc-extra": "doc-nature.txt",
        }
        if path in documents:
            return self.respond(200, fixture(documents[path]), mimetypes.guess_type(documents[path])[0] or "text/plain")
        if path == "/fixtures/scenarios/source-error":
            return self.respond(503, b'{"error":"synthetic_source_failure"}', "application/json")
        if path.startswith("/fixtures/"):
            target = (FIXTURES / path.removeprefix("/fixtures/")).resolve()
            if target.is_file() and FIXTURES.resolve() in target.parents:
                return self.respond(200, target.read_bytes(), mimetypes.guess_type(target.name)[0] or "application/octet-stream")
        return self.respond(404, b"not found", "text/plain")

    def respond(self, status: int, body: bytes, content_type: str):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, message, *args):
        print("source-mock", message % args, flush=True)


ThreadingHTTPServer(("0.0.0.0", int(os.environ.get("PORT", "8080"))), Handler).serve_forever()

#!/usr/bin/env python3
"""Uitvoerbaar acceptatiescenario voor de synthetische Bestuur-reeks.

Per scenario: kies het scenario via de scenariobediening, roep "Nu controleren" aan
(POST /api/meetings/check-now) en vergelijk de uitkomst van beide commissies met de verwachting
uit docs/acceptatiemock-bestuur-scenarios.md. Ruimte moet in elk scenario normaal antwoorden.

Gebruik:
  python3 tools/acceptance-bestuur-scenarios.py --self-test
      Start de mock lokaal plus een stub-backend (geen database, geen credentials). Dit is het bewijs vóór merge.
  python3 tools/acceptance-bestuur-scenarios.py --backend-url https://<acceptatie-origin>
      Draait tegen een echte acceptatie-omgeving. Sessie en CSRF komen uit de omgeving, nooit uit argumenten
      en nooit in de uitvoer: PVDD_SESSION_COOKIE (waarde van de sessiecookie, als 'naam=waarde')
      en PVDD_CSRF_TOKEN (waarde van cookie pvdd_csrf; wordt ook als header X-CSRF-Token gestuurd).
"""
import argparse
import hashlib
import json
import os
import sys
import threading
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BESTUUR_TYPE = "1100617071"
RUIMTE_TYPE = "1100617069"
CHANGED_DOCUMENT = "bestuur-doc-a1"


class Client:
    def __init__(self, base, cookie=None, csrf=None):
        self.base, self.cookie, self.csrf = base.rstrip("/"), cookie, csrf

    def call(self, method, path, body=None):
        headers = {"Accept": "application/json"}
        if method != "GET":
            headers["Idempotency-Key"] = f"bestuur-scn-{uuid.uuid4()}"
            if self.csrf:
                headers["X-CSRF-Token"] = self.csrf
        if self.cookie:
            cookie = self.cookie if not self.csrf else f"{self.cookie}; pvdd_csrf={self.csrf}"
            headers["Cookie"] = cookie
        data = None if body is None else json.dumps(body).encode()
        if data:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.status, json.loads(response.read() or b"{}")
        except urllib.error.HTTPError as error:
            raw = error.read()
            try:
                return error.code, json.loads(raw or b"{}")
            except ValueError:
                return error.code, {}

    def select(self, name):
        status, _ = self.call("POST", f"/api/scenarios/{name}")
        assert status == 200, f"scenario {name} kiezen gaf HTTP {status}"

    def check_now(self):
        status, body = self.call("POST", "/api/meetings/check-now")
        assert status == 200, f"Nu controleren gaf HTTP {status}"
        return {c["commissie"]: c for c in body["commissies"]}


def run(client):
    failures = []

    def expect(label, condition):
        print(("ok   " if condition else "FOUT ") + label)
        if not condition:
            failures.append(label)

    client.call("POST", "/api/scenarios/reset")
    baseline = client.check_now()["RUIMTE"]
    expect("baseline: Ruimte slaagt", baseline["success"])

    def ruimte_normal(result, scenario):
        expect(f"{scenario}: Ruimte antwoordt normaal", result["RUIMTE"]["success"]
               and result["RUIMTE"]["meetingSourceId"] == baseline["meetingSourceId"])

    client.select("bestuur-niet-gepubliceerd")
    result = client.check_now()
    expect("bestuur-niet-gepubliceerd: Bestuur slaagt zonder agenda",
           result["BESTUUR"]["success"] and result["BESTUUR"]["status"] == "AGENDA_UNPUBLISHED")
    ruimte_normal(result, "bestuur-niet-gepubliceerd")

    client.select("bestuur-gepubliceerd")
    result = client.check_now()
    expect("bestuur-gepubliceerd: Bestuur geslaagd met agenda",
           result["BESTUUR"]["success"] and result["BESTUUR"]["status"] not in ("AGENDA_UNPUBLISHED", "SOURCE_FAILURE"))
    ruimte_normal(result, "bestuur-gepubliceerd")

    client.select("bestuur-gewijzigd-stuk")
    first = client.check_now()
    expect("bestuur-gewijzigd-stuk: eerste controle importeert", first["BESTUUR"]["success"])
    status, _ = client.call("PUT", f"/api/scenarios/documents/{CHANGED_DOCUMENT}")
    expect("bestuur-gewijzigd-stuk: stuk wijzigen via de bestaande documentroute", status == 200)
    second = client.check_now()
    expect("bestuur-gewijzigd-stuk: tweede controle ziet de wijziging",
           second["BESTUUR"]["success"] and second["BESTUUR"]["status"] != "UNCHANGED")
    third = client.check_now()
    expect("bestuur-gewijzigd-stuk: derde controle zonder wijziging is UNCHANGED", third["BESTUUR"]["status"] == "UNCHANGED")
    ruimte_normal(second, "bestuur-gewijzigd-stuk")

    client.select("bestuur-bronfout")
    result = client.check_now()
    expect("bestuur-bronfout: Bestuur mislukt", not result["BESTUUR"]["success"])
    expect("bestuur-bronfout: Ruimte slaagt", result["RUIMTE"]["success"])
    ruimte_normal(result, "bestuur-bronfout")

    client.call("POST", "/api/scenarios/reset")
    return failures


class StubBackend(BaseHTTPRequestHandler):
    """Minimale backend: stuurt scenario's door naar de mock en vergelijkt per commissie de bronuitkomst."""

    mock = None
    seen = {}
    lock = threading.Lock()

    def _forward(self, method, path, body=None):
        request = urllib.request.Request(self.mock + path, data=body, method=method)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()

    def _reply(self, status, body):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _check(self, commissie, type_id):
        status, year = self._forward("GET", f"/Agenda/RetrieveAgendasForYear?agendatypeId={type_id}&year=2026")
        if status != 200:
            return {"commissie": commissie, "success": False, "status": "SOURCE_FAILURE", "meetingSourceId": None}
        import re
        links = re.findall(rb'href="/Agenda/Index/([^"]+)"', year)
        if not links:
            return {"commissie": commissie, "success": True, "status": "NO_FUTURE_MEETING", "meetingSourceId": None}
        meeting = links[0].decode()
        status, agenda = self._forward("GET", f"/Agenda/Index/{meeting}")
        if status != 200:
            return {"commissie": commissie, "success": False, "status": "SOURCE_FAILURE", "meetingSourceId": meeting}
        if b"later gepubliceerd" in agenda:
            return {"commissie": commissie, "success": True, "status": "AGENDA_UNPUBLISHED", "meetingSourceId": meeting}
        digest = hashlib.sha256(agenda)
        for document in sorted(set(re.findall(rb'data-document-id="([^"]+)"', agenda))):
            digest.update(self._forward("GET", f"/Document/View/{document.decode()}")[1])
        key = digest.hexdigest()
        with self.lock:
            changed = self.seen.get(commissie) != key
            self.seen[commissie] = key
        return {"commissie": commissie, "success": True, "status": "IMPORTED" if changed else "UNCHANGED", "meetingSourceId": meeting}

    def do_GET(self):
        self._reply(*self._forward("GET", self.path)) if self.path.startswith("/api/scenarios") else self._reply(404, {})

    def do_PUT(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        self._reply(*self._forward("PUT", self.path, body)) if self.path.startswith("/api/scenarios") else self._reply(404, {})

    def do_POST(self):
        if self.path == "/api/meetings/check-now":
            return self._reply(200, {"status": "IMPORTED", "commissies": [
                self._check("RUIMTE", RUIMTE_TYPE), self._check("BESTUUR", BESTUUR_TYPE)]})
        if self.path.startswith("/api/scenarios"):
            if self.path == "/api/scenarios/reset":
                with self.lock:
                    self.seen.clear()
            return self._reply(*self._forward("POST", self.path))
        return self._reply(404, {})

    def log_message(self, *args):
        pass


def self_test():
    sys.path.insert(0, str(ROOT / "meeting-source-mock"))
    import server

    mock = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
    server.Handler.log_message = lambda *args: None
    threading.Thread(target=mock.serve_forever, daemon=True).start()
    StubBackend.mock = f"http://127.0.0.1:{mock.server_port}"
    StubBackend.seen = {}
    backend = ThreadingHTTPServer(("127.0.0.1", 0), StubBackend)
    threading.Thread(target=backend.serve_forever, daemon=True).start()
    try:
        return run(Client(f"http://127.0.0.1:{backend.server_port}"))
    finally:
        backend.shutdown()
        mock.shutdown()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--self-test", action="store_true", help="lokale mock en stub-backend")
    parser.add_argument("--backend-url", help="basis-URL van de acceptatiebackend")
    args = parser.parse_args()
    if args.self_test:
        failures = self_test()
    elif args.backend_url:
        failures = run(Client(args.backend_url, os.environ.get("PVDD_SESSION_COOKIE"), os.environ.get("PVDD_CSRF_TOKEN")))
    else:
        parser.error("kies --self-test of --backend-url")
    print("Resultaat:", "alle scenario's groen" if not failures else f"{len(failures)} fout(en)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())

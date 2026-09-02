import json
from pathlib import Path
import unittest

import server


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


if __name__ == "__main__":
    unittest.main()

import unittest

import server


class SourceRevisionFixtureTest(unittest.TestCase):
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

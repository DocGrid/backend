"""Regression tests for the deterministic final performance chart generator."""

from __future__ import annotations

import copy
import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT_PATH = Path(__file__).with_name("generate_final_performance_report.py")
SPEC = importlib.util.spec_from_file_location("final_performance_report", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"생성기 Module을 읽을 수 없습니다: {SCRIPT_PATH}")
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


class FinalPerformanceReportGeneratorTest(unittest.TestCase):
    """Verify data-contract failures and deterministic output behavior."""

    def setUp(self) -> None:
        """Load a fresh copy of the canonical data for every test."""
        self.data = GENERATOR.load_data(GENERATOR.DEFAULT_DATA_PATH)

    def test_canonical_data_and_sources_are_valid(self) -> None:
        """The committed data must satisfy the complete provenance contract."""
        GENERATOR.validate_data(self.data, GENERATOR.REPOSITORY_ROOT)

    def test_every_required_chart_renders_accessible_svg(self) -> None:
        """All required benchmarks must produce titled SVG images."""
        GENERATOR.validate_data(self.data, GENERATOR.REPOSITORY_ROOT)
        rendered = GENERATOR.render_all(self.data)

        self.assertEqual(6, len(rendered))
        for file_name, svg in rendered.items():
            with self.subTest(file_name=file_name):
                self.assertTrue(file_name.endswith(".svg"))
                self.assertIn('role="img"', svg)
                self.assertIn("<title id=\"chart-title\">", svg)
                self.assertIn("<desc id=\"chart-description\">", svg)
                self.assertTrue(svg.endswith("\n"))

    def test_series_length_mismatch_is_rejected(self) -> None:
        """A chart cannot silently omit a category value."""
        invalid = copy.deepcopy(self.data)
        invalid["benchmarks"]["claim"]["series"]["throughput"]["values"].pop()

        with self.assertRaisesRegex(GENERATOR.DataContractError, "category 수와 다릅니다"):
            GENERATOR.validate_data(invalid, GENERATOR.REPOSITORY_ROOT)

    def test_check_detects_a_tampered_svg(self) -> None:
        """Check mode must detect drift between data and committed charts."""
        GENERATOR.validate_data(self.data, GENERATOR.REPOSITORY_ROOT)
        rendered = GENERATOR.render_all(self.data)

        with tempfile.TemporaryDirectory() as directory:
            output_directory = Path(directory)
            GENERATOR.write_outputs(rendered, output_directory)
            GENERATOR.check_outputs(rendered, output_directory)
            first_file = output_directory / sorted(rendered)[0]
            first_file.write_text("tampered", encoding="utf-8")

            with self.assertRaisesRegex(GENERATOR.DataContractError, "불일치"):
                GENERATOR.check_outputs(rendered, output_directory)


if __name__ == "__main__":
    unittest.main()

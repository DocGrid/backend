#!/usr/bin/env python3
"""Validate the consolidated performance data and render deterministic SVG charts."""

from __future__ import annotations

import argparse
import html
import json
import math
import sys
from pathlib import Path
from typing import Any


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DATA_PATH = (
    REPOSITORY_ROOT
    / "docs/test-results/gimin-#145-final-performance-report-data.json"
)
DEFAULT_OUTPUT_DIRECTORY = (
    REPOSITORY_ROOT
    / "docs/test-results/assets/final-performance-report"
)
REQUIRED_BENCHMARKS = {
    "claim",
    "bgeBatch",
    "vectorSearch",
    "workerScaling",
    "queueBackpressure",
    "realDocumentE2E",
}
PALETTE = ("#2563eb", "#dc2626", "#059669", "#7c3aed", "#d97706")
CANVAS_WIDTH = 1200
CANVAS_HEIGHT = 680
PLOT_LEFT = 105
PLOT_RIGHT = 1095
PLOT_TOP = 150
PLOT_BOTTOM = 570


class DataContractError(ValueError):
    """Indicate that the consolidated benchmark data violates its public contract."""


def load_data(path: Path) -> dict[str, Any]:
    """Read the canonical JSON data as UTF-8."""
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise DataContractError(f"성능 데이터 파일을 찾을 수 없습니다: {path}") from exc
    except json.JSONDecodeError as exc:
        raise DataContractError(f"성능 데이터 JSON이 올바르지 않습니다: {exc}") from exc


def validate_data(data: dict[str, Any], repository_root: Path) -> None:
    """Validate schema, provenance, numeric values, and chart references."""
    if data.get("schemaVersion") != 1:
        raise DataContractError("schemaVersion은 1이어야 합니다.")

    sources = data.get("sources")
    if not isinstance(sources, list) or not sources:
        raise DataContractError("sources는 비어 있지 않은 배열이어야 합니다.")

    source_ids: set[str] = set()
    for source in sources:
        source_id = _require_non_empty_string(source, "id", "source")
        if source_id in source_ids:
            raise DataContractError(f"중복 source id입니다: {source_id}")
        source_ids.add(source_id)
        source_path = _require_non_empty_string(source, "path", source_id)
        resolved = (repository_root / source_path).resolve()
        if not resolved.is_relative_to(repository_root.resolve()):
            raise DataContractError(f"저장소 밖의 source 경로입니다: {source_path}")
        if not resolved.is_file():
            raise DataContractError(f"source 문서를 찾을 수 없습니다: {source_path}")

    benchmarks = data.get("benchmarks")
    if not isinstance(benchmarks, dict):
        raise DataContractError("benchmarks는 객체여야 합니다.")
    missing = REQUIRED_BENCHMARKS - benchmarks.keys()
    if missing:
        raise DataContractError(f"필수 benchmark가 없습니다: {', '.join(sorted(missing))}")

    output_files: set[str] = set()
    for benchmark_id in sorted(REQUIRED_BENCHMARKS):
        _validate_benchmark(
            benchmark_id,
            benchmarks[benchmark_id],
            source_ids,
            output_files,
        )

    _validate_supporting_baselines(data.get("supportingBaselines"), source_ids)


def _validate_benchmark(
    benchmark_id: str,
    benchmark: dict[str, Any],
    source_ids: set[str],
    output_files: set[str],
) -> None:
    """Validate one chart-backed benchmark."""
    if not isinstance(benchmark, dict):
        raise DataContractError(f"{benchmark_id} benchmark는 객체여야 합니다.")
    _require_non_empty_string(benchmark, "title", benchmark_id)
    _require_non_empty_string(benchmark, "subtitle", benchmark_id)
    _require_non_empty_string(benchmark, "categoryLabel", benchmark_id)

    categories = benchmark.get("categories")
    if (
        not isinstance(categories, list)
        or not categories
        or any(not isinstance(value, str) or not value.strip() for value in categories)
    ):
        raise DataContractError(f"{benchmark_id}.categories가 올바르지 않습니다.")

    referenced_sources = benchmark.get("sourceIds")
    if not isinstance(referenced_sources, list) or not referenced_sources:
        raise DataContractError(f"{benchmark_id}.sourceIds는 비어 있을 수 없습니다.")
    unknown_sources = set(referenced_sources) - source_ids
    if unknown_sources:
        raise DataContractError(
            f"{benchmark_id}가 알 수 없는 source를 참조합니다: "
            f"{', '.join(sorted(unknown_sources))}"
        )

    series = benchmark.get("series")
    if not isinstance(series, dict) or not series:
        raise DataContractError(f"{benchmark_id}.series는 비어 있을 수 없습니다.")
    for series_id, definition in series.items():
        _require_non_empty_string(definition, "label", f"{benchmark_id}.{series_id}")
        _require_non_empty_string(definition, "unit", f"{benchmark_id}.{series_id}")
        values = definition.get("values")
        if not isinstance(values, list) or len(values) != len(categories):
            raise DataContractError(
                f"{benchmark_id}.{series_id} 값 수가 category 수와 다릅니다."
            )
        for value in values:
            if (
                isinstance(value, bool)
                or not isinstance(value, (int, float))
                or not math.isfinite(value)
                or value < 0
            ):
                raise DataContractError(
                    f"{benchmark_id}.{series_id}에는 0 이상의 유한한 수만 허용됩니다."
                )

    chart = benchmark.get("chart")
    if not isinstance(chart, dict):
        raise DataContractError(f"{benchmark_id}.chart는 객체여야 합니다.")
    file_name = _require_non_empty_string(chart, "file", benchmark_id)
    if Path(file_name).name != file_name or not file_name.endswith(".svg"):
        raise DataContractError(f"올바르지 않은 SVG 파일 이름입니다: {file_name}")
    if file_name in output_files:
        raise DataContractError(f"중복 SVG 파일 이름입니다: {file_name}")
    output_files.add(file_name)

    left_series = _require_series_list(chart, "leftSeries", benchmark_id, series)
    right_series = _require_series_list(chart, "rightSeries", benchmark_id, series)
    if set(left_series) & set(right_series):
        raise DataContractError(f"{benchmark_id}의 좌우 축 Series가 중복됩니다.")
    if chart.get("leftScale") not in {"linear", "log10"}:
        raise DataContractError(f"{benchmark_id}.leftScale이 올바르지 않습니다.")
    for style_key in ("leftStyle", "rightStyle"):
        if chart.get(style_key) not in {"line", "bar"}:
            raise DataContractError(f"{benchmark_id}.{style_key}이 올바르지 않습니다.")
    if chart["leftScale"] == "log10":
        for series_id in left_series:
            if any(value <= 0 for value in series[series_id]["values"]):
                raise DataContractError(
                    f"{benchmark_id}.{series_id}의 Log 축 값은 0보다 커야 합니다."
                )


def _validate_supporting_baselines(
    baselines: Any,
    source_ids: set[str],
) -> None:
    """Validate provenance and aligned arrays for non-chart supporting baselines."""
    if not isinstance(baselines, dict) or not baselines:
        raise DataContractError("supportingBaselines는 비어 있을 수 없습니다.")
    for baseline_id, baseline in baselines.items():
        referenced_sources = baseline.get("sourceIds")
        if not isinstance(referenced_sources, list) or not referenced_sources:
            raise DataContractError(f"{baseline_id}.sourceIds는 비어 있을 수 없습니다.")
        unknown_sources = set(referenced_sources) - source_ids
        if unknown_sources:
            raise DataContractError(
                f"{baseline_id}가 알 수 없는 source를 참조합니다: "
                f"{', '.join(sorted(unknown_sources))}"
            )
        _require_non_empty_string(baseline, "comparisonBoundary", baseline_id)


def _require_non_empty_string(
    value: dict[str, Any],
    key: str,
    context: str,
) -> str:
    """Return a required, trimmed string or raise a contextual error."""
    result = value.get(key) if isinstance(value, dict) else None
    if not isinstance(result, str) or not result.strip():
        raise DataContractError(f"{context}.{key}는 비어 있지 않은 문자열이어야 합니다.")
    return result.strip()


def _require_series_list(
    chart: dict[str, Any],
    key: str,
    benchmark_id: str,
    series: dict[str, Any],
) -> list[str]:
    """Return a non-empty list of registered series identifiers."""
    result = chart.get(key)
    if not isinstance(result, list) or not result:
        raise DataContractError(f"{benchmark_id}.{key}는 비어 있을 수 없습니다.")
    unknown = set(result) - series.keys()
    if unknown:
        raise DataContractError(
            f"{benchmark_id}.{key}가 알 수 없는 Series를 참조합니다: "
            f"{', '.join(sorted(unknown))}"
        )
    return result


def render_all(data: dict[str, Any]) -> dict[str, str]:
    """Render every required benchmark to a file-name-to-SVG mapping."""
    return {
        benchmark["chart"]["file"]: render_chart(benchmark)
        for benchmark_id, benchmark in data["benchmarks"].items()
        if benchmark_id in REQUIRED_BENCHMARKS
    }


def render_chart(benchmark: dict[str, Any]) -> str:
    """Render one accessible dual-axis chart as a deterministic SVG string."""
    categories = benchmark["categories"]
    series = benchmark["series"]
    chart = benchmark["chart"]
    ordered_series = chart["leftSeries"] + chart["rightSeries"]
    colors = {
        series_id: PALETTE[index]
        for index, series_id in enumerate(ordered_series)
    }
    left_axis = _build_axis(
        [series[series_id]["values"] for series_id in chart["leftSeries"]],
        chart["leftScale"],
        series[chart["leftSeries"][0]]["unit"],
    )
    right_axis = _build_axis(
        [series[series_id]["values"] for series_id in chart["rightSeries"]],
        "linear",
        series[chart["rightSeries"][0]]["unit"],
    )

    parts = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        (
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{CANVAS_WIDTH}" '
            f'height="{CANVAS_HEIGHT}" viewBox="0 0 {CANVAS_WIDTH} {CANVAS_HEIGHT}" '
            'role="img" aria-labelledby="chart-title chart-description">'
        ),
        f'<title id="chart-title">{_escape(benchmark["title"])}</title>',
        (
            '<desc id="chart-description">'
            f'{_escape(benchmark["subtitle"])}. '
            f'X축은 {_escape(benchmark["categoryLabel"])}이며 좌우 축 Series를 함께 표시한다.'
            '</desc>'
        ),
        '<rect width="1200" height="680" fill="#ffffff"/>',
        '<style>text{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;}'
        '.title{font-size:25px;font-weight:700;fill:#111827;}'
        '.subtitle{font-size:14px;fill:#4b5563;}'
        '.axis{font-size:12px;fill:#4b5563;}'
        '.axis-title{font-size:13px;font-weight:600;fill:#374151;}'
        '.legend{font-size:13px;fill:#1f2937;}'
        '.grid{stroke:#e5e7eb;stroke-width:1;}'
        '.frame{stroke:#9ca3af;stroke-width:1.2;fill:none;}</style>',
        f'<text class="title" x="{PLOT_LEFT}" y="42">{_escape(benchmark["title"])}</text>',
        f'<text class="subtitle" x="{PLOT_LEFT}" y="68">{_escape(benchmark["subtitle"])}</text>',
    ]
    parts.extend(_render_legend(ordered_series, series, colors))
    parts.extend(_render_grid_and_axes(left_axis, right_axis))

    x_positions = _category_positions(len(categories))
    left_style = chart["leftStyle"]
    right_style = chart["rightStyle"]
    if left_style == "bar":
        parts.extend(
            _render_bars(chart["leftSeries"], series, colors, x_positions, left_axis)
        )
    if right_style == "bar":
        parts.extend(
            _render_bars(chart["rightSeries"], series, colors, x_positions, right_axis)
        )
    if left_style == "line":
        parts.extend(
            _render_lines(chart["leftSeries"], series, colors, x_positions, left_axis)
        )
    if right_style == "line":
        parts.extend(
            _render_lines(chart["rightSeries"], series, colors, x_positions, right_axis)
        )

    for x, category in zip(x_positions, categories, strict=True):
        parts.append(
            f'<text class="axis" x="{x:.2f}" y="594" text-anchor="middle">'
            f'{_escape(category)}</text>'
        )
    parts.extend(
        [
            (
                f'<text class="axis-title" x="{(PLOT_LEFT + PLOT_RIGHT) / 2:.1f}" '
                f'y="632" text-anchor="middle">{_escape(benchmark["categoryLabel"])}</text>'
            ),
            (
                f'<text class="axis-title" x="25" y="{(PLOT_TOP + PLOT_BOTTOM) / 2:.1f}" '
                f'text-anchor="middle" transform="rotate(-90 25 {(PLOT_TOP + PLOT_BOTTOM) / 2:.1f})">'
                f'{_escape(left_axis["unit"])}</text>'
            ),
            (
                f'<text class="axis-title" x="1175" y="{(PLOT_TOP + PLOT_BOTTOM) / 2:.1f}" '
                f'text-anchor="middle" transform="rotate(90 1175 {(PLOT_TOP + PLOT_BOTTOM) / 2:.1f})">'
                f'{_escape(right_axis["unit"])}</text>'
            ),
            '</svg>',
            '',
        ]
    )
    return "\n".join(parts)


def _build_axis(value_groups: list[list[float]], scale: str, unit: str) -> dict[str, Any]:
    """Create an axis descriptor and value-to-y coordinate mapping inputs."""
    maximum = max(max(values) for values in value_groups)
    if scale == "log10":
        lower = 10 ** math.floor(math.log10(min(min(values) for values in value_groups)))
        upper = 10 ** math.ceil(math.log10(maximum))
        if lower == upper:
            upper *= 10
        ticks = []
        tick = lower
        while tick <= upper:
            ticks.append(float(tick))
            tick *= 10
        return {"minimum": lower, "maximum": upper, "ticks": ticks, "scale": scale, "unit": unit}

    upper = _nice_upper(maximum)
    ticks = [upper * index / 5 for index in range(6)]
    return {"minimum": 0.0, "maximum": upper, "ticks": ticks, "scale": scale, "unit": unit}


def _nice_upper(maximum: float) -> float:
    """Round a positive maximum to a stable human-readable chart bound."""
    if maximum <= 0:
        return 1.0
    rough = maximum * 1.08
    magnitude = 10 ** math.floor(math.log10(rough))
    normalized = rough / magnitude
    if normalized <= 1:
        nice = 1
    elif normalized <= 2:
        nice = 2
    elif normalized <= 5:
        nice = 5
    else:
        nice = 10
    return nice * magnitude


def _category_positions(count: int) -> list[float]:
    """Return centered x coordinates for a categorical axis."""
    slot = (PLOT_RIGHT - PLOT_LEFT) / count
    return [PLOT_LEFT + slot * (index + 0.5) for index in range(count)]


def _axis_y(value: float, axis: dict[str, Any]) -> float:
    """Map a numeric value onto the chart's y coordinate."""
    if axis["scale"] == "log10":
        minimum = math.log10(axis["minimum"])
        maximum = math.log10(axis["maximum"])
        ratio = (math.log10(value) - minimum) / (maximum - minimum)
    else:
        ratio = (value - axis["minimum"]) / (axis["maximum"] - axis["minimum"])
    return PLOT_BOTTOM - ratio * (PLOT_BOTTOM - PLOT_TOP)


def _render_legend(
    ordered_series: list[str],
    series: dict[str, Any],
    colors: dict[str, str],
) -> list[str]:
    """Render a horizontal legend that includes a marker and unit."""
    parts: list[str] = []
    x = PLOT_LEFT
    for series_id in ordered_series:
        definition = series[series_id]
        parts.append(
            f'<line x1="{x}" y1="105" x2="{x + 24}" y2="105" '
            f'stroke="{colors[series_id]}" stroke-width="4"/>'
        )
        parts.append(
            f'<circle cx="{x + 12}" cy="105" r="4" fill="#ffffff" '
            f'stroke="{colors[series_id]}" stroke-width="3"/>'
        )
        label = f'{definition["label"]} ({definition["unit"]})'
        parts.append(
            f'<text class="legend" x="{x + 32}" y="110">{_escape(label)}</text>'
        )
        x += 32 + max(135, len(label) * 8)
    return parts


def _render_grid_and_axes(
    left_axis: dict[str, Any],
    right_axis: dict[str, Any],
) -> list[str]:
    """Render plot frame, horizontal grid, and both numeric axes."""
    parts = [
        (
            f'<rect class="frame" x="{PLOT_LEFT}" y="{PLOT_TOP}" '
            f'width="{PLOT_RIGHT - PLOT_LEFT}" height="{PLOT_BOTTOM - PLOT_TOP}"/>'
        )
    ]
    for tick in left_axis["ticks"]:
        y = _axis_y(tick, left_axis)
        parts.append(
            f'<line class="grid" x1="{PLOT_LEFT}" y1="{y:.2f}" '
            f'x2="{PLOT_RIGHT}" y2="{y:.2f}"/>'
        )
        parts.append(
            f'<text class="axis" x="{PLOT_LEFT - 12}" y="{y + 4:.2f}" '
            f'text-anchor="end">{_format_number(tick)}</text>'
        )
    for tick in right_axis["ticks"]:
        y = _axis_y(tick, right_axis)
        parts.append(
            f'<text class="axis" x="{PLOT_RIGHT + 12}" y="{y + 4:.2f}" '
            f'text-anchor="start">{_format_number(tick)}</text>'
        )
    return parts


def _render_lines(
    series_ids: list[str],
    series: dict[str, Any],
    colors: dict[str, str],
    x_positions: list[float],
    axis: dict[str, Any],
) -> list[str]:
    """Render line series with visible point markers and exact-value metadata."""
    parts: list[str] = []
    for series_id in series_ids:
        values = series[series_id]["values"]
        points = " ".join(
            f"{x:.2f},{_axis_y(value, axis):.2f}"
            for x, value in zip(x_positions, values, strict=True)
        )
        parts.append(
            f'<polyline points="{points}" fill="none" stroke="{colors[series_id]}" '
            'stroke-width="3.5" stroke-linejoin="round" stroke-linecap="round"/>'
        )
        for x, value in zip(x_positions, values, strict=True):
            y = _axis_y(value, axis)
            parts.append(
                f'<circle cx="{x:.2f}" cy="{y:.2f}" r="5" fill="#ffffff" '
                f'stroke="{colors[series_id]}" stroke-width="3" '
                f'data-series="{_escape(series_id)}" data-value="{value}"/>'
            )
    return parts


def _render_bars(
    series_ids: list[str],
    series: dict[str, Any],
    colors: dict[str, str],
    x_positions: list[float],
    axis: dict[str, Any],
) -> list[str]:
    """Render grouped bars against one numeric axis."""
    category_slot = (PLOT_RIGHT - PLOT_LEFT) / len(x_positions)
    group_width = category_slot * 0.58
    bar_width = group_width / len(series_ids)
    baseline_y = _axis_y(axis["minimum"], axis) if axis["scale"] == "log10" else PLOT_BOTTOM
    parts: list[str] = []
    for series_index, series_id in enumerate(series_ids):
        values = series[series_id]["values"]
        for x_center, value in zip(x_positions, values, strict=True):
            x = x_center - group_width / 2 + series_index * bar_width + 2
            y = _axis_y(value, axis)
            height = max(1.0, baseline_y - y)
            parts.append(
                f'<rect x="{x:.2f}" y="{y:.2f}" width="{max(1.0, bar_width - 4):.2f}" '
                f'height="{height:.2f}" rx="3" fill="{colors[series_id]}" fill-opacity="0.82" '
                f'data-series="{_escape(series_id)}" data-value="{value}"/>'
            )
    return parts


def _format_number(value: float) -> str:
    """Format axis values without locale-dependent output."""
    if value >= 1000:
        return f"{value:,.0f}"
    if value >= 10:
        return f"{value:.0f}"
    if value >= 1:
        return f"{value:.1f}".rstrip("0").rstrip(".")
    return f"{value:.2f}".rstrip("0").rstrip(".")


def _escape(value: Any) -> str:
    """Escape text used in SVG element content and attributes."""
    return html.escape(str(value), quote=True)


def write_outputs(rendered: dict[str, str], output_directory: Path) -> None:
    """Write all rendered SVG files using stable UTF-8 and LF output."""
    output_directory.mkdir(parents=True, exist_ok=True)
    for file_name in sorted(rendered):
        (output_directory / file_name).write_text(rendered[file_name], encoding="utf-8")


def check_outputs(rendered: dict[str, str], output_directory: Path) -> None:
    """Fail when committed SVG files are missing or differ from generated output."""
    mismatches: list[str] = []
    for file_name in sorted(rendered):
        output_path = output_directory / file_name
        if not output_path.is_file():
            mismatches.append(f"누락: {_display_path(output_path)}")
            continue
        if output_path.read_text(encoding="utf-8") != rendered[file_name]:
            mismatches.append(f"불일치: {_display_path(output_path)}")
    if mismatches:
        details = "\n".join(f"- {mismatch}" for mismatch in mismatches)
        raise DataContractError(
            "Commit된 성능 그래프가 정규화 데이터와 일치하지 않습니다.\n"
            f"{details}\n"
            "다음 명령으로 다시 생성하세요: "
            "python3 scripts/performance/generate_final_performance_report.py"
        )


def _display_path(path: Path) -> str:
    """Prefer a repository-relative path while supporting isolated test directories."""
    try:
        return str(path.relative_to(REPOSITORY_ROOT))
    except ValueError:
        return str(path)


def parse_arguments(arguments: list[str] | None = None) -> argparse.Namespace:
    """Parse command-line arguments for generation and verification."""
    parser = argparse.ArgumentParser(
        description="DocGrid 최종 통합 성능 데이터 검증과 SVG 생성을 수행합니다."
    )
    parser.add_argument("--check", action="store_true", help="Commit된 SVG와 생성 결과만 비교합니다.")
    parser.add_argument("--data", type=Path, default=DEFAULT_DATA_PATH, help="정규화 JSON 경로")
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=DEFAULT_OUTPUT_DIRECTORY,
        help="SVG 출력 Directory",
    )
    return parser.parse_args(arguments)


def main(arguments: list[str] | None = None) -> int:
    """Run validation, rendering, and either deterministic check or file writing."""
    options = parse_arguments(arguments)
    try:
        # 1. 정규화 데이터와 저장소 내 출처를 먼저 검증한다.
        data = load_data(options.data)
        validate_data(data, REPOSITORY_ROOT)
        # 2. 검증된 데이터만 결정적 SVG 문자열로 변환한다.
        rendered = render_all(data)
        # 3. 검사 모드는 파일을 변경하지 않고 Commit 결과와 비교한다.
        if options.check:
            check_outputs(rendered, options.output_dir)
            print(f"성능 그래프 {len(rendered)}개가 정규화 데이터와 일치합니다.")
        else:
            # 4. 기본 모드는 검증된 결과만 대상 Directory에 기록한다.
            write_outputs(rendered, options.output_dir)
            print(f"성능 그래프 {len(rendered)}개를 생성했습니다: {options.output_dir}")
        return 0
    except DataContractError as exc:
        print(f"오류: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

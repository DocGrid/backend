import math

import pytest

import benchmark_batch_size as benchmark


def test_parse_batch_sizes_preserves_declared_order():
    assert benchmark.parse_batch_sizes("16,1,64") == (16, 1, 64)


@pytest.mark.parametrize("raw_value", ["", "1,1", "0,1", "1,65", "one,4"])
def test_parse_batch_sizes_rejects_invalid_value(raw_value):
    with pytest.raises(benchmark.BenchmarkError):
        benchmark.parse_batch_sizes(raw_value)


def test_build_corpus_is_deterministic_and_non_blank():
    first = benchmark.build_corpus(20)
    second = benchmark.build_corpus(20)

    assert first == second
    assert len(first) == 20
    assert all(text.strip() for text in first)
    assert first[0] != first[-1]


def test_percentile_uses_linear_interpolation():
    values = [1.0, 2.0, 3.0, 4.0]

    assert benchmark.percentile(values, 0) == 1.0
    assert benchmark.percentile(values, 50) == 2.5
    assert benchmark.percentile(values, 95) == pytest.approx(3.85)
    assert benchmark.percentile(values, 100) == 4.0


def test_rotated_batch_sizes_changes_start_profile():
    batch_sizes = (1, 4, 8, 16)

    assert benchmark.rotated_batch_sizes(batch_sizes, 0) == (1, 4, 8, 16)
    assert benchmark.rotated_batch_sizes(batch_sizes, 1) == (4, 8, 16, 1)
    assert benchmark.rotated_batch_sizes(batch_sizes, 5) == (4, 8, 16, 1)


def test_validate_batch_response_accepts_ordered_finite_vectors():
    payload = {
        "model": benchmark.MODEL_NAME,
        "embeddings": [
            {"index": 0, "vector": [0.1] * benchmark.VECTOR_DIMENSION},
            {"index": 1, "vector": [0.2] * benchmark.VECTOR_DIMENSION},
        ],
    }

    benchmark.validate_batch_response(payload, expected_count=2)


@pytest.mark.parametrize(
    "payload",
    [
        {"model": "other", "embeddings": []},
        {"model": benchmark.MODEL_NAME, "embeddings": []},
        {
            "model": benchmark.MODEL_NAME,
            "embeddings": [{"index": 1, "vector": [0.1] * benchmark.VECTOR_DIMENSION}],
        },
        {
            "model": benchmark.MODEL_NAME,
            "embeddings": [{"index": 0, "vector": [0.1]}],
        },
        {
            "model": benchmark.MODEL_NAME,
            "embeddings": [
                {
                    "index": 0,
                    "vector": [math.nan] + [0.1] * (benchmark.VECTOR_DIMENSION - 1),
                }
            ],
        },
    ],
)
def test_validate_batch_response_rejects_contract_violation(payload):
    with pytest.raises(benchmark.BenchmarkError):
        benchmark.validate_batch_response(payload, expected_count=1)


def test_summarize_profile_calculates_latency_throughput_and_rss():
    samples = [
        benchmark.RequestSample(4, 0, 0, 4, 1.0, True, None),
        benchmark.RequestSample(4, 0, 1, 4, 3.0, True, None),
        benchmark.RequestSample(4, 0, 2, 4, 0.5, False, "TimeoutError"),
        benchmark.RequestSample(8, 0, 0, 8, 2.0, True, None),
    ]
    memory_samples = [
        benchmark.MemorySample(10.0, 100),
        benchmark.MemorySample(11.0, 180),
        benchmark.MemorySample(20.0, 999),
    ]
    windows = [benchmark.ProfileWindow(4, 9.0, 12.0)]

    result = benchmark.summarize_profile(4, samples, memory_samples, windows)

    assert result["request_count"] == 3
    assert result["success_count"] == 2
    assert result["failure_count"] == 1
    assert result["successful_text_count"] == 8
    assert result["throughput_texts_per_second"] == 2.0
    assert result["request_latency_milliseconds"]["p50"] == 2000.0
    assert result["average_latency_milliseconds_per_text"] == 500.0
    assert result["rss"]["minimum_bytes"] == 100
    assert result["rss"]["maximum_bytes"] == 180
    assert result["rss"]["delta_bytes"] == 80
    assert result["errors"] == ["TimeoutError"]


def test_recommendation_keeps_current_default_within_five_percent():
    results = [
        {"batch_size": 16, "failure_count": 0, "throughput_texts_per_second": 96.0},
        {"batch_size": 32, "failure_count": 0, "throughput_texts_per_second": 100.0},
    ]

    result = benchmark.recommendation(results)

    assert result["decision"] == "KEEP_DEFAULT"
    assert result["fastest_batch_size"] == 32
    assert result["current_to_fastest_throughput_ratio"] == 0.96


def test_validate_config_rejects_unbalanced_total_text_count(tmp_path):
    config = benchmark.BenchmarkConfig(
        base_url="http://localhost:8000",
        batch_sizes=(1, 4, 8),
        total_texts=10,
        warmup_rounds=1,
        measurement_rounds=1,
        timeout_seconds=10.0,
        container_name=None,
        memory_sample_interval_seconds=1.0,
        output_path=tmp_path / "result.json",
    )

    with pytest.raises(benchmark.BenchmarkError):
        benchmark.validate_config(config)

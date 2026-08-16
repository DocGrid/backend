import json

import pytest

import benchmark_real_pdf as benchmark
from benchmark_batch_size import MemorySample


def write_corpus(tmp_path):
    path = tmp_path / "corpus.json"
    path.write_text(json.dumps({
        "schemaVersion": 1,
        "chunkSize": 1000,
        "overlap": 200,
        "documents": [{
            "sourceName": "noname.pdf",
            "sizeBytes": 100,
            "fileSha256": "a" * 64,
            "chunks": [
                {
                    "chunkIndex": 0,
                    "text": "실제 PDF 본문",
                    "codePointCount": 9,
                    "utf8Bytes": 21,
                    "estimatedTokenCount": 3,
                    "contentHash": "b" * 64,
                },
                {
                    "chunkIndex": 1,
                    "text": "두 번째 PDF 본문",
                    "codePointCount": 11,
                    "utf8Bytes": 25,
                    "estimatedTokenCount": 4,
                    "contentHash": "c" * 64,
                },
            ],
        }],
    }), encoding="utf-8")
    return path


def test_parse_positive_integers_preserves_declared_order():
    assert benchmark.parse_positive_integers("4,8,16", maximum=64) == (4, 8, 16)


@pytest.mark.parametrize("raw_value", ["", "4,4", "0,4", "four,8", "65"])
def test_parse_positive_integers_rejects_invalid_values(raw_value):
    with pytest.raises(benchmark.BenchmarkError):
        benchmark.parse_positive_integers(raw_value, maximum=64)


def test_load_corpus_returns_texts_but_summary_excludes_raw_content(tmp_path):
    documents, summary = benchmark.load_corpus(write_corpus(tmp_path))
    texts = [text for document in documents for text in document]

    assert [text.text for text in texts] == ["실제 PDF 본문", "두 번째 PDF 본문"]
    assert summary["document_count"] == 1
    assert summary["chunk_count"] == 2
    assert summary["documents"][0]["file_sha256"] == "a" * 64
    assert "text" not in json.dumps(summary, ensure_ascii=False)
    assert summary["corpus_sha256"]


def test_summarize_profile_uses_wall_time_for_concurrent_throughput():
    samples = [
        benchmark.RequestSample(4, 2, 0, 0, 4, 4000, 8000, 2.0, True, None),
        benchmark.RequestSample(4, 2, 0, 1, 4, 4000, 8000, 2.5, True, None),
    ]
    windows = [benchmark.ProfileWindow(4, 2, 0, 10.0, 13.0)]
    memory = [MemorySample(11.0, 100), MemorySample(12.0, 180)]
    states = [benchmark.ProfileContainerState(4, 2, True, False, 0, 0, 1024, None)]
    documents = [
        benchmark.DocumentSample(4, 2, 0, 0, "a.pdf", "a" * 64, 8, 2, 3.0, True)
    ]

    result = benchmark.summarize_profile(4, 2, samples, documents, memory, windows, states)

    assert result["throughput_texts_per_second"] == pytest.approx(8 / 3)
    assert result["request_latency_milliseconds"]["p50"] == 2250.0
    assert result["corpus_round_latency_milliseconds"]["p99"] == 3000.0
    assert result["document_latency_milliseconds"]["p99"] == 3000.0
    assert result["document_success_count"] == 1
    assert result["rss"]["maximum_bytes"] == 180
    assert result["oom_killed"] is False
    assert result["container_state_verified"] is True


def test_run_round_preserves_document_boundaries_and_sequential_batches(tmp_path, monkeypatch):
    request_shapes = []

    def execute_request(config, texts, batch_size, concurrency, round_index, request_index):
        request_shapes.append((texts[0].file_sha256, len(texts), request_index))
        return benchmark.RequestSample(
            batch_size, concurrency, round_index, request_index, len(texts),
            sum(text.code_point_count for text in texts),
            sum(text.utf8_bytes for text in texts),
            0.01, True, None,
        )

    monkeypatch.setattr(benchmark, "execute_request", execute_request)
    config = benchmark.SafetyBenchmarkConfig(
        "http://localhost:8000", tmp_path / "corpus.json", (4,), (1,),
        1, 1, 1, 60.0, None, 0.25, tmp_path / "result.json",
    )
    documents = (
        tuple(corpus_text("a", index) for index in range(6)),
        tuple(corpus_text("b", index) for index in range(6)),
    )

    samples, document_samples = benchmark.run_round(config, documents, 4, 1, 0)

    assert [(file_hash, size) for file_hash, size, _ in request_shapes] == [
        ("a" * 64, 4), ("a" * 64, 2), ("b" * 64, 4), ("b" * 64, 2),
    ]
    assert len(samples) == 4
    assert [sample.request_count for sample in document_samples] == [2, 2]
    assert all(sample.success for sample in document_samples)


def test_recommendation_selects_smallest_safe_batch_within_five_percent():
    results = [
        profile(4, 1, 90.0, 9000.0),
        profile(4, 2, 98.0, 10000.0),
        profile(8, 1, 95.0, 11000.0),
        profile(8, 2, 100.0, 12000.0),
        profile(16, 2, 103.0, 15000.0),
    ]

    result = benchmark.recommendation(results, (1, 2))

    assert result["decision"] == "APPLY_SAFE_DEFAULTS"
    assert result["selected_batch_size"] == 4
    assert result["fastest_batch_size"] == 16
    assert result["document_read_timeout_seconds"] == 30


def test_recommendation_rejects_failed_or_oom_profiles():
    failed = profile(4, 2, 100.0, 10000.0)
    failed["failure_count"] = 1
    oom = profile(8, 2, 120.0, 10000.0)
    oom["oom_killed"] = True

    result = benchmark.recommendation([failed, oom], (1, 2))

    assert result["decision"] == "NO_SAFE_BATCH"


def test_write_payload_replaces_checkpoint_atomically(tmp_path):
    output = tmp_path / "result.json"

    benchmark.write_payload(output, {"value": 1})
    benchmark.write_payload(output, {"value": 2})

    assert json.loads(output.read_text(encoding="utf-8")) == {"value": 2}
    assert not output.with_suffix(".json.tmp").exists()


def profile(batch_size, concurrency, throughput, p99):
    return {
        "batch_size": batch_size,
        "concurrency": concurrency,
        "failure_count": 0,
        "oom_killed": False,
        "container_running": True,
        "container_state_verified": True,
        "throughput_texts_per_second": throughput,
        "request_latency_milliseconds": {"p99": p99},
    }


def corpus_text(file_marker, chunk_index):
    return benchmark.CorpusText(
        f"{file_marker}.pdf",
        file_marker * 64,
        chunk_index,
        f"chunk-{chunk_index}",
        10,
        10,
        2,
        str(chunk_index) * 64,
    )

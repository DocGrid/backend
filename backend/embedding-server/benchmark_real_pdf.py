#!/usr/bin/env python3
"""실제 PDF Chunk Corpus로 BGE-M3 Batch·동시성·메모리 안전 기준을 측정한다."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import subprocess
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Sequence
from urllib import error, request

from benchmark_batch_size import (
    BenchmarkError,
    ContainerMemorySampler,
    MemorySample,
    collect_environment,
    percentile,
    validate_batch_response,
)


MODEL_NAME = "BAAI/bge-m3"
VECTOR_DIMENSION = 1024
CURRENT_DEFAULT_BATCH_SIZE = 32
DEFAULT_BATCH_SIZES = (4, 8, 16)
DEFAULT_CONCURRENCY_LEVELS = (1, 2)


@dataclass(frozen=True)
class SafetyBenchmarkConfig:
    """실제 PDF 안전성 Benchmark가 공유하는 입력·실행·출력 설정이다."""

    base_url: str
    corpus_path: Path
    batch_sizes: tuple[int, ...]
    concurrency_levels: tuple[int, ...]
    corpus_repetitions: int
    warmup_rounds: int
    measurement_rounds: int
    timeout_seconds: float
    container_name: str | None
    memory_sample_interval_seconds: float
    output_path: Path
    allow_overload_rejections: bool = False


@dataclass(frozen=True)
class CorpusText:
    """Provider에 전달할 실제 Chunk와 공개 가능한 길이 통계를 결합한다."""

    source_name: str
    file_sha256: str
    chunk_index: int
    text: str
    code_point_count: int
    utf8_bytes: int
    estimated_token_count: int
    content_hash: str


@dataclass(frozen=True)
class RequestSample:
    """한 Batch HTTP 요청의 입력 규모·동시성·지연·성공 여부를 기록한다."""

    batch_size: int
    concurrency: int
    round_index: int
    request_index: int
    text_count: int
    code_point_count: int
    utf8_bytes: int
    latency_seconds: float
    success: bool
    error_type: str | None


@dataclass(frozen=True)
class DocumentSample:
    """한 문서 Job이 내부 Batch를 순차 처리한 전체 지연과 성공 여부를 기록한다."""

    batch_size: int
    concurrency: int
    round_index: int
    document_run_index: int
    source_name: str
    file_sha256: str
    chunk_count: int
    request_count: int
    latency_seconds: float
    success: bool


@dataclass(frozen=True)
class ProfileWindow:
    """특정 Batch·동시성 Profile 한 Round의 실제 경과 시간 구간이다."""

    batch_size: int
    concurrency: int
    round_index: int
    started_at: float
    finished_at: float


@dataclass(frozen=True)
class ProfileContainerState:
    """한 Profile 직후의 Provider 생존·OOM·재시작 상태를 보존한다."""

    batch_size: int
    concurrency: int
    running: bool | None
    oom_killed: bool | None
    exit_code: int | None
    restart_count: int | None
    memory_limit_bytes: int | None
    inspect_error: str | None


def parse_positive_integers(raw_value: str, maximum: int | None = None) -> tuple[int, ...]:
    """쉼표 구분 양의 정수를 중복 없이 선언 순서대로 읽는다."""
    try:
        values = tuple(int(value.strip()) for value in raw_value.split(","))
    except ValueError as exception:
        raise BenchmarkError("values must be comma-separated integers") from exception
    if not values or len(set(values)) != len(values) or any(value < 1 for value in values):
        raise BenchmarkError("values must be unique positive integers")
    if maximum is not None and any(value > maximum for value in values):
        raise BenchmarkError(f"values must not exceed {maximum}")
    return values


def environment_int(name: str, default: int) -> int:
    """환경 변수의 정수 값을 읽고 잘못된 입력을 Benchmark 오류로 통일한다."""
    try:
        return int(os.getenv(name, str(default)))
    except ValueError as exception:
        raise BenchmarkError(f"{name} must be an integer") from exception


def environment_float(name: str, default: float) -> float:
    """환경 변수의 실수 값을 읽고 잘못된 입력을 Benchmark 오류로 통일한다."""
    try:
        return float(os.getenv(name, str(default)))
    except ValueError as exception:
        raise BenchmarkError(f"{name} must be a number") from exception


def load_corpus(path: Path) -> tuple[tuple[tuple[CorpusText, ...], ...], dict[str, Any]]:
    """운영 Java Exporter의 JSON에서 Text를 읽고 원문 없는 공개 요약을 만든다."""
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise BenchmarkError("real PDF corpus could not be loaded") from exception
    if payload.get("schemaVersion") != 1 or not isinstance(payload.get("documents"), list):
        raise BenchmarkError("unsupported real PDF corpus schema")

    corpus_documents: list[tuple[CorpusText, ...]] = []
    all_texts: list[CorpusText] = []
    documents: list[dict[str, Any]] = []
    for document in payload["documents"]:
        if not isinstance(document, dict):
            raise BenchmarkError("corpus document metadata is invalid")
        chunks = document.get("chunks")
        if not isinstance(chunks, list) or not chunks:
            raise BenchmarkError("corpus document must contain chunks")
        source_name = document.get("sourceName")
        file_sha256 = document.get("fileSha256")
        if not isinstance(source_name, str) or not isinstance(file_sha256, str):
            raise BenchmarkError("corpus document identity is invalid")
        document_texts: list[CorpusText] = []
        try:
            size_bytes = int(document["sizeBytes"])
            for chunk in chunks:
                if not isinstance(chunk, dict):
                    raise TypeError
                text = chunk.get("text")
                if not isinstance(text, str) or not text.strip():
                    raise BenchmarkError("corpus chunk text must be non-blank")
                content_hash = chunk["contentHash"]
                if not isinstance(content_hash, str) or not content_hash:
                    raise TypeError
                document_texts.append(CorpusText(
                    source_name=source_name,
                    file_sha256=file_sha256,
                    chunk_index=int(chunk["chunkIndex"]),
                    text=text,
                    code_point_count=int(chunk["codePointCount"]),
                    utf8_bytes=int(chunk["utf8Bytes"]),
                    estimated_token_count=int(chunk["estimatedTokenCount"]),
                    content_hash=content_hash,
                ))
        except (KeyError, TypeError, ValueError) as exception:
            raise BenchmarkError("corpus chunk metadata is invalid") from exception
        corpus_documents.append(tuple(document_texts))
        all_texts.extend(document_texts)
        documents.append({
            "source_name": source_name,
            "size_bytes": size_bytes,
            "file_sha256": file_sha256,
            "chunk_count": len(chunks),
        })
    if not all_texts:
        raise BenchmarkError("real PDF corpus is empty")

    code_points = [text.code_point_count for text in all_texts]
    utf8_sizes = [text.utf8_bytes for text in all_texts]
    token_estimates = [text.estimated_token_count for text in all_texts]
    fingerprint_source = "\n".join(
        f"{text.file_sha256}:{text.chunk_index}:{text.content_hash}" for text in all_texts
    ).encode("utf-8")
    summary = {
        "schema_version": 1,
        "chunk_size": int(payload.get("chunkSize", 0)),
        "overlap": int(payload.get("overlap", 0)),
        "document_count": len(documents),
        "chunk_count": len(all_texts),
        "corpus_sha256": hashlib.sha256(fingerprint_source).hexdigest(),
        "documents": documents,
        "code_points": distribution(code_points),
        "utf8_bytes": distribution(utf8_sizes),
        "estimated_tokens": distribution(token_estimates),
    }
    return tuple(corpus_documents), summary


def distribution(values: Sequence[int]) -> dict[str, float | int]:
    """입력 길이 목록을 원문 없이 비교 가능한 분포로 요약한다."""
    if not values:
        raise BenchmarkError("distribution requires values")
    return {
        "minimum": min(values),
        "p50": percentile(values, 50),
        "p95": percentile(values, 95),
        "maximum": max(values),
    }


def execute_request(
    config: SafetyBenchmarkConfig,
    texts: Sequence[CorpusText],
    batch_size: int,
    concurrency: int,
    round_index: int,
    request_index: int,
) -> RequestSample:
    """실제 Batch 응답 전체 수신과 Vector 계약 검증까지의 지연을 측정한다."""
    body = json.dumps(
        {"texts": [text.text for text in texts], "batch_size": batch_size},
        ensure_ascii=False,
    ).encode("utf-8")
    http_request = request.Request(
        f"{config.base_url.rstrip('/')}/embed/batch",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    started_at = time.perf_counter()
    try:
        with request.urlopen(http_request, timeout=config.timeout_seconds) as response:
            payload = json.loads(response.read().decode("utf-8"))
        validate_batch_response(payload, len(texts), MODEL_NAME, VECTOR_DIMENSION)
        return RequestSample(
            batch_size, concurrency, round_index, request_index, len(texts),
            sum(text.code_point_count for text in texts),
            sum(text.utf8_bytes for text in texts),
            time.perf_counter() - started_at, True, None,
        )
    except error.HTTPError as exception:
        return RequestSample(
            batch_size, concurrency, round_index, request_index, len(texts),
            sum(text.code_point_count for text in texts),
            sum(text.utf8_bytes for text in texts),
            time.perf_counter() - started_at, False, f"HTTP_{exception.code}",
        )
    except (
        BenchmarkError,
        OSError,
        UnicodeDecodeError,
        json.JSONDecodeError,
    ) as exception:
        return RequestSample(
            batch_size, concurrency, round_index, request_index, len(texts),
            sum(text.code_point_count for text in texts),
            sum(text.utf8_bytes for text in texts),
            time.perf_counter() - started_at, False, type(exception).__name__,
        )


def assert_health(config: SafetyBenchmarkConfig) -> None:
    """Model 준비가 끝난 Provider만 안전성 측정에 사용한다."""
    health_request = request.Request(f"{config.base_url.rstrip('/')}/health", method="GET")
    try:
        with request.urlopen(health_request, timeout=config.timeout_seconds) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise BenchmarkError("embedding server health check failed") from exception
    if payload != {"status": "ok"}:
        raise BenchmarkError("embedding server is not ready")


def inspect_container(
    container_name: str | None,
    batch_size: int,
    concurrency: int,
) -> ProfileContainerState:
    """Provider Container의 생존·OOM·Resource Limit 상태를 읽는다."""
    if not container_name:
        return ProfileContainerState(batch_size, concurrency, None, None, None, None, None, None)
    try:
        completed = subprocess.run(
            ["docker", "inspect", container_name],
            check=True,
            capture_output=True,
            text=True,
            timeout=10,
        )
        payload = json.loads(completed.stdout)[0]
        state = payload.get("State", {})
        return ProfileContainerState(
            batch_size=batch_size,
            concurrency=concurrency,
            running=bool(state.get("Running")),
            oom_killed=bool(state.get("OOMKilled")),
            exit_code=int(state.get("ExitCode", 0)),
            restart_count=int(payload.get("RestartCount", 0)),
            memory_limit_bytes=int(payload.get("HostConfig", {}).get("Memory", 0)),
            inspect_error=None,
        )
    except (OSError, subprocess.SubprocessError, ValueError, KeyError, json.JSONDecodeError) as exception:
        return ProfileContainerState(
            batch_size, concurrency, None, None, None, None, None, type(exception).__name__
        )


def run_document(
    config: SafetyBenchmarkConfig,
    document: Sequence[CorpusText],
    batch_size: int,
    concurrency: int,
    round_index: int,
    document_run_index: int,
) -> tuple[list[RequestSample], DocumentSample]:
    """운영 Worker처럼 한 문서의 Batch를 순차 처리하고 전체 지연을 측정한다."""
    started_at = time.perf_counter()
    batches = [
        document[start : start + batch_size]
        for start in range(0, len(document), batch_size)
    ]
    samples: list[RequestSample] = []
    for batch_index, texts in enumerate(batches):
        sample = execute_request(
            config,
            texts,
            batch_size,
            concurrency,
            round_index,
            document_run_index * 10_000 + batch_index,
        )
        samples.append(sample)
        if not sample.success:
            break
    first_text = document[0]
    document_sample = DocumentSample(
        batch_size=batch_size,
        concurrency=concurrency,
        round_index=round_index,
        document_run_index=document_run_index,
        source_name=first_text.source_name,
        file_sha256=first_text.file_sha256,
        chunk_count=len(document),
        request_count=len(samples),
        latency_seconds=time.perf_counter() - started_at,
        success=len(samples) == len(batches) and all(sample.success for sample in samples),
    )
    return samples, document_sample


def run_round(
    config: SafetyBenchmarkConfig,
    workload: Sequence[Sequence[CorpusText]],
    batch_size: int,
    concurrency: int,
    round_index: int,
) -> tuple[list[RequestSample], list[DocumentSample]]:
    """문서 내부는 순차, 문서 Job 사이만 지정 동시성으로 전체 Corpus를 처리한다."""
    if concurrency == 1:
        results = [
            run_document(
                config, document, batch_size, concurrency, round_index, document_run_index
            )
            for document_run_index, document in enumerate(workload)
        ]
    else:
        # Provider 동시성은 실제 Worker Job 수와 같고 한 Job의 내부 Batch 순서는 유지한다.
        with ThreadPoolExecutor(
            max_workers=concurrency,
            thread_name_prefix="real-pdf-bge",
        ) as executor:
            futures = [
                executor.submit(
                    run_document,
                    config,
                    document,
                    batch_size,
                    concurrency,
                    round_index,
                    document_run_index,
                )
                for document_run_index, document in enumerate(workload)
            ]
            results = [future.result() for future in futures]

    return (
        [sample for request_samples, _ in results for sample in request_samples],
        [document_sample for _, document_sample in results],
    )


def summarize_profile(
    batch_size: int,
    concurrency: int,
    samples: Sequence[RequestSample],
    document_samples: Sequence[DocumentSample],
    memory_samples: Sequence[MemorySample],
    windows: Sequence[ProfileWindow],
    states: Sequence[ProfileContainerState],
) -> dict[str, Any]:
    """Batch·동시성 조합의 지연·처리량·RSS·OOM 결과를 집계한다."""
    profile_samples = [
        sample for sample in samples
        if sample.batch_size == batch_size and sample.concurrency == concurrency
    ]
    successes = [sample for sample in profile_samples if sample.success]
    failures = [sample for sample in profile_samples if not sample.success]
    overload_rejections = [
        sample for sample in failures if sample.error_type == "HTTP_429"
    ]
    unexpected_failures = [
        sample for sample in failures if sample.error_type != "HTTP_429"
    ]
    profile_documents = [
        sample for sample in document_samples
        if sample.batch_size == batch_size and sample.concurrency == concurrency
    ]
    latencies = [sample.latency_seconds for sample in successes]
    profile_windows = [
        window for window in windows
        if window.batch_size == batch_size and window.concurrency == concurrency
    ]
    round_latencies = [window.finished_at - window.started_at for window in profile_windows]
    elapsed = sum(round_latencies)
    total_texts = sum(sample.text_count for sample in successes)
    rss_values = [
        sample.rss_bytes
        for sample in memory_samples
        if any(
            window.started_at <= sample.monotonic_seconds <= window.finished_at
            for window in profile_windows
        )
    ]
    profile_states = [
        state for state in states
        if state.batch_size == batch_size and state.concurrency == concurrency
    ]
    return {
        "batch_size": batch_size,
        "concurrency": concurrency,
        "request_count": len(profile_samples),
        "success_count": len(successes),
        "failure_count": len(failures),
        "failure_rate": len(failures) / len(profile_samples) if profile_samples else 1.0,
        "overload_rejection_count": len(overload_rejections),
        "unexpected_failure_count": len(unexpected_failures),
        "overload_rejection_latency_milliseconds": latency_summary([
            sample.latency_seconds for sample in overload_rejections
        ]),
        "successful_text_count": total_texts,
        "throughput_texts_per_second": total_texts / elapsed if elapsed > 0 else None,
        "request_latency_milliseconds": latency_summary(latencies),
        "document_latency_milliseconds": latency_summary([
            sample.latency_seconds for sample in profile_documents if sample.success
        ]),
        "document_success_count": sum(sample.success for sample in profile_documents),
        "document_failure_count": sum(not sample.success for sample in profile_documents),
        "corpus_round_latency_milliseconds": latency_summary(round_latencies),
        "average_latency_milliseconds_per_text": (
            sum(latencies) * 1000 / total_texts if total_texts > 0 else None
        ),
        "rss": {
            "sample_count": len(rss_values),
            "minimum_bytes": min(rss_values) if rss_values else None,
            "maximum_bytes": max(rss_values) if rss_values else None,
            "delta_bytes": max(rss_values) - min(rss_values) if rss_values else None,
        },
        "oom_killed": any(state.oom_killed is True for state in profile_states),
        "container_running": bool(profile_states)
            and all(state.running is True for state in profile_states),
        "container_state_verified": bool(profile_states)
            and all(
                state.running is not None and state.oom_killed is not None
                for state in profile_states
            ),
        "errors": sorted({sample.error_type for sample in failures if sample.error_type}),
    }


def latency_summary(values: Sequence[float]) -> dict[str, float | int | None]:
    """초 단위 표본을 Millisecond p50·p95·p99·max로 요약한다."""
    return {
        "sample_count": len(values),
        "p50": percentile(values, 50) * 1000 if values else None,
        "p95": percentile(values, 95) * 1000 if values else None,
        "p99": percentile(values, 99) * 1000 if values else None,
        "max": max(values) * 1000 if values else None,
    }


def recommendation(
    results: Sequence[dict[str, Any]],
    concurrency_levels: Sequence[int],
) -> dict[str, Any]:
    """최대 동시성에서 안전하고 최고 처리량의 95% 이상인 가장 작은 Batch를 선택한다."""
    required_concurrency = max(concurrency_levels)
    eligible = [
        result for result in results
        if result["concurrency"] == required_concurrency
        and result["failure_count"] == 0
        and not result["oom_killed"]
        and result["container_running"]
        and result["container_state_verified"]
        and result["throughput_texts_per_second"] is not None
    ]
    if not eligible:
        return {
            "decision": "NO_SAFE_BATCH",
            "current_default_batch_size": CURRENT_DEFAULT_BATCH_SIZE,
            "required_concurrency": required_concurrency,
        }

    fastest = max(eligible, key=lambda result: result["throughput_texts_per_second"])
    threshold = fastest["throughput_texts_per_second"] * 0.95
    selected = min(
        (result for result in eligible if result["throughput_texts_per_second"] >= threshold),
        key=lambda result: result["batch_size"],
    )
    selected_profiles = [
        result for result in results
        if result["batch_size"] == selected["batch_size"]
        and result["failure_count"] == 0
        and result["request_latency_milliseconds"]["p99"] is not None
    ]
    worst_p99 = max(
        result["request_latency_milliseconds"]["p99"] for result in selected_profiles
    )
    calculated_timeout = math.ceil(max(30_000.0, worst_p99 * 2) / 5_000.0) * 5
    if calculated_timeout > 60:
        return {
            "decision": "TIMEOUT_BUDGET_EXCEEDED",
            "selected_batch_size": selected["batch_size"],
            "worst_request_p99_milliseconds": worst_p99,
            "calculated_timeout_seconds": calculated_timeout,
        }
    return {
        "decision": "APPLY_SAFE_DEFAULTS",
        "current_default_batch_size": CURRENT_DEFAULT_BATCH_SIZE,
        "selected_batch_size": selected["batch_size"],
        "fastest_batch_size": fastest["batch_size"],
        "selected_to_fastest_throughput_ratio": (
            selected["throughput_texts_per_second"]
            / fastest["throughput_texts_per_second"]
        ),
        "required_concurrency": required_concurrency,
        "worst_request_p99_milliseconds": worst_p99,
        "document_read_timeout_seconds": calculated_timeout,
        "policy": "최대 동시성에서 실패·OOM 없이 최고 처리량의 95% 이상인 가장 작은 Batch",
    }


def build_payload(
    config: SafetyBenchmarkConfig,
    corpus_summary: dict[str, Any],
    environment: dict[str, Any],
    samples: Sequence[RequestSample],
    document_samples: Sequence[DocumentSample],
    memory_samples: Sequence[MemorySample],
    windows: Sequence[ProfileWindow],
    states: Sequence[ProfileContainerState],
    stop_reason: str | None,
) -> dict[str, Any]:
    """진행 중에도 저장 가능한 원문 없는 Benchmark 결과를 조립한다."""
    measured_profiles = sorted({(sample.batch_size, sample.concurrency) for sample in samples})
    results = [
        summarize_profile(
            batch_size,
            concurrency,
            samples,
            document_samples,
            memory_samples,
            windows,
            states,
        )
        for batch_size, concurrency in measured_profiles
    ]
    return {
        "schema_version": 1,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "model": MODEL_NAME,
        "vector_dimension": VECTOR_DIMENSION,
        "configuration": {
            "base_url": config.base_url,
            "batch_sizes": list(config.batch_sizes),
            "concurrency_levels": list(config.concurrency_levels),
            "corpus_repetitions": config.corpus_repetitions,
            "warmup_rounds": config.warmup_rounds,
            "measurement_rounds": config.measurement_rounds,
            "timeout_seconds": config.timeout_seconds,
            "container_name": config.container_name,
            "memory_sample_interval_seconds": config.memory_sample_interval_seconds,
            "allow_overload_rejections": config.allow_overload_rejections,
        },
        "corpus": corpus_summary,
        "environment": environment,
        "stop_reason": stop_reason,
        "results": results,
        "recommendation": recommendation(results, config.concurrency_levels),
        "container_states": [asdict(state) for state in states],
        "request_samples": [asdict(sample) for sample in samples],
        "document_samples": [asdict(sample) for sample in document_samples],
    }


def write_payload(path: Path, payload: dict[str, Any]) -> None:
    """완료된 Profile이 Provider 장애 뒤에도 남도록 JSON을 원자적으로 교체한다."""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = path.with_suffix(path.suffix + ".tmp")
    temporary_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    temporary_path.replace(path)


def run_benchmark(config: SafetyBenchmarkConfig) -> dict[str, Any]:
    """안전한 Profile부터 실행하고 실패·OOM 시 결과를 보존한 뒤 확장을 중단한다."""
    assert_health(config)
    corpus_documents, corpus_summary = load_corpus(config.corpus_path)
    workload = corpus_documents * config.corpus_repetitions
    environment = collect_environment(config.container_name)
    samples: list[RequestSample] = []
    document_samples: list[DocumentSample] = []
    windows: list[ProfileWindow] = []
    states: list[ProfileContainerState] = []
    stop_reason: str | None = None

    # 1. 작은 입력부터 모델 Kernel을 준비하되 예열 결과는 본 통계에 포함하지 않는다.
    for warmup_round in range(config.warmup_rounds):
        for batch_size in config.batch_sizes:
            warmup = execute_request(
                config,
                workload[0][: min(batch_size, len(workload[0]))],
                batch_size,
                1,
                -(warmup_round + 1),
                0,
            )
            if not warmup.success:
                raise BenchmarkError(f"warm-up failed for batch {batch_size}: {warmup.error_type}")

    memory_sampler = ContainerMemorySampler(
        config.container_name,
        config.memory_sample_interval_seconds,
    )
    memory_sampler.start()
    try:
        # 2. Batch와 동시성을 작은 순서로 높여 실패 뒤 더 위험한 Profile이 실행되지 않게 한다.
        for batch_size in config.batch_sizes:
            for concurrency in config.concurrency_levels:
                profile_failed = False
                for round_index in range(config.measurement_rounds):
                    started_at = time.monotonic()
                    memory_sampler.sample_now()
                    round_samples, round_documents = run_round(
                        config, workload, batch_size, concurrency, round_index
                    )
                    samples.extend(round_samples)
                    document_samples.extend(round_documents)
                    memory_sampler.sample_now()
                    windows.append(ProfileWindow(
                        batch_size,
                        concurrency,
                        round_index,
                        started_at,
                        time.monotonic(),
                    ))
                    if has_unexpected_request_failure(
                        round_samples, config.allow_overload_rejections
                    ):
                        profile_failed = True
                        break

                state = inspect_container(config.container_name, batch_size, concurrency)
                states.append(state)
                if profile_failed:
                    stop_reason = f"REQUEST_FAILURE_BATCH_{batch_size}_CONCURRENCY_{concurrency}"
                elif state.oom_killed is True or state.running is False:
                    stop_reason = f"PROVIDER_STOPPED_BATCH_{batch_size}_CONCURRENCY_{concurrency}"

                # 3. Profile마다 Checkpoint를 교체해 OOM 이후에도 완료 지표와 실패 원인을 보존한다.
                write_payload(config.output_path, build_payload(
                    config,
                    corpus_summary,
                    environment,
                    samples,
                    document_samples,
                    memory_sampler.samples(),
                    windows,
                    states,
                    stop_reason,
                ))
                if stop_reason is not None:
                    break
            if stop_reason is not None:
                break
    finally:
        memory_sampler.stop()

    payload = build_payload(
        config,
        corpus_summary,
        environment,
        samples,
        document_samples,
        memory_sampler.samples(),
        windows,
        states,
        stop_reason,
    )
    write_payload(config.output_path, payload)
    return payload


def has_unexpected_request_failure(
    samples: Sequence[RequestSample],
    allow_overload_rejections: bool,
) -> bool:
    """Stress 모드에서만 HTTP 429를 보호 성공으로 인정하고 다른 실패는 모두 중단한다."""
    return any(
        not sample.success
        and (not allow_overload_rejections or sample.error_type != "HTTP_429")
        for sample in samples
    )


def validate_config(config: SafetyBenchmarkConfig) -> None:
    """실제 요청 전 Corpus와 안전성 측정 설정을 검증한다."""
    if not config.base_url.startswith(("http://", "https://")):
        raise BenchmarkError("base URL must use http or https")
    if not config.corpus_path.is_file():
        raise BenchmarkError("real PDF corpus file does not exist")
    if config.corpus_repetitions < 1 or config.warmup_rounds < 1 or config.measurement_rounds < 1:
        raise BenchmarkError("repetitions and rounds must be positive")
    if config.timeout_seconds <= 0 or config.memory_sample_interval_seconds <= 0:
        raise BenchmarkError("timeouts and sampling intervals must be positive")


def parse_args(argv: Sequence[str] | None = None) -> SafetyBenchmarkConfig:
    """CLI와 환경 변수에서 실제 PDF 안전성 Benchmark 설정을 읽는다."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--base-url",
        default=os.getenv("REAL_PDF_BENCHMARK_BASE_URL", "http://localhost:8000"),
    )
    parser.add_argument(
        "--corpus",
        type=Path,
        default=Path(os.getenv(
            "REAL_PDF_BENCHMARK_CORPUS",
            "build/reports/real-pdf-embedding/real-pdf-corpus.json",
        )),
    )
    parser.add_argument(
        "--batch-sizes",
        default=os.getenv("REAL_PDF_BENCHMARK_BATCH_SIZES", "4,8,16"),
    )
    parser.add_argument(
        "--concurrency-levels",
        default=os.getenv("REAL_PDF_BENCHMARK_CONCURRENCY_LEVELS", "1,2"),
    )
    parser.add_argument(
        "--corpus-repetitions",
        type=int,
        default=environment_int("REAL_PDF_BENCHMARK_CORPUS_REPETITIONS", 1),
    )
    parser.add_argument(
        "--warmup-rounds",
        type=int,
        default=environment_int("REAL_PDF_BENCHMARK_WARMUP_ROUNDS", 1),
    )
    parser.add_argument(
        "--measurement-rounds",
        type=int,
        default=environment_int("REAL_PDF_BENCHMARK_ROUNDS", 3),
    )
    parser.add_argument(
        "--timeout-seconds",
        type=float,
        default=environment_float("REAL_PDF_BENCHMARK_TIMEOUT_SECONDS", 60.0),
    )
    parser.add_argument(
        "--container-name",
        default=os.getenv("REAL_PDF_BENCHMARK_CONTAINER_NAME", "docgrid-embedding"),
    )
    parser.add_argument(
        "--memory-sample-interval-seconds",
        type=float,
        default=environment_float("REAL_PDF_BENCHMARK_MEMORY_SAMPLE_INTERVAL_SECONDS", 0.25),
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(os.getenv(
            "REAL_PDF_BENCHMARK_OUTPUT",
            "build/reports/real-pdf-embedding/real-pdf-safety.json",
        )),
    )
    parser.add_argument(
        "--allow-overload-rejections",
        action="store_true",
        help="continue stress rounds when every request failure is an expected HTTP 429",
    )
    args = parser.parse_args(argv)
    config = SafetyBenchmarkConfig(
        base_url=args.base_url,
        corpus_path=args.corpus,
        batch_sizes=parse_positive_integers(args.batch_sizes, maximum=64),
        concurrency_levels=parse_positive_integers(args.concurrency_levels),
        corpus_repetitions=args.corpus_repetitions,
        warmup_rounds=args.warmup_rounds,
        measurement_rounds=args.measurement_rounds,
        timeout_seconds=args.timeout_seconds,
        container_name=args.container_name.strip() or None,
        memory_sample_interval_seconds=args.memory_sample_interval_seconds,
        output_path=args.output,
        allow_overload_rejections=args.allow_overload_rejections,
    )
    validate_config(config)
    return config


def print_summary(payload: dict[str, Any]) -> None:
    """핵심 Profile 지표와 설정 추천을 Console 표로 출력한다."""
    print("Batch | concurrency | texts/s | p50 ms | p95 ms | p99 ms | peak RSS MiB | OOM | failures")
    print("---: | ---: | ---: | ---: | ---: | ---: | ---: | :---: | ---:")
    for result in payload["results"]:
        latency = result["request_latency_milliseconds"]
        peak_rss = result["rss"]["maximum_bytes"]
        print(
            f"{result['batch_size']} | {result['concurrency']} | "
            f"{format_number(result['throughput_texts_per_second'])} | "
            f"{format_number(latency['p50'])} | {format_number(latency['p95'])} | "
            f"{format_number(latency['p99'])} | "
            f"{format_number(None if peak_rss is None else peak_rss / (1024 * 1024))} | "
            f"{result['oom_killed']} | {result['failure_count']}"
        )
    print(json.dumps(payload["recommendation"], ensure_ascii=False))


def format_number(value: float | None) -> str:
    """누락 값을 구분하면서 성능 수치를 소수점 둘째 자리로 표시한다."""
    return "n/a" if value is None else f"{value:.2f}"


def main(argv: Sequence[str] | None = None) -> int:
    """실제 PDF 안전성 Benchmark를 실행하고 결과에 따라 종료 코드를 반환한다."""
    config = parse_args(argv)
    payload = run_benchmark(config)
    print_summary(payload)
    print(f"result: {config.output_path}")
    return 1 if payload["stop_reason"] is not None else 0


if __name__ == "__main__":
    raise SystemExit(main())

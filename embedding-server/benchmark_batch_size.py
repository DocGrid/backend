#!/usr/bin/env python3
"""실제 BGE-M3 HTTP 서버의 Batch Size별 처리량·지연·RSS를 비교한다."""

from __future__ import annotations

import argparse
import json
import math
import os
import platform
import subprocess
import threading
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Sequence
from urllib import error, request


MODEL_NAME = "BAAI/bge-m3"
VECTOR_DIMENSION = 1024
MAX_BATCH_SIZE = 64
CURRENT_DEFAULT_BATCH_SIZE = 16
DEFAULT_BATCH_SIZES = (1, 4, 8, 16, 32, 64)

BASE_TEXTS = (
    "DocGrid는 업로드한 문서를 작은 조각으로 나눈 뒤 벡터로 변환해 검색합니다.",
    "Worker는 대기 중인 작업을 Claim하고 Lease를 갱신하면서 인덱싱 파이프라인을 실행합니다.",
    "BGE-M3 임베딩은 의미가 비슷한 문장을 가까운 고차원 벡터로 표현합니다.",
    "PostgreSQL과 pgvector는 코사인 거리 기반의 문서 검색을 지원합니다.",
    "동일한 문서가 동시에 업로드돼도 하나의 진행 중 버전만 생성돼야 합니다.",
    "Embedding 결과는 모든 Chunk가 성공한 뒤 하나의 트랜잭션에서 저장됩니다.",
    "Lease가 만료된 작업은 오래된 Worker의 쓰기를 차단한 뒤 다시 대기열로 돌아갑니다.",
    "PDF 페이지와 DOCX 제목 정보는 검색 결과의 출처 메타데이터로 보존됩니다.",
    "Batch Size가 커지면 HTTP 왕복 횟수는 줄지만 요청 지연과 메모리는 증가할 수 있습니다.",
    "처리량과 꼬리 지연을 함께 측정해야 운영 기본값을 안전하게 선택할 수 있습니다.",
    "HNSW 인덱스는 정확 검색과 다른 속도 및 재현율의 절충점을 가집니다.",
    "장애가 발생해도 이미 검색 가능한 이전 문서 버전은 계속 유지돼야 합니다.",
    "관리자 조회 API는 작업과 시도 및 이벤트 이력을 노출하되 민감정보는 숨깁니다.",
    "실제 모델 성능은 CPU 아키텍처와 Torch Thread 수 및 입력 길이에 영향을 받습니다.",
    "공정한 성능 비교는 모든 Profile에서 같은 Text 집합과 같은 총 개수를 사용합니다.",
    "Warm-up 측정은 모델 초기 준비 비용을 본 측정 통계에서 분리하기 위해 필요합니다.",
)


class BenchmarkError(RuntimeError):
    """측정 계약이나 실제 Embedding 응답 계약이 깨졌음을 나타낸다."""


@dataclass(frozen=True)
class BenchmarkConfig:
    """한 번의 Batch Size Benchmark가 공유하는 재현 설정이다."""

    base_url: str
    batch_sizes: tuple[int, ...]
    total_texts: int
    warmup_rounds: int
    measurement_rounds: int
    timeout_seconds: float
    container_name: str | None
    memory_sample_interval_seconds: float
    output_path: Path


@dataclass(frozen=True)
class RequestSample:
    """단일 HTTP Batch 요청에서 관찰한 결과와 지연이다."""

    batch_size: int
    round_index: int
    request_index: int
    text_count: int
    latency_seconds: float
    success: bool
    error_type: str | None


@dataclass(frozen=True)
class MemorySample:
    """단조 시각 기준으로 수집한 Embedding Server RSS 표본이다."""

    monotonic_seconds: float
    rss_bytes: int


@dataclass(frozen=True)
class ProfileWindow:
    """특정 Batch Size가 실제 요청을 처리한 시간 구간이다."""

    batch_size: int
    started_at: float
    finished_at: float


class ContainerMemorySampler:
    """Benchmark와 독립된 Thread에서 Docker Container PID 1의 RSS를 표본화한다."""

    def __init__(self, container_name: str | None, interval_seconds: float):
        self._container_name = container_name
        self._interval_seconds = interval_seconds
        self._samples: list[MemorySample] = []
        self._lock = threading.Lock()
        self._stop_event = threading.Event()
        self._thread: threading.Thread | None = None
        self.unavailable_reason: str | None = None

    def start(self) -> None:
        """Container가 지정된 경우 첫 표본을 읽고 Background 수집을 시작한다."""
        if not self._container_name:
            self.unavailable_reason = "container name not configured"
            return

        self.sample_now()
        if self.unavailable_reason is not None:
            return

        self._thread = threading.Thread(
            target=self._sample_loop,
            name="bge-memory-sampler",
            daemon=True,
        )
        self._thread.start()

    def stop(self) -> None:
        """마지막 표본을 추가하고 Background 수집을 안전하게 종료한다."""
        if self._thread is None:
            return
        self._stop_event.set()
        self._thread.join(timeout=max(5.0, self._interval_seconds * 2))
        self.sample_now()

    def sample_now(self) -> None:
        """현재 RSS를 읽되 수집 실패가 성능 요청 자체를 중단시키지는 않게 한다."""
        if not self._container_name or self.unavailable_reason is not None:
            return
        try:
            rss_bytes = read_container_rss_bytes(self._container_name)
        except (OSError, subprocess.SubprocessError, ValueError) as exception:
            self.unavailable_reason = type(exception).__name__
            return

        with self._lock:
            self._samples.append(MemorySample(time.monotonic(), rss_bytes))

    def samples(self) -> tuple[MemorySample, ...]:
        """동시 수집 중인 내부 목록을 변경할 수 없는 Snapshot으로 반환한다."""
        with self._lock:
            return tuple(self._samples)

    def _sample_loop(self) -> None:
        while not self._stop_event.wait(self._interval_seconds):
            self.sample_now()


def parse_batch_sizes(raw_value: str) -> tuple[int, ...]:
    """쉼표 구분 Batch Size를 중복 없는 양의 정수 목록으로 변환한다."""
    try:
        values = tuple(int(value.strip()) for value in raw_value.split(","))
    except ValueError as exception:
        raise BenchmarkError("batch sizes must be comma-separated integers") from exception

    if not values or len(set(values)) != len(values):
        raise BenchmarkError("batch sizes must be non-empty and unique")
    if any(value < 1 or value > MAX_BATCH_SIZE for value in values):
        raise BenchmarkError(f"batch sizes must be between 1 and {MAX_BATCH_SIZE}")
    return values


def build_corpus(count: int) -> tuple[str, ...]:
    """입력 순서와 길이 분포가 재실행마다 같은 한국어 Text Corpus를 만든다."""
    if count < 1:
        raise BenchmarkError("total texts must be positive")
    return tuple(
        f"{BASE_TEXTS[index % len(BASE_TEXTS)]} 표본 번호 {index + 1}."
        for index in range(count)
    )


def percentile(values: Sequence[float], percentage: float) -> float:
    """정렬된 두 관측값 사이를 선형 보간해 지정 Percentile을 계산한다."""
    if not values:
        raise BenchmarkError("percentile requires at least one value")
    if percentage < 0.0 or percentage > 100.0:
        raise BenchmarkError("percentage must be between 0 and 100")

    ordered = sorted(values)
    rank = (len(ordered) - 1) * (percentage / 100.0)
    lower_index = math.floor(rank)
    upper_index = math.ceil(rank)
    if lower_index == upper_index:
        return ordered[lower_index]
    weight = rank - lower_index
    return ordered[lower_index] + (ordered[upper_index] - ordered[lower_index]) * weight


def rotated_batch_sizes(batch_sizes: Sequence[int], round_index: int) -> tuple[int, ...]:
    """Round마다 시작 Profile을 바꿔 Cache·열 상태의 순서 편향을 완화한다."""
    if not batch_sizes:
        raise BenchmarkError("batch sizes must not be empty")
    offset = round_index % len(batch_sizes)
    values = tuple(batch_sizes)
    return values[offset:] + values[:offset]


def validate_batch_response(
    payload: Any,
    expected_count: int,
    expected_model: str = MODEL_NAME,
    expected_dimension: int = VECTOR_DIMENSION,
) -> None:
    """실제 Batch 응답의 모델·순서·개수·Vector 계약을 저장 없이 검증한다."""
    if not isinstance(payload, dict) or payload.get("model") != expected_model:
        raise BenchmarkError("embedding response model mismatch")
    embeddings = payload.get("embeddings")
    if not isinstance(embeddings, list) or len(embeddings) != expected_count:
        raise BenchmarkError("embedding response count mismatch")

    for expected_index, item in enumerate(embeddings):
        if not isinstance(item, dict) or item.get("index") != expected_index:
            raise BenchmarkError("embedding response index mismatch")
        vector = item.get("vector")
        if not isinstance(vector, list) or len(vector) != expected_dimension:
            raise BenchmarkError("embedding vector dimension mismatch")
        if any(not isinstance(value, (int, float)) or not math.isfinite(value) for value in vector):
            raise BenchmarkError("embedding vector contains a non-finite value")


def execute_batch_request(
    config: BenchmarkConfig,
    texts: Sequence[str],
    batch_size: int,
    round_index: int,
    request_index: int,
    urlopen: Callable[..., Any] = request.urlopen,
) -> RequestSample:
    """실제 HTTP 응답 본문 수신·역직렬화·계약 검증까지의 지연을 측정한다."""
    body = json.dumps(
        {"texts": list(texts), "batch_size": batch_size},
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
        with urlopen(http_request, timeout=config.timeout_seconds) as response:
            payload = json.loads(response.read().decode("utf-8"))
        validate_batch_response(payload, len(texts))
        return RequestSample(
            batch_size=batch_size,
            round_index=round_index,
            request_index=request_index,
            text_count=len(texts),
            latency_seconds=time.perf_counter() - started_at,
            success=True,
            error_type=None,
        )
    except (BenchmarkError, error.URLError, TimeoutError, json.JSONDecodeError) as exception:
        return RequestSample(
            batch_size=batch_size,
            round_index=round_index,
            request_index=request_index,
            text_count=len(texts),
            latency_seconds=time.perf_counter() - started_at,
            success=False,
            error_type=type(exception).__name__,
        )


def assert_health(config: BenchmarkConfig) -> None:
    """Model Loading이 끝난 실제 서버만 본 측정에 진입하게 한다."""
    health_request = request.Request(f"{config.base_url.rstrip('/')}/health", method="GET")
    try:
        with request.urlopen(health_request, timeout=config.timeout_seconds) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except (error.URLError, TimeoutError, json.JSONDecodeError) as exception:
        raise BenchmarkError("embedding server health check failed") from exception
    if payload != {"status": "ok"}:
        raise BenchmarkError("embedding server is not ready")


def read_container_rss_bytes(container_name: str) -> int:
    """Docker Container PID 1의 `/proc/1/status`에서 현재 VmRSS를 Byte로 읽는다."""
    completed = subprocess.run(
        ["docker", "exec", container_name, "cat", "/proc/1/status"],
        check=True,
        capture_output=True,
        text=True,
        timeout=10,
    )
    for line in completed.stdout.splitlines():
        if line.startswith("VmRSS:"):
            parts = line.split()
            if len(parts) >= 2:
                return int(parts[1]) * 1024
    raise ValueError("VmRSS not found")


def collect_environment(container_name: str | None) -> dict[str, Any]:
    """성능 결과 해석에 필요한 Host와 선택적 Container Runtime 지문을 수집한다."""
    environment: dict[str, Any] = {
        "host_platform": platform.platform(),
        "host_machine": platform.machine(),
        "host_processor": platform.processor() or None,
        "host_logical_cpu_count": os.cpu_count(),
        "client_python_version": platform.python_version(),
    }
    if not container_name:
        environment["container"] = None
        return environment

    try:
        inspect_result = subprocess.run(
            ["docker", "inspect", container_name],
            check=True,
            capture_output=True,
            text=True,
            timeout=10,
        )
        inspect_payload = json.loads(inspect_result.stdout)[0]
        environment["container"] = {
            "name": container_name,
            "image": inspect_payload.get("Config", {}).get("Image"),
            "platform": inspect_payload.get("Platform"),
        }
    except (OSError, subprocess.SubprocessError, ValueError, KeyError, json.JSONDecodeError) as exception:
        environment["container"] = {"name": container_name, "inspect_error": type(exception).__name__}
        return environment

    version_script = (
        "import importlib.metadata as m,json,platform,torch,transformers;"
        "print(json.dumps({'python':platform.python_version(),"
        "'torch':torch.__version__,'transformers':transformers.__version__,"
        "'flag_embedding':m.version('FlagEmbedding'),'torch_threads':torch.get_num_threads(),"
        "'cuda_available':torch.cuda.is_available()}))"
    )
    try:
        version_result = subprocess.run(
            ["docker", "exec", container_name, "python3", "-c", version_script],
            check=True,
            capture_output=True,
            text=True,
            timeout=30,
        )
        environment["container"]["runtime"] = json.loads(version_result.stdout)
    except (OSError, subprocess.SubprocessError, json.JSONDecodeError) as exception:
        environment["container"]["runtime_error"] = type(exception).__name__
    return environment


def summarize_profile(
    batch_size: int,
    samples: Sequence[RequestSample],
    memory_samples: Sequence[MemorySample],
    windows: Sequence[ProfileWindow],
) -> dict[str, Any]:
    """한 Batch Size의 Request·Text·Latency·RSS 관측치를 비교 가능한 지표로 집계한다."""
    profile_samples = [sample for sample in samples if sample.batch_size == batch_size]
    successes = [sample for sample in profile_samples if sample.success]
    failures = [sample for sample in profile_samples if not sample.success]
    latencies = [sample.latency_seconds for sample in successes]
    total_texts = sum(sample.text_count for sample in successes)
    latency_sum = sum(latencies)

    profile_windows = [window for window in windows if window.batch_size == batch_size]
    rss_values = [
        sample.rss_bytes
        for sample in memory_samples
        if any(
            window.started_at <= sample.monotonic_seconds <= window.finished_at
            for window in profile_windows
        )
    ]

    return {
        "batch_size": batch_size,
        "request_count": len(profile_samples),
        "success_count": len(successes),
        "failure_count": len(failures),
        "failure_rate": len(failures) / len(profile_samples) if profile_samples else 1.0,
        "successful_text_count": total_texts,
        "throughput_texts_per_second": total_texts / latency_sum if latency_sum > 0 else None,
        "request_latency_milliseconds": {
            "sample_count": len(latencies),
            "p50": percentile(latencies, 50) * 1000 if latencies else None,
            "p95": percentile(latencies, 95) * 1000 if latencies else None,
            "p99": percentile(latencies, 99) * 1000 if latencies else None,
            "max": max(latencies) * 1000 if latencies else None,
        },
        "average_latency_milliseconds_per_text": (
            latency_sum * 1000 / total_texts if total_texts > 0 else None
        ),
        "rss": {
            "sample_count": len(rss_values),
            "minimum_bytes": min(rss_values) if rss_values else None,
            "maximum_bytes": max(rss_values) if rss_values else None,
            "delta_bytes": max(rss_values) - min(rss_values) if rss_values else None,
        },
        "errors": sorted({sample.error_type for sample in failures if sample.error_type}),
    }


def recommendation(results: Sequence[dict[str, Any]]) -> dict[str, Any]:
    """실패 없는 Profile의 처리량만으로 현재 기본값 검토 신호를 계산한다."""
    eligible = [
        result
        for result in results
        if result["failure_count"] == 0 and result["throughput_texts_per_second"] is not None
    ]
    if not eligible:
        return {
            "decision": "INSUFFICIENT_DATA",
            "current_default_batch_size": CURRENT_DEFAULT_BATCH_SIZE,
        }

    fastest = max(eligible, key=lambda result: result["throughput_texts_per_second"])
    current = next(
        (result for result in eligible if result["batch_size"] == CURRENT_DEFAULT_BATCH_SIZE),
        None,
    )
    if current is None:
        return {
            "decision": "CURRENT_DEFAULT_NOT_MEASURED",
            "current_default_batch_size": CURRENT_DEFAULT_BATCH_SIZE,
            "fastest_batch_size": fastest["batch_size"],
        }

    throughput_ratio = (
        current["throughput_texts_per_second"] / fastest["throughput_texts_per_second"]
    )
    return {
        "decision": "KEEP_DEFAULT" if throughput_ratio >= 0.95 else "REVIEW_CHANGE",
        "current_default_batch_size": CURRENT_DEFAULT_BATCH_SIZE,
        "fastest_batch_size": fastest["batch_size"],
        "current_to_fastest_throughput_ratio": throughput_ratio,
        "policy": "현재 기본값이 최고 처리량의 95% 이상이면 유지한다.",
    }


def run_benchmark(config: BenchmarkConfig) -> dict[str, Any]:
    """Warm-up, 회전 순서 본 측정, RSS 수집과 결과 집계를 순서대로 실행한다."""
    assert_health(config)
    corpus = build_corpus(config.total_texts)
    memory_sampler = ContainerMemorySampler(
        config.container_name,
        config.memory_sample_interval_seconds,
    )
    samples: list[RequestSample] = []
    windows: list[ProfileWindow] = []

    # 1. 모델 적재 비용과 첫 Kernel 준비 비용을 본 측정에서 분리한다.
    for warmup_round in range(config.warmup_rounds):
        for batch_size in config.batch_sizes:
            sample = execute_batch_request(
                config,
                corpus[:batch_size],
                batch_size,
                round_index=-(warmup_round + 1),
                request_index=0,
            )
            if not sample.success:
                raise BenchmarkError(
                    f"warm-up failed for batch size {batch_size}: {sample.error_type}"
                )

    memory_sampler.start()
    try:
        # 2. Round마다 Profile 시작 순서를 회전하면서 모든 Batch가 같은 Corpus를 처리한다.
        for round_index in range(config.measurement_rounds):
            for batch_size in rotated_batch_sizes(config.batch_sizes, round_index):
                profile_started_at = time.monotonic()
                memory_sampler.sample_now()
                for request_index, start in enumerate(range(0, len(corpus), batch_size)):
                    samples.append(
                        execute_batch_request(
                            config,
                            corpus[start : start + batch_size],
                            batch_size,
                            round_index=round_index,
                            request_index=request_index,
                        )
                    )
                memory_sampler.sample_now()
                windows.append(
                    ProfileWindow(batch_size, profile_started_at, time.monotonic())
                )
    finally:
        memory_sampler.stop()

    # 3. Vector 원문은 버리고 Profile별 비교 지표와 실행 환경만 구조화한다.
    memory_samples = memory_sampler.samples()
    results = [
        summarize_profile(batch_size, samples, memory_samples, windows)
        for batch_size in config.batch_sizes
    ]
    return {
        "schema_version": 1,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "model": MODEL_NAME,
        "vector_dimension": VECTOR_DIMENSION,
        "configuration": {
            "base_url": config.base_url,
            "batch_sizes": list(config.batch_sizes),
            "total_texts_per_profile_per_round": config.total_texts,
            "warmup_rounds": config.warmup_rounds,
            "measurement_rounds": config.measurement_rounds,
            "timeout_seconds": config.timeout_seconds,
            "container_name": config.container_name,
            "memory_sample_interval_seconds": config.memory_sample_interval_seconds,
        },
        "environment": collect_environment(config.container_name),
        "memory_sampling_error": memory_sampler.unavailable_reason,
        "results": results,
        "recommendation": recommendation(results),
        "request_samples": [asdict(sample) for sample in samples],
    }


def validate_config(config: BenchmarkConfig) -> None:
    """불공정하거나 실행 불가능한 Benchmark 설정을 요청 전에 거부한다."""
    if not config.base_url.startswith(("http://", "https://")):
        raise BenchmarkError("base URL must use http or https")
    if config.warmup_rounds < 1 or config.measurement_rounds < 1:
        raise BenchmarkError("warm-up and measurement rounds must be positive")
    if config.timeout_seconds <= 0 or config.memory_sample_interval_seconds <= 0:
        raise BenchmarkError("timeout and memory sample interval must be positive")
    if config.total_texts < max(config.batch_sizes):
        raise BenchmarkError("total texts must be at least the largest batch size")
    if any(config.total_texts % batch_size != 0 for batch_size in config.batch_sizes):
        raise BenchmarkError("total texts must be divisible by every batch size")


def parse_args(argv: Sequence[str] | None = None) -> BenchmarkConfig:
    """CLI와 환경 변수에서 재현 가능한 Benchmark 설정을 읽는다."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--base-url",
        default=os.getenv("BGE_BENCHMARK_BASE_URL", "http://localhost:8000"),
    )
    parser.add_argument(
        "--batch-sizes",
        default=os.getenv("BGE_BENCHMARK_BATCH_SIZES", "1,4,8,16,32,64"),
    )
    parser.add_argument(
        "--total-texts",
        type=int,
        default=int(os.getenv("BGE_BENCHMARK_TOTAL_TEXTS", "64")),
    )
    parser.add_argument(
        "--warmup-rounds",
        type=int,
        default=int(os.getenv("BGE_BENCHMARK_WARMUP_ROUNDS", "1")),
    )
    parser.add_argument(
        "--measurement-rounds",
        type=int,
        default=int(os.getenv("BGE_BENCHMARK_ROUNDS", "3")),
    )
    parser.add_argument(
        "--timeout-seconds",
        type=float,
        default=float(os.getenv("BGE_BENCHMARK_TIMEOUT_SECONDS", "300")),
    )
    parser.add_argument(
        "--container-name",
        default=os.getenv("BGE_BENCHMARK_CONTAINER_NAME", "docgrid-embedding"),
    )
    parser.add_argument(
        "--memory-sample-interval-seconds",
        type=float,
        default=float(os.getenv("BGE_BENCHMARK_MEMORY_SAMPLE_INTERVAL_SECONDS", "1")),
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(
            os.getenv(
                "BGE_BENCHMARK_OUTPUT",
                "build/reports/bge-m3-batch/bge-m3-batch-latest.json",
            )
        ),
    )
    args = parser.parse_args(argv)
    container_name = args.container_name.strip() or None
    config = BenchmarkConfig(
        base_url=args.base_url,
        batch_sizes=parse_batch_sizes(args.batch_sizes),
        total_texts=args.total_texts,
        warmup_rounds=args.warmup_rounds,
        measurement_rounds=args.measurement_rounds,
        timeout_seconds=args.timeout_seconds,
        container_name=container_name,
        memory_sample_interval_seconds=args.memory_sample_interval_seconds,
        output_path=args.output,
    )
    validate_config(config)
    return config


def format_milliseconds(value: float | None) -> str:
    """누락 값을 구분하면서 Millisecond 지표를 표 문자열로 바꾼다."""
    return "n/a" if value is None else f"{value:.2f}"


def print_summary(payload: dict[str, Any]) -> None:
    """저장한 JSON과 같은 핵심 지표를 Console 비교 표로 출력한다."""
    print("Batch | texts/s | p50 ms | p95 ms | p99 ms | ms/text | peak RSS MiB | failures")
    print("---: | ---: | ---: | ---: | ---: | ---: | ---: | ---:")
    for result in payload["results"]:
        latency = result["request_latency_milliseconds"]
        peak_rss = result["rss"]["maximum_bytes"]
        peak_rss_mib = None if peak_rss is None else peak_rss / (1024 * 1024)
        throughput = result["throughput_texts_per_second"]
        print(
            f"{result['batch_size']} | "
            f"{format_milliseconds(throughput)} | "
            f"{format_milliseconds(latency['p50'])} | "
            f"{format_milliseconds(latency['p95'])} | "
            f"{format_milliseconds(latency['p99'])} | "
            f"{format_milliseconds(result['average_latency_milliseconds_per_text'])} | "
            f"{format_milliseconds(peak_rss_mib)} | "
            f"{result['failure_count']}"
        )
    print(json.dumps(payload["recommendation"], ensure_ascii=False))


def main(argv: Sequence[str] | None = None) -> int:
    """Benchmark를 실행하고 원본 JSON을 남긴 뒤 실패 요청 유무를 Exit Code에 반영한다."""
    config = parse_args(argv)
    payload = run_benchmark(config)
    config.output_path.parent.mkdir(parents=True, exist_ok=True)
    config.output_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print_summary(payload)
    print(f"result: {config.output_path}")
    return 1 if any(result["failure_count"] > 0 for result in payload["results"]) else 0


if __name__ == "__main__":
    raise SystemExit(main())

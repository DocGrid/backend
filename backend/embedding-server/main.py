import math
import os
import threading
from contextlib import asynccontextmanager, contextmanager
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field, field_validator
from FlagEmbedding import BGEM3FlagModel
import logging

logger = logging.getLogger(__name__)

MODEL_NAME = "BAAI/bge-m3"
MAX_BATCH_SIZE = 64
OVERLOAD_CODE = "EMBEDDING_PROVIDER_OVERLOADED"


def _positive_int_environment(name: str, default: int) -> int:
    """환경 변수에서 양의 정수를 읽고 시작 전에 잘못된 안전 설정을 거부한다."""
    value = int(os.getenv(name, str(default)))
    if value < 1:
        raise ValueError(f"{name} must be positive")
    return value


def _non_negative_int_environment(name: str, default: int) -> int:
    """환경 변수에서 0 이상의 정수를 읽어 대기열 비활성화도 명시적으로 허용한다."""
    value = int(os.getenv(name, str(default)))
    if value < 0:
        raise ValueError(f"{name} must not be negative")
    return value


def _positive_float_environment(name: str, default: float) -> float:
    """환경 변수에서 양의 실수를 읽고 무제한 대기 설정을 차단한다."""
    value = float(os.getenv(name, str(default)))
    if not math.isfinite(value) or value <= 0:
        raise ValueError(f"{name} must be positive")
    return value


PROVIDER_MAX_CONCURRENCY = _positive_int_environment(
    "EMBEDDING_PROVIDER_MAX_CONCURRENCY", 1
)
PROVIDER_MAX_QUEUE_SIZE = _non_negative_int_environment(
    "EMBEDDING_PROVIDER_MAX_QUEUE_SIZE", 1
)
PROVIDER_QUEUE_WAIT_TIMEOUT_SECONDS = _positive_float_environment(
    "EMBEDDING_PROVIDER_QUEUE_WAIT_TIMEOUT_SECONDS", 15.0
)


class ProviderOverloadedError(RuntimeError):
    """Provider 실행 permit을 bounded queue 정책 안에서 얻지 못한 과부하를 표현한다."""

    def __init__(self, reason: str, retry_after_seconds: float):
        super().__init__(reason)
        self.retry_after_seconds = retry_after_seconds


class ProviderAdmissionController:
    """한 Provider 프로세스의 모델 실행 동시성과 대기 요청 수를 함께 제한한다.

    모델 실행 permit만 소유하며 HTTP 응답과 모델 결과 계약에는 관여하지 않는다. 현재 Docker
    구성처럼 Uvicorn 단일 프로세스에서 컨테이너 전체 제한으로 동작한다.
    """

    def __init__(
        self,
        max_concurrency: int,
        max_queue_size: int,
        queue_wait_timeout_seconds: float,
    ):
        if max_concurrency < 1 or max_queue_size < 0 or queue_wait_timeout_seconds <= 0:
            raise ValueError("provider admission settings are invalid")
        self._permits = threading.BoundedSemaphore(max_concurrency)
        self._max_queue_size = max_queue_size
        self._queue_wait_timeout_seconds = queue_wait_timeout_seconds
        self._state_lock = threading.Lock()
        self._waiting = 0

    @contextmanager
    def admission(self):
        """즉시 permit 또는 제한된 대기 자리를 확보하고 종료 시 permit을 반드시 반환한다."""
        acquired = self._permits.acquire(blocking=False)
        if not acquired:
            # 1. 대기 카운터를 Lock 안에서 선점해 요청 폭주가 Queue 상한을 넘지 않게 한다.
            with self._state_lock:
                if self._waiting >= self._max_queue_size:
                    raise ProviderOverloadedError(
                        "queue_full", self._queue_wait_timeout_seconds
                    )
                self._waiting += 1

            try:
                # 2. 문서 read timeout보다 짧게 기다려 아직 계산 중인 요청 뒤에 무한히 쌓이지 않게 한다.
                acquired = self._permits.acquire(
                    timeout=self._queue_wait_timeout_seconds
                )
            finally:
                with self._state_lock:
                    self._waiting -= 1

            if not acquired:
                raise ProviderOverloadedError(
                    "queue_wait_timeout", self._queue_wait_timeout_seconds
                )

        try:
            # 3. 실제 모델 실행 구간만 permit으로 보호해 응답 직렬화 비용은 포함하지 않는다.
            yield
        finally:
            self._permits.release()

    def waiting_count(self) -> int:
        """동시성 계약 테스트와 진단을 위해 현재 대기 요청 수의 일관된 Snapshot을 반환한다."""
        with self._state_lock:
            return self._waiting


model: BGEM3FlagModel | None = None
provider_admission_controller = ProviderAdmissionController(
    PROVIDER_MAX_CONCURRENCY,
    PROVIDER_MAX_QUEUE_SIZE,
    PROVIDER_QUEUE_WAIT_TIMEOUT_SECONDS,
)


def _load_model():
    global model
    logger.info("Loading %s model...", MODEL_NAME)
    model = BGEM3FlagModel(MODEL_NAME, use_fp16=True)
    logger.info("Model loaded.")


@asynccontextmanager
async def lifespan(app: FastAPI):
    thread = threading.Thread(target=_load_model, daemon=True)
    thread.start()
    yield
    global model
    model = None


app = FastAPI(lifespan=lifespan)


class EmbedRequest(BaseModel):
    text: str


class EmbedResponse(BaseModel):
    vector: list[float]


class EmbedBatchRequest(BaseModel):
    """여러 문서 Chunk의 원문 순서와 모델 내부 Batch 크기를 전달한다."""

    texts: list[str] = Field(min_length=1, max_length=MAX_BATCH_SIZE)
    batch_size: int = Field(ge=1, le=MAX_BATCH_SIZE)

    @field_validator("texts")
    @classmethod
    def validate_texts(cls, texts: list[str]) -> list[str]:
        if any(not text.strip() for text in texts):
            raise ValueError("texts must not contain blank values")
        return texts


class EmbedBatchItemResponse(BaseModel):
    """요청 목록의 위치와 해당 위치에서 생성된 Dense Vector를 결합한다."""

    index: int
    vector: list[float]


class EmbedBatchResponse(BaseModel):
    """실제 사용 모델과 요청 순서를 보존한 Batch Embedding 결과를 반환한다."""

    model: str
    embeddings: list[EmbedBatchItemResponse]


def _encode(texts: list[str], batch_size: int) -> list[list[float]]:
    """입력 전체를 한 번에 Encoding하고 Dense Vector 개수 계약을 검증한다."""
    if model is None:
        raise HTTPException(status_code=503, detail="Model not loaded")

    try:
        with provider_admission_controller.admission():
            result = model.encode(texts, batch_size=batch_size, max_length=8192)
        dense_vectors = result.get("dense_vecs") if isinstance(result, dict) else None
        if dense_vectors is None or len(dense_vectors) != len(texts):
            raise ValueError("dense vector count mismatch")

        # Provider 배열 형식과 상관없이 JSON 직렬화 가능한 float 목록으로 경계를 고정한다.
        return [
            vector.tolist() if hasattr(vector, "tolist") else list(vector)
            for vector in dense_vectors
        ]
    except HTTPException:
        raise
    except ProviderOverloadedError as exception:
        logger.warning("Embedding request rejected. reason=%s", str(exception))
        raise HTTPException(
            status_code=429,
            detail={
                "code": OVERLOAD_CODE,
                "message": "Embedding provider is overloaded",
            },
            headers={
                "Retry-After": str(math.ceil(exception.retry_after_seconds))
            },
        ) from exception
    except Exception as exception:
        logger.error("Embedding generation failed. cause=%s", type(exception).__name__)
        raise HTTPException(status_code=500, detail="Embedding generation failed") from exception


@app.get("/health")
def health():
    if model is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    return {"status": "ok"}


@app.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest):
    vector = _encode([req.text], batch_size=1)[0]
    return EmbedResponse(vector=vector)


@app.post("/embed/batch", response_model=EmbedBatchResponse)
def embed_batch(req: EmbedBatchRequest):
    vectors = _encode(req.texts, batch_size=req.batch_size)
    return EmbedBatchResponse(
        model=MODEL_NAME,
        embeddings=[
            EmbedBatchItemResponse(index=index, vector=vector)
            for index, vector in enumerate(vectors)
        ],
    )

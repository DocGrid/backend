import threading
from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field, field_validator
from FlagEmbedding import BGEM3FlagModel
import logging

logger = logging.getLogger(__name__)

MODEL_NAME = "BAAI/bge-m3"
MAX_BATCH_SIZE = 64

model: BGEM3FlagModel | None = None


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

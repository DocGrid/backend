from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from FlagEmbedding import BGEM3FlagModel
import logging

logger = logging.getLogger(__name__)

model: BGEM3FlagModel | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global model
    logger.info("Loading BAAI/bge-m3 model...")
    model = BGEM3FlagModel("BAAI/bge-m3", use_fp16=True)
    logger.info("Model loaded.")
    yield
    model = None


app = FastAPI(lifespan=lifespan)


class EmbedRequest(BaseModel):
    text: str


class EmbedResponse(BaseModel):
    vector: list[float]


@app.get("/health")
def health():
    if model is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    return {"status": "ok"}


@app.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest):
    if model is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    result = model.encode([req.text], batch_size=1, max_length=8192)
    vector = result["dense_vecs"][0].tolist()
    return EmbedResponse(vector=vector)

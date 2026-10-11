"""Dich vu nhan dien anh cho ScanIt: nhan anh, tra vector 512 chieu. Backend Java luu va tim kiem bang pgvector."""
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, File, HTTPException, UploadFile

from embedder import DIM, BadImage, ClipEmbedder, FakeEmbedder

MAX_BYTES = 15 * 1024 * 1024
state: dict = {}


@asynccontextmanager
async def lifespan(_: FastAPI):
    # RECOGNIZER_FAKE=1 chi de thu quy trinh khi chua tai duoc model; ket qua KHONG co y nghia
    state["embedder"] = FakeEmbedder() if os.getenv("RECOGNIZER_FAKE") == "1" else ClipEmbedder()
    yield


app = FastAPI(title="scanit-recognizer", lifespan=lifespan)


@app.get("/health")
def health():
    e = state.get("embedder")
    return {"ok": e is not None, "model": getattr(e, "model_name", None), "dim": DIM}


@app.post("/embed")
async def embed(file: UploadFile = File(...)):
    data = await file.read(MAX_BYTES + 1)
    if not data:
        raise HTTPException(400, "Anh rong")
    if len(data) > MAX_BYTES:
        raise HTTPException(413, "Anh qua lon")
    e = state["embedder"]
    try:
        # model chay CPU (blocking): de FastAPI chay trong threadpool
        vec = await _run(e.embed, data)
    except BadImage:
        raise HTTPException(400, "Khong doc duoc anh")
    return {"model": e.model_name, "dim": len(vec), "embedding": vec}


async def _run(fn, *args):
    from starlette.concurrency import run_in_threadpool

    return await run_in_threadpool(fn, *args)

"""Tinh vector dac trung (embedding) cua anh. Khong phu thuoc FastAPI de test duoc rieng."""
from __future__ import annotations

import hashlib
import io
import math
from typing import Protocol

DIM = 512
MAX_SIDE = 1024        # thu nho anh qua lon truoc khi dua vao model (nhanh hon, khong anh huong ket qua)
MAX_PIXELS = 60_000_000


class Embedder(Protocol):
    model_name: str

    def embed(self, image_bytes: bytes) -> list[float]: ...


class BadImage(ValueError):
    pass


def load_image(image_bytes: bytes):
    """Doc anh, xoay theo EXIF, doi sang RGB. Nem BadImage neu khong doc duoc."""
    from PIL import Image, ImageOps

    Image.MAX_IMAGE_PIXELS = MAX_PIXELS
    try:
        img = Image.open(io.BytesIO(image_bytes))
        img.load()
        img = ImageOps.exif_transpose(img).convert("RGB")
    except Exception as e:  # PIL nem nhieu loai loi khac nhau
        raise BadImage("Khong doc duoc anh") from e
    if max(img.size) > MAX_SIDE:
        img.thumbnail((MAX_SIDE, MAX_SIDE))
    return img


def l2_normalize(v: list[float]) -> list[float]:
    n = math.sqrt(sum(x * x for x in v))
    if n == 0:
        raise ValueError("vector 0")
    return [x / n for x in v]


class ClipEmbedder:
    """open_clip ViT-B-32 (laion2b), 512 chieu, chay CPU. Tai model lan dau tu HuggingFace roi cache."""

    model_name = "clip-vit-b-32-laion2b"

    def __init__(self) -> None:
        import open_clip
        import torch

        self._torch = torch
        torch.set_num_threads(max(1, torch.get_num_threads()))
        self._model, _, self._preprocess = open_clip.create_model_and_transforms(
            "ViT-B-32", pretrained="laion2b_s34b_b79k"
        )
        self._model.eval()

    def embed(self, image_bytes: bytes) -> list[float]:
        img = load_image(image_bytes)
        tensor = self._preprocess(img).unsqueeze(0)
        with self._torch.no_grad():
            feat = self._model.encode_image(tensor)
            feat = feat / feat.norm(dim=-1, keepdim=True)
        return feat[0].tolist()


class FakeEmbedder:
    """Dung cho test: vector xac dinh tu noi dung anh (cung anh -> cung vector). KHONG nhan dien that."""

    model_name = "fake-test"

    def embed(self, image_bytes: bytes) -> list[float]:
        load_image(image_bytes)
        out: list[float] = []
        seed = hashlib.sha256(image_bytes).digest()
        while len(out) < DIM:
            seed = hashlib.sha256(seed).digest()
            out.extend((b - 127.5) for b in seed)
        return l2_normalize(out[:DIM])

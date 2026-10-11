import io
import math
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from PIL import Image  # noqa: E402

from embedder import DIM, BadImage, FakeEmbedder, load_image  # noqa: E402


def png(color, size=(64, 48)):
    buf = io.BytesIO()
    Image.new("RGB", size, color).save(buf, "PNG")
    return buf.getvalue()


class EmbedderTest(unittest.TestCase):
    def test_dim_and_unit_norm(self):
        v = FakeEmbedder().embed(png("red"))
        self.assertEqual(len(v), DIM)
        self.assertAlmostEqual(math.sqrt(sum(x * x for x in v)), 1.0, places=5)

    def test_deterministic_and_distinct(self):
        e = FakeEmbedder()
        self.assertEqual(e.embed(png("red")), e.embed(png("red")))
        self.assertNotEqual(e.embed(png("red")), e.embed(png("blue")))

    def test_bad_image(self):
        with self.assertRaises(BadImage):
            FakeEmbedder().embed(b"not an image")

    def test_large_image_is_downscaled(self):
        img = load_image(png("green", (3000, 2000)))
        self.assertLessEqual(max(img.size), 1024)

    def test_exif_rotation_applied(self):
        im = Image.new("RGB", (40, 20), "white")
        exif = Image.Exif()
        exif[0x0112] = 6  # xoay 90 do
        buf = io.BytesIO()
        im.save(buf, "JPEG", exif=exif)
        self.assertEqual(load_image(buf.getvalue()).size, (20, 40))


if __name__ == "__main__":
    unittest.main()

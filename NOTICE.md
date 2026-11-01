# Сторонні матеріали · Third-party material

Svitlyna's own code is licensed under GPL-3.0 (see `LICENSE`).

- `app/src/main/assets/ocr/*.onnx` and `*_dict.txt` — PP-OCRv5 mobile text detection and East Slavic /
  Latin text recognition models by PaddlePaddle (PaddleOCR), Apache License 2.0, converted to ONNX
  unchanged in weights (see `app/src/main/assets/ocr/SOURCES.json`, `SHA256SUMS` and
  `scripts/ocr_models.py`). The same files as in Pysanka.
- `app/src/main/java/ua/svitlyna/ocr/TextRecognizer.kt` and `scripts/ocr_models.py` — from Pysanka
  (same author), Apache License 2.0.
- ONNX Runtime (Microsoft, MIT) runs the text models.
- AndroidX, Jetpack Compose, Media3 and ExifInterface (Google, Apache License 2.0); Kotlin and kotlinx
  coroutines (JetBrains, Apache License 2.0) are used as dependencies under their own licences.

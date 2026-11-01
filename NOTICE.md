# Сторонні матеріали · Third-party material

Svitlyna's own code is licensed under GPL-3.0 (see `LICENSE`).

- `app/src/main/assets/ocr/*.onnx` and `*_dict.txt` — PP-OCRv5 mobile text detection and East Slavic /
  Latin text recognition models by PaddlePaddle (PaddleOCR), Apache License 2.0, converted to ONNX
  unchanged in weights (see `app/src/main/assets/ocr/SOURCES.json`, `SHA256SUMS` and
  `scripts/ocr_models.py`). The same files as in Pysanka.
- `app/src/main/java/ua/svitlyna/ocr/TextRecognizer.kt` and `scripts/ocr_models.py` — from Pysanka
  (same author), Apache License 2.0; the recogniser here also stops between lines when cancelled.
- **The app's interface is based on Iris Gallery** (https://github.com/MohamadOday/iris-gallery,
  Copyright 2026 Mohamad Oday), Apache License 2.0: the code in `ua.svitlyna` (root), `ua.svitlyna.ui`,
  `ua.svitlyna.data` and `ua.svitlyna.decoder` comes from Iris Gallery (version of November 2026),
  moved to the package `ua.svitlyna` and changed by Svitlyna: the phone's own lock instead of a PIN,
  an encrypted vault (`data/VaultCrypto.kt`, `data/VaultProvider.kt`), copies and metadata following
  Svitlyna's rules, safe video cleaning, text on photos and search, screenshot tools, Ukrainian.
  Its Ukrainian translation is Svitlyna's. Iris Gallery's licence applies to those parts; its full
  text is in `LICENSES/Apache-2.0-Iris-Gallery.txt` (Iris Gallery has no NOTICE file of its own).
  Files from Iris Gallery were modified by Svitlyna (November 2026).
- `app/src/main/java/ua/svitlyna/edit/EditorCanvasView.kt` — the editor canvas from Iris Gallery
  (https://github.com/MohamadOday/iris-gallery, Mohamad Oday), Apache License 2.0, unchanged except
  for the package. `edit/EditorRender.kt` adapts its rendering of colours, brushes, shapes and text.
  `media/Similar.kt` follows Iris Gallery's way of grouping similar photos (difference hash, six bands);
  `media/VideoClean.kt` follows the idea of its MP4 metadata stripper (boxes are emptied in place here).
- ONNX Runtime (Microsoft, MIT) runs the text models.
- Coil (Apache License 2.0), libavif for Android (BSD), Tink (Google, Apache License 2.0) for the
  vault's encryption.
- SQLCipher for Android (Zetetic LLC, BSD-style licence) encrypts the search database; it includes
  OpenSSL's libcrypto (Apache License 2.0). androidx.sqlite (Google, Apache License 2.0).
- AndroidX, Jetpack Compose, Media3 and ExifInterface (Google, Apache License 2.0); Kotlin and kotlinx
  coroutines (JetBrains, Apache License 2.0) are used as dependencies under their own licences.

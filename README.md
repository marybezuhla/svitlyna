# Світлина · Svitlyna

Приватна галерея для Android: усе на телефоні, без інтернету, акаунтів і реклами. Текст на фото, пошук українською, обличчя, місця й локальний ШІ — лише на телефоні. Українська та англійська. Безкоштовно.

> **Статус: тестова версія 0.5.1** — інтерфейс на основі Iris Gallery. Працюють пошук за текстом на фото українською, назвами й датами, шкала й папки, перегляд, текст на фото, редактор, «перед надсиланням», скріншоти, дублікати й схожі фото, зміна дати й місця, віджет «Цього дня» й блокування. Світлину можна зробити основною галереєю: вона відкриває фото з інших застосунків і є вибором фото для них. Далі — пошук за змістом, обличчя, карти. Завантажити: [Releases](../../releases). Що будуємо — [SPEC.md](SPEC.md), чому саме так — [DECISIONS.md](DECISIONS.md). Ідеї й побажання: [Issues](../../issues).

## Що буде

- 🔒 **Приватність.** Дозволу на інтернет немає; збірка зупиниться, якщо він з'явиться. Типово доступ лише до фото й відео. Зашифровані сейф і база.
- 🔤 **Текст на фото,** як на iPhone: виділити, скопіювати, перекласти.
- 🔍 **Пошук українською:** текст на фото миттєво («Шевченко» знайде й «Шевченка»), за змістом («кіт на дивані»), за людьми, місцями й датами.
- 📸 **Скріншоти:** без рядка стану одним дотиком, склеювання довгих скріншотів, розмиття особистого.
- 📤 **Безпечне надсилання:** без геолокації, перевірка перед публікацією, формати для соцмереж.
- 🗺️ **Місця й карти без інтернету,** подорожі й маршрути, приватні зони.
- ✨ **Редактор:** краса, відновлення старих і розмитих фото, вирізати об'єкт, наліпки й векторні емодзі.
- 📖 **Гарні альбоми:** фотокнига PDF з картами й текстом.
- 🧳 **Режим поїздки** і зашифрована резервна копія для перенесення на новий телефон.
- 🗓️ **Метадані не губляться:** відредаговане фото лишається на своєму місці в шкалі.

## Прозорість

- **Підпис:** справжній APK буде підписано сертифікатом із відбитком SHA-256 `53027b1a87085eb9e40a74820ff2aa067dd3350a0e3010a8f6c559e04a628630`.
- **Збірка:** APK збиратиметься з цього коду в GitHub Actions і підписуватиметься лише після схвалення авторки.
- **Основа — Iris Gallery:** інтерфейс і більшість функцій — з [Iris Gallery](https://github.com/MohamadOday/iris-gallery) (Apache 2.0), доповнені Світлиною; див. [NOTICE.md](NOTICE.md).
- **Як створено:** код пишеться за допомогою ШІ (Claude, Anthropic) під керівництвом і з тестуванням авторки.

Kotlin · Jetpack Compose. Ліцензія GPL-3.0.

---

# Svitlyna · English

A private gallery for Android: everything on the phone, no internet, no accounts, no ads. Text in photos, search in Ukrainian, faces, places and an on-device AI — all on the phone. Ukrainian and English. Free.

> **Status: test version 0.5.1** — interface based on Iris Gallery. Search by text in photos (Ukrainian word forms, Latin letters), names and dates, timeline and folders, viewer, text in photos, editor, before-sending check, screenshots, duplicates and similar photos, changing date and place, the "This day" widget and lock work. Svitlyna can be your main gallery: it opens photos from other apps and is their photo chooser. Next: search by meaning, faces, maps. Download: [Releases](../../releases). What we are building is in [SPEC.md](SPEC.md) and why in [DECISIONS.md](DECISIONS.md) (both in Ukrainian). Ideas and wishes: [Issues](../../issues).

## Planned

- 🔒 **Privacy.** No internet permission; the build fails if one appears. Access to photos and videos only by default. Encrypted vault and database.
- 🔤 **Text in photos,** like on iPhone: select, copy, translate.
- 🔍 **Search** by text in photos (instant, word forms included), by meaning, by people, places and dates.
- 📸 **Screenshots:** remove the status bar in one tap, stitch long screenshots, blur personal details.
- 📤 **Safe sharing:** without location, a check before posting, sizes for social networks.
- 🗺️ **Places and offline maps,** trips and routes, private zones.
- ✨ **Editor:** beauty, restoring old and blurry photos, cut out an object, stickers and vector emoji.
- 📖 **Beautiful albums:** a PDF photo book with maps and text.
- 🧳 **Travel mode** and an encrypted backup to move to a new phone.
- 🗓️ **Metadata is kept:** an edited photo stays in its place in the timeline.

## Transparency

- **Signing:** the genuine APK will be signed with a certificate whose SHA-256 fingerprint is `53027b1a87085eb9e40a74820ff2aa067dd3350a0e3010a8f6c559e04a628630`.
- **Build:** the APK will be built from this code in GitHub Actions and signed only after the author's approval.
- **Based on Iris Gallery:** the interface and most features come from [Iris Gallery](https://github.com/MohamadOday/iris-gallery) (Apache 2.0), extended by Svitlyna; see [NOTICE.md](NOTICE.md).
- **How it is made:** written with AI assistance (Claude, Anthropic), directed and tested by the author.

Kotlin · Jetpack Compose. GPL-3.0 licence.

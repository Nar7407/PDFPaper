# PDFPaper

An open-source, on-device PDF app for Android, built on its own PDF engine written in Kotlin.
No account, no cloud processing, no tracking: your files stay on your phone.

> ⚠️ **Status: early preview (v0.1.0).** The engine is written from scratch and still has bugs.
> Please don't rely on it for important documents yet, and keep backups of your files.

<!-- Add 2-4 screenshots here, e.g.:
<p>
  <img src="docs/screenshots/home.png" width="240">
  <img src="docs/screenshots/all-tools.png" width="240">
</p>
-->

## Features

**Available now**
- Open, scroll, zoom and search PDFs
- Page tools: rotate, delete, reorder, extract, merge, split
- Save changes as a new PDF

**Planned** (shown as "Coming soon" in the app)
- Editing text and images, annotations, comments and stamps
- Form filling
- Encryption and password-protected PDFs
- Redaction and document sanitizing
- Digital signatures (signing and validation)
- Scan and OCR
- Conversion between PDF and Word/PowerPoint, and opening Word/PowerPoint files

## Download

Get the latest APK from the [Releases](../../releases) page. Requires Android 8.0 (API 26) or newer.

## Build from source

Requirements: JDK 17, Android Studio (latest stable), Android SDK 34.

```bash
git clone https://github.com/<your-user>/PDFPaper.git
cd PDFPaper
./gradlew build                 # build and run all tests
./gradlew :app:assembleDebug    # debug APK in app/build/outputs/apk/debug/
```

## Project structure

| Module | Purpose |
|---|---|
| `:engine` | The PDF engine (pure Kotlin, no Android or `java.awt` dependencies) |
| `:render-jvm` | Reference renderer that writes PNG images, used for testing |
| `:cli` | Command-line tool for trying the engine without the app |
| `:app` | Android app (Jetpack Compose) |

More detail is in `docs/ARCHITECTURE.md` and `docs/API.md`.

## Command-line tool

```bash
./gradlew :cli:run --args="info file.pdf"
./gradlew :cli:run --args="text file.pdf --page 1"
./gradlew :cli:run --args="render file.pdf --page 1 --scale 2 --out page.png"
```

## Testing

The engine is tested against a folder of real-world PDFs (`/testdata`). Run:

```bash
./gradlew :engine:test
./gradlew :engine:corpusCheck   # opens every PDF in /testdata and writes build/corpus-report.txt
```

## Known issues
- Text spacing can be wrong when a font can't be read completely
- Some images may appear upside down
- Very large PDFs can be slow on low-end phones

See the [issue tracker](../../issues) for the current list.

## Contributing

Bug reports and sample PDFs that fail to open are especially welcome (remove private content first).
Before sending a pull request, run `./gradlew build` and make sure it is green.
Please don't add dependencies with GPL/AGPL licenses.

## Development

Parts of this project were written with the help of AI coding assistants and reviewed and tested by the author.
`AGENTS.md` describes the working rules and milestones used during development.

## License

Licensed under the [Apache License 2.0](LICENSE). Third-party components and their licenses are listed in
[THIRD_PARTY.md](THIRD_PARTY.md).

PDFPaper is an independent project. It is not affiliated with or endorsed by Adobe or by any other PDF product or company.
"PDFPaper" and its logo are not covered by the code license.

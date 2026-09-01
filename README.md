# Drumroll

An Android app for logging well-meter readings: frame the register, confirm the digits,
and the row lands in Google Sheets with the right date.

Built for a **Rockwell International 3/4 T-04** reading in gallons — six rotating drums
plus a painted trailing zero, so a register showing `324444` and a `0` is stored as
`3244440`. The drum count, painted-zero count and plausible daily range are all
configurable in Settings if the meter is ever replaced.

## Why it's shaped this way

The OCR approach was measured against 15 photographs of the meter (2017–2021) before
any of it was written. What that testing found:

- **Cropping to the register is the highest-value step.** On whole photos the recognizer
  competes with the serial number, "PA 15208", "GALLONS" and the dial numerals, and is
  usable on only 2 of 15. On the Master Meter photo it confidently returned `1041993`
  when the register read `0000129`.
- **No preprocessing.** Greyscale, contrast stretching, thresholding and inversion each
  made the recognizer return *nothing*. The register's leading drums are dark-on-light
  and its trailing drums are light-on-dark, so any threshold that rescues one half
  destroys the other.
- **Upscale about 2×.** 1.5–3× read five of six drums correctly; 5× degraded to two.
- **The mid-roll drum is never read correctly**, and confidence comes back at 1.00
  regardless. Model confidence is unusable as a gate here, so agreement across frames is
  used instead.

Hence the design: OCR prefills only the drums that several independent frames agreed on,
and leaves anything else blank and focused rather than guessing. See `docs/` for the
full design note.

## Setup

### 1. The spreadsheet

Create a tab named `Readings` with this header row:

| id | date | reading | meter | source | note | change | days | gal/day |
|----|------|---------|-------|--------|------|--------|------|---------|

Put these three formulas in `G3`, `H3` and `I3` once — they spill down forever:

```
G3: =ARRAYFORMULA(IF(C3:C="","", IF(D3:D<>D2:D, "new meter", C3:C - C2:C)))
H3: =ARRAYFORMULA(IF(B3:B="","", B3:B - B2:B))
I3: =ARRAYFORMULA(IF(B3:B="","", IF(D3:D<>D2:D, "", (C3:C-C2:C)/(B3:B-B2:B))))
```

The `D3:D<>D2:D` guard stops a meter swap from rendering as a nonsensical negative.

### 2. The endpoint

Extensions → Apps Script, paste `apps-script/Code.gs`, set `SHEET_ID` and `SECRET`,
then Deploy → New deployment → Web app → execute as *me*, access *Anyone*.

Paste the deployment URL and the secret into the app's Settings screen.

> The URL plus the secret is effectively a bearer token, and anything in an APK can be
> extracted. Fine for a personal log — worst case is junk rows you can undo. Don't commit
> the URL to a public repo.

### 3. Build and install

```sh
export JAVA_HOME=$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 4. First run

Grant camera access, then open Settings and set the endpoint. Back on the capture
screen, use the rotate button (↻) to match the guide rectangle to how the register
actually sits in the crawlspace — half the archive photos have it running vertically.
The angle is remembered.

## Layout

```
app/src/main/java/com/schmmd/drumroll/
  data/        Room entity + DAO, MeterConfig, DataStore settings, Repository
  ocr/         MeterRecognizer interface, ML Kit implementation, Vote, FrameCapture
  sync/        SheetsClient (Apps Script), SyncWorker (WorkManager)
  ui/          Capture, Confirm, History, Settings screens + MainViewModel
  util/        Validation rules
apps-script/   The Google Sheets endpoint
photos/        Meter photos (gitignored)
```

`MeterRecognizer` is a one-method interface. If on-device OCR turns out not to earn its
keep, a cloud implementation drops in behind it without touching anything else.

## Tests

```sh
./gradlew testDebugUnitTest
```

16 tests covering the vote, the register arithmetic and the validation rules. The vote
cases are transcribed from real OCR output on the meter photos — including the jitter
burst that caught a false-confidence bug: dividing agreement by *surviving* frames
instead of *attempted* ones reported certainty in a wrong digit.

## Status

Compiles clean, unit tests pass, debug APK builds. **Not yet run on a device** — no
handset or emulator was attached when it was written, and the camera path in particular
needs a real device pointed at the real meter.

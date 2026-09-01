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
- **Height separates the register from the dial face.** Measured on real crops, the
  register comes back as a single 56–65px element while the dial numerals, `GALLON` and
  the `5/8` pipe marking are all 14–27px. Fragments far below the tallest are dropped;
  without that they are concatenated into the reading (`045694` → `86589120`).
- **A correct read is seven digits, not six.** The guide box always includes the painted
  zero — it is part of the register face — so requiring exactly `drumCount` threw good
  frames away. A trailing run matching the painted zeros is now stripped instead.

### Choosing the engine

Three engines were measured against photographs of this meter on 2026-08-31:

| Engine | Read of a register showing `045694` |
|--------|-------------------------------------|
| ML Kit (bundled Latin) | `lölASI6E`, `lö1251680` — letters, no usable digits |
| Tesseract 5 + digit whitelist | `560` from a clean, isolated, upscaled strip |
| **PP-OCRv4 (ONNX)** | **`1014516940` → `0456940` after drum assignment** |

ML Kit has no way to express a character whitelist, so nothing stops it choosing letters.
Tesseract binarises with a global threshold, which this face defeats by being
dark-on-light and light-on-dark at once — the same reason preprocessing failed above.

PP-OCRv4 reads the digits but reads the black bars *between* drums as `1`s, returning ten
characters for a seven-digit face. Dropping `1`s would also drop a drum genuinely showing
one, so position decides instead: a separator falls between two cell centres and loses to
the digit beside it, while a real `1` sits on a centre and wins.

The remaining honest limit is the mid-roll drum, which no general-purpose recognizer will
resolve — which is why prefill is still gated on cross-frame agreement and every drum
stays editable.

Hence the design: OCR prefills only the drums that several independent frames agreed on,
and leaves anything else blank and focused rather than guessing. See `docs/` for the
full design note.

## Setup

There isn't any. No Google account, no OAuth, no Cloud project, no deployed script and
no shared secret — readings go to a file through Android's Storage Access Framework, the
same system picker you get from any "Save to…" sheet.

In **Settings → Spreadsheet**:

- **Choose a file…** — creates `well-readings.csv` wherever the picker offers. Pick
  Google Drive to keep it in the cloud; local storage and Dropbox work identically.
- **Use existing…** — point at a file you exported before and carry on appending to it.
- **Save after every reading** — rewrites the whole file a few seconds after anything
  changes. Access to the file is persisted, so this survives a restart.
- **Save now** — writes immediately.
- **Import…** — reads a spreadsheet back in, to seed a new phone from the old one's file.
  Readings already held are skipped, matched on meter plus capture timestamp, so it is
  safe to run twice or against a file that only partly overlaps. Nothing is deleted.

Because access is granted per-file by the picker, the app declares no network permission
at all.

The file carries the columns the Apps Script version kept in the sheet:

| meter | timestamp | date | reading | source | note | change | days | gal/day | photo |
|-------|-----------|------|---------|--------|------|--------|------|---------|-------|

`timestamp` is when the shutter fired and is never edited; `date` is editorial and can be
nudged with −1d/+1d. `photo` is the JPEG's filename in the app's private storage — the
absolute path is meaningless off the device, but the name ties a row to the frame it came
from. Several readings a day are fine — the timestamp is what tells them
apart, and `gal/day` is left blank rather than dividing by a zero-day gap.

`change`, `days` and `gal/day` used to be `ARRAYFORMULA`s living in the spreadsheet. A
file in Drive is not a live sheet and has nowhere to host them, so they are computed on
the way out instead — per meter, so swapping the meter starts a fresh series rather than
rendering as one enormous negative jump.

> A CSV in Drive is a file, not a Google Sheet. Opening it with Sheets makes a *separate*
> converted copy that the app does not write to; work from the CSV itself, or re-import
> when you want to chart it.

## Build and install

```sh
export JAVA_HOME=$(brew --prefix openjdk@17)
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First run

Grant camera access, then open Settings and pick a file to save to. Back on the capture
screen, use the flip button to turn the crop end-for-end when you are reading the
register from the far side of the pit. The setting is remembered.

## Layout

```
app/src/main/java/com/schmmd/drumroll/
  data/        Room entity + DAO, MeterConfig, DataStore settings, Repository
  ocr/         MeterRecognizer interface, PP-OCRv4 implementation, Vote, FrameCapture
  backup/      SafFile (Storage Access Framework), ReadingsCsv, ExportCoordinator
  ui/          Capture, Confirm, History, Settings screens + MainViewModel
  util/        Validation rules
photos/        Meter photos (gitignored)
```

`MeterRecognizer` is a one-method interface. It has already earned its keep once: ML Kit
was swapped out for PP-OCRv4 behind it without any other file changing shape.

## Tests

```sh
./gradlew testDebugUnitTest
```

42 tests covering the burst vote, the register arithmetic, the validation rules, the
spreadsheet columns in both directions, and the drum assignment. The vote
cases are transcribed from real OCR output on the meter photos — including the jitter
burst that caught a false-confidence bug: dividing agreement by *surviving* frames
instead of *attempted* ones reported certainty in a wrong digit.

## Status

Compiles clean, unit tests pass, and the debug APK runs on a Pixel 10. The camera path
has been exercised on-device; the recognizer's accuracy against the real meter in real
light is still the open question.

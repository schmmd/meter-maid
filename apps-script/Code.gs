/**
 * Drumroll — Apps Script endpoint.
 *
 * Setup:
 *   1. Open your spreadsheet, Extensions → Apps Script, paste this in.
 *   2. Set SHEET_ID and SECRET below.
 *   3. Make sure the sheet has a tab named "Readings" with this header row:
 *        id | date | reading | meter | source | note | change | days | gal/day
 *   4. Put these three formulas in G3, H3 and I3 (once, they spill forever):
 *        G3: =ARRAYFORMULA(IF(C3:C="","", IF(D3:D<>D2:D, "new meter", C3:C - C2:C)))
 *        H3: =ARRAYFORMULA(IF(B3:B="","", B3:B - B2:B))
 *        I3: =ARRAYFORMULA(IF(B3:B="","", IF(D3:D<>D2:D, "", (C3:C-C2:C)/(B3:B-B2:B))))
 *   5. Deploy → New deployment → Web app → Execute as *me*, access *Anyone*.
 *   6. Paste the deployment URL and the secret into the app's Settings screen.
 *
 * Note on the secret: deployed as "Anyone", this URL plus the shared string is
 * effectively a bearer token, and anything shipped in an APK can be extracted. For a
 * personal well log that is a fine trade — the worst case is junk rows you can undo.
 * Don't commit the URL to a public repo.
 */

const SHEET_ID = 'PASTE_YOUR_SPREADSHEET_ID';
const SECRET = 'PASTE_A_LONG_RANDOM_STRING';
const TAB = 'Readings';

function doPost(e) {
  try {
    const body = JSON.parse(e.postData.contents);
    if (body.secret !== SECRET) {
      return json({ ok: false, error: 'bad secret' });
    }

    const sheet = SpreadsheetApp.openById(SHEET_ID).getSheetByName(TAB);
    if (!sheet) return json({ ok: false, error: 'no tab named ' + TAB });

    const ids = sheet.getRange('A:A').getValues().flat().filter(String);

    // The phone retries anything it did not see succeed — including writes that did.
    // It cannot tell "failed" from "succeeded but the reply was lost", so we decide.
    if (ids.indexOf(body.id) !== -1) {
      return json({ ok: true, duplicate: true });
    }

    // Deliberately not appendRow(): the spilled ARRAYFORMULA results in G:I count as
    // content, so appendRow would land the new row underneath them. Column A is the
    // only honest row counter on this sheet.
    const row = ids.length + 1;
    sheet.getRange(row, 1, 1, 6).setValues([[
      body.id,
      new Date(body.readingDate + 'T12:00:00'),  // noon sidesteps DST edges
      body.value,
      body.meterId,
      body.corrected ? 'corrected' : 'ocr',
      body.note || ''
    ]]);

    return json({ ok: true, row: row });
  } catch (err) {
    return json({ ok: false, error: String(err) });
  }
}

function json(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

# Changelog

Each release's notes on GitHub are taken from its section here. Add the new version's section (`## X.Y.Z - date`)
before tagging; the release fails if it's missing.

## 1.3.0 - 2026-10-11

- **Side panel** (menu button, top left) replaces the bottom bar, with tabs inside each section. The back gesture now
  returns to the previous screen instead of closing the app.
- **Manage** your Spoolman from the app: add, edit and delete manufacturers, filaments and spools, archive spools,
  weigh a spool in, create several spools at once or duplicate one.
- **Spoolman's public filament database**: matches show up when you search for a filament, and you can browse by
  manufacturer and add several filaments (and new manufacturers) at once.
- **Tags**: see, link and unlink the tags on any spool or filament; **Find a spool** opens what a tag is linked to;
  **Tag audit** lists spools with missing, unlinked or stray tags; new setting **Link tags when reading**.
- **QR labels**: print them from the new **Labels** section (sheet or label printer, any size) and scan them with
  the camera (Read → Scan a label). They use Spoolman's own codes. The camera permission is asked for only when you
  scan.
- **Report scans to Spoolman** (off by default) makes the phone act as a Spoolman tag reader, so Spoolman's web page
  can jump to what you scan. A how-to shows when you turn it on.
- Write list: new **Needs attention**, **Low**, **Material** and **Location** filters.
- Tags per spool is now a number from 1 to 6 (still 2 by default), and the Done screen closes after a number of
  seconds you choose, 3 by default.

## 1.2.0 - 2026-10-04

- Added filter chips to the Write list: **To do** (default), **All**, **Untagged**, **Partly tagged** and
  **Fully tagged**. Each shows how many spools it lists, and your choice is remembered.
- Spools tagged with 1.0 are now remembered: they show "N tags · not linked", count as tagged, and stay out of the
  To do list. A **Not linked** chip lists them so you can link their tags. Once a spool's tags are linked,
  Spoolman's own tag list takes over its count.

## 1.1.0 - 2026-10-04

- Each tag you write is now linked to its spool in Spoolman's tag feature (format `creality`). Spoolman is the
  source of truth for how many tags a spool has.
- A tag Spoolman already has on another spool or filament is moved to the new spool, and you're told.
- The Read tab asks Spoolman about the tag you scan and shows whether it's linked, not linked, or linked to a
  different spool, with a button to link it.
- "Save & test" shows the Spoolman version and whether tag linking is available. The spool list shows each
  spool's location and refreshes when you return to the app.
- Settings: "Record tag count in Spoolman" is now "Link tags in Spoolman".
- Spoolman older than 0.27 still works; it keeps the tag count in a custom field instead of linking tags.

## 1.0.0 - 2026-10-04

- First release. Writes Creality CFS RFID tags (MIFARE Classic 1K) from your Spoolman spools, storing the spool ID
  so the Jacobean firmware can link the CFS slot to Spoolman.
- Write one spool or a batch (multi-select, moves on automatically), with two tags per spool, one per flange.
- Every write is read back and verified; asks before overwriting a tag that already has data.
- Read tab to decode and check a tag.
- Options: keep the screen on, auto-close the Done screen, two tags per spool, confirm before overwriting.
- Warnings for multi-color filaments, missing colors and spool #1; an NFC-off banner with a shortcut to settings.

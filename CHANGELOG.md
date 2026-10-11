# Changelog

Each release's notes on GitHub are taken from its section here. Add the new version's section (`## X.Y.Z - date`)
before tagging; the release fails if it's missing.

## Unreleased

- Tags per spool is now a number, 1 to 6 (it was a two-or-one switch), still 2 by default. The seconds before the Done
  screen closes is also a number, 0 to 60, and now defaults to 3.
- The back gesture now returns to the previous screen, or clears a selection, instead of sending the app to the
  background. It only leaves the app from the first screen.
- Searching for a filament, when adding a spool or on the Filaments tab, also shows matches from Spoolman's public
  database; tap one to add it (and its manufacturer if new) and use it.
- Add manufacturers and filaments from Spoolman's public filament database: browse by manufacturer, tick several
  filaments and add them in one go, creating the manufacturer if it's new.
- Turning on **Report scans to Spoolman** shows a short how-to for making Spoolman's page jump to scans, with a
  "Don't show this again" box.
- Settings now explains when Spoolman's web page jumps to a scanned spool, and shows what Spoolman made of the last
  scan.
- New side panel (menu button, top left) replaces the bottom bar. Sections with several views have tabs: Read
  (Read a tag, Find a spool), Manage (Spools, Filaments, Manufacturers) and Settings (Spoolman, Writing, Reading).
  Tag audit is its own section.
- New **Manage** tab: add, edit and delete manufacturers, filaments and spools in Spoolman without leaving the app,
  and archive or restore spools.
- New filaments can start from the community filament database that Spoolman serves.
- See, unlink and link the tags on any spool or filament (link by scanning a tag), or write a spool's tags from its page.
- Write list: new **Needs attention**, **Low**, **Material** and **Location** filters that combine with the tag chips.
- Weigh in a spool from its page: enter the scale reading and Spoolman works out what's left.
- Create several identical spools at once, or duplicate a spool.
- Hold a tag to the phone on Read → Find a spool to open whatever it's linked to.
- **Tag audit** list of spools with missing, unlinked or stray tags.
- New setting **Report scans to Spoolman** makes the phone act as a Spoolman tag reader, so a paired browser window follows what you scan.
- New setting **Link tags when reading** links a scanned tag to its spool automatically.

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

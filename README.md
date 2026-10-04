# SpoolFID

Write RFID tags for your Creality CFS straight from your phone, using the spools in your
[Spoolman](https://github.com/Donkie/Spoolman) inventory.

Pick a spool, hold a blank tag to the back of your phone, and you're done. When the tagged spool goes into
the CFS, the printer reads the tag, looks the spool up in Spoolman, and fills in the slot's material, color,
brand, name and remaining weight for you.

SpoolFID is built for the [Jacobean K2 Plus firmware](https://jacob10383.github.io/k2-plus-custom-firmware/cfs/),
which can link a CFS slot to a Spoolman spool.

## What you need

- **An Android phone** (Android 8.0 or newer) with NFC and **MIFARE Classic support**. Not every phone has
  it, because it depends on the NFC chip. SpoolFID checks on launch and shows a warning at the top of the
  screen if your phone can't do it.
- **Blank MIFARE Classic 1K tags with a 4-byte UID.** These adhesive MIFARE stickers are known to work:
  [Amazon listing](https://www.amazon.com/Adhesive-Stickers-Self-Adhesive-Commercial-Proximity/dp/B0G1M659Q7).
  Listings change, so check that anything you buy is MIFARE Classic 1K with a 4-byte UID.
- **Two tags per spool**, one on each flange (see [Two tags per spool](#two-tags-per-spool)).
- **A Spoolman server** your phone can reach on your network. Plain `http://` is fine.
- **A Creality printer with a CFS running the Jacobean firmware**, set up as described in
  [Printer setup](#printer-setup).

## Install

1. Open the [Releases page](https://github.com/mitt3n/SpoolFID/releases) on your phone and download the
   latest `SpoolFID-vX.Y.Z.apk`.
2. Open the downloaded file. Android will ask you to allow installs from your browser or file manager the
   first time. Allow it, then install.

## Getting started

1. Open **Settings** and enter your Spoolman address, for example `192.168.1.50:7912`. Tap **Save & test**.
   It tells you how many spools it found, or why it couldn't connect.
2. Go to the **Write** tab. Your spools are listed with their color, material and remaining weight.

## Writing tags

- **One spool:** tap it, then hold a blank tag to the back of the phone.
- **Many spools:** long-press spools to select them (or tap **All**), then tap **Write N**. Tap a fresh tag
  for each one. After every write the phone vibrates and beeps and moves on to the next. **Skip** leaves a
  spool out and **Stop** ends the session.
- **Check the details:** the screen shows what's about to be written, including the material, color and
  weight. Anything approximate is shown in amber.
- **Every write is verified.** SpoolFID reads the tag back before it counts as done. If you tap the same tag
  twice in a row it refuses, so one tag can't be used for two spools.

Each spool in the list shows how many tags it has. A green check with "2 tags" means it's finished, and an
amber "1/2 tags" means it still needs another. Finished spools are hidden by default (the **Hide fully
tagged** chip), so the list shows what's left to do.

### Two tags per spool

Put a tag on **each flange** of the spool and write both for the same spool. Genuine Creality spools carry
two identical tags, and the CFS only reads the tag on the side facing its reader. A spool with a single tag
may be read one way round and ignored the other. With a tag on both sides it works however you insert it.

SpoolFID does this for you. For each spool it asks for two tags in a row ("tag 1 of 2", then "now a tag for
the other side"), writing the same data to both. You can switch to one tag per spool in Settings.

### Overwrite protection

Before replacing a tag that already holds a different spool, or other CFS data such as a genuine Creality
tag, SpoolFID shows what's on it and asks you to confirm. Re-writing the same spool onto its own tag never
asks. You can turn this off in Settings for the fastest batch writing.

## Reading tags

The **Read** tab decodes any tag you hold to the phone: the spool ID, material, color and weight, along with
the matching spool from Spoolman. Use it to check a tag before putting the spool in the printer.

## Settings

| Setting | What it does |
|---|---|
| Spoolman address | Where your Spoolman server is. |
| Two tags per spool | Write a tag for each flange. On by default. |
| Record tag count in Spoolman | Stores how many tags each spool has, so the count shows in the list. Creates a "CFS tags written" field in Spoolman the first time. |
| Keep screen on | Stops the display sleeping while you write or read tags. On by default. |
| Close "Done" screen automatically | After the last tag, returns to the list after Off / 3 / 5 / 10 seconds. The Done button shows the countdown and still works. |
| Confirm before overwriting | Ask before replacing a tag that already has data. On by default. |

## Printer setup

SpoolFID writes the tags. For the printer to use them, check these on the printer side:

1. **Connect Moonraker to Spoolman.** In `moonraker.conf`, add a `[spoolman]` section with `server:` set to
   your Spoolman address. You can confirm it's working at `http://<printer>:7125/server/spoolman/status`,
   which should show `"spoolman_connected": true`.
2. **Turn on RFID reading.** In Fluidd, open Filament Box, click the cog, and enable **Read RFID on
   insertion** and **Read RFID after Klipper starts**. Both are off by default.
3. **Give every spool a material and a color in Spoolman**, and don't archive it. Without those, the
   printer ignores Spoolman and uses only what's on the tag (a "Generic" slot with no Spoolman link).
4. **Use spool ID 2 or higher** (see [Spool #1](#spool-1-doesnt-work-with-the-jacobean-firmware)).

### How the printer reads tags

- The CFS reads a tag when a spool is **inserted**, with the filament **unloaded**. To update a slot,
  pull the spool all the way out, wait a few seconds, and push it back in.
- A restart of Klipper or the host does **not** make the CFS re-read slots it already has data for. To force
  every slot to be re-read, **switch the printer's power off for about 30 seconds** and back on.
- If a spool's tags can't be read, its slot keeps showing the **previous spool's** details. A swapped spool
  that still shows the old color usually means its tag isn't being read. Check both tags with the Read tab.

### Troubleshooting

- **Watch the Fluidd console** while you insert a spool. A working tag prints, in order:
  `RFID tag read for …`, then `fetching Spoolman ID N`, then `Spoolman ID N profile applied`.
  - **No `RFID tag read` line:** the CFS isn't seeing the tag. Check that both flanges have a tag and that
    they read on your phone.
  - **`fetching` but then `unavailable or incomplete`:** the Spoolman lookup failed. Check the Moonraker
    connection above, and that the spool has a material and a color.
- **`BOX_DEBUG`** in the console shows what the CFS has stored for each slot. A slot with no readable tag
  shows `unknown`.
- **Fill a slot without a tag** using **Select spool** in Filament Box, or
  `_BOX_SLOT_SET SLOT=<index> MATERIAL=PLA COLOR=#RRGGBB BRAND=… NAME=… SPOOLMAN_ID=<id>`.
  Clear a slot with `_BOX_SLOT_CLEAR SLOT=<index>`. Slot indices run 0 to 7 across the two boxes.

## Good to know

### Spool #1 doesn't work with the Jacobean firmware
The firmware treats a spool ID of `0` or `1` as "no spool ID" and skips the Spoolman lookup. SpoolFID writes
spool #1 correctly and warns you, but the printer will ignore that tag.

Spoolman can't renumber spools. To use that spool, create a new one (it gets the next free ID) and archive
the old one, or just leave #1 unused. IDs from 2 up to 999999 work.

### Material matching is approximate
The tag needs a Creality material ID. SpoolFID maps your Spoolman material (PLA, PETG, ABS, TPU and common
variants like "PLA+") to Creality's generic profile for that family. If nothing matches, it uses Generic PLA
and warns you. With the Jacobean firmware, Spoolman's own data takes priority over the tag's material.

### Multi-color filaments need one main color
Spoolman stores multi-color filaments without a single color. SpoolFID writes the first color and warns you,
but the Jacobean firmware only links a spool to Spoolman when the filament has one color set. Until you set
one in Spoolman, that slot won't show the Spoolman name or remaining weight.

### Weights are rounded
Tags can only hold 250 g, 500 g, 600 g, 750 g or 1000 g. A spool's weight is rounded to the nearest of
those. If Spoolman has no weight for it, 1000 g is assumed.

### Needs a connection
SpoolFID needs to reach Spoolman while you use it. There's no offline mode.

## License

Copyright (C) 2026 mitt3n

SpoolFID is free software: you can redistribute it and/or modify it under the terms of the
[GNU General Public License v3.0](LICENSE) as published by the Free Software Foundation. It is distributed
in the hope that it will be useful, but **without any warranty**; without even the implied warranty of
merchantability or fitness for a particular purpose. See the license for details.

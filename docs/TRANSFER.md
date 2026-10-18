# KeyCorrect — Settings & learned-data transfer (export / import)

Goal: move your keyboard to another device (or keep a backup) by hand. Android's own backup and
device transfer stay disabled (privacy invariant, HANDOFF §5); this is an explicit,
user-initiated file export.

## Decisions (user, 2026-10-18)

| # | Question | Decision |
|---|---|---|
| T-1 | Protection | **Optional passphrase.** You can export a plain file or an encrypted one. Encrypted: AES-256-GCM with a key derived from the passphrase (PBKDF2-HMAC-SHA256, random salt, high iteration count). A wrong passphrase fails cleanly. |
| T-2 | Contents | **Everything:** settings; the personal dictionary (added, learned and blocked words) and text shortcuts; learned typing data (n-grams / phrase learning, correction memory, voice substitution pairs, emoji recents); touch and key-position data. **Not included:** clipboard history (it is not learning), the voice pack and the dictionary (they come with the APKs). |
| T-3 | Import into a keyboard that already has data | **Ask each time:** *Merge* adds counts, unions words and shortcuts, and keeps this device's data. *Replace* wipes this device's learned data and settings first. |
| T-4 | The exporting device's screen differs from the importing one | **Warn, and skip touch and key-position data by default.** An "import anyway" box brings it in. The same rule applies to the size settings tied to the screen (key height, padding, gap, bottom-row widths). |

## File format (`.kcbackup`)
- A zip containing:
  - `manifest.json`: format version, app version, creation time, and the source device (model;
    screen width and height in px; density; smallest width in dp; the keyboard's key height and
    number-row setting).
  - `settings.json`.
  - `dictionary.json`.
  - `learning.json`.
  - `touch.json`, keyed by the existing touch-statistics buckets.
- **Encrypted variant:** a small header (magic, version, KDF parameters, salt, nonce) followed by
  the encrypted zip. The importer detects which variant it is and asks for the passphrase.
- Import is versioned: newer formats are refused with a clear message, and older formats are
  migrated.

## Flow (Settings → Backup & transfer)
- **Export:**
  1. Choose a passphrase or leave it empty.
  2. The system file picker (`ACTION_CREATE_DOCUMENT`) chooses the destination, so no storage
     permission is needed and there is no network.
- **Import:**
  1. Pick the file (`ACTION_OPEN_DOCUMENT`).
  2. Enter the passphrase, if it's encrypted.
  3. A summary shows the source device, the date and the counts of each kind.
  4. Choose Merge or Replace.
  5. If the screen differs, a warning explains that touch patterns depend on screen size and
     hand position; touch data and screen-tied sizes are left out unless "import anyway" is
     ticked.
  6. Apply, then show a result summary.
- The data is read and written through the existing encrypted stores (SQLCipher, settings), in
  one transaction where possible. A failed import leaves the device unchanged.
- **Privacy:**
  - It is only ever user-initiated.
  - Nothing is logged except counts and error classes.
  - Learning gates apply as usual after import (incognito and so on).
  - "Erase personal data" also forgets nothing extra, because no copies are kept.

# KeyCorrect: Phase 8 plan (UI/UX customization)

Goal (PLAN §13.1, §6.5): let the user shape how the keyboard fits and looks, with live previews, and
recommend size changes from their own touch data.

## Decisions (user, 2026-11-06)

| # | Question | Decision |
|---|---|---|
| P8-1 | Scope | **The keyboard-fit report** and **the look (theme and keys)**. One-handed, floating and split modes, toolbar ordering, strip options and per-event haptics or sounds move to a later phase. |
| P8-2 | Profiles | **Automatic size profiles.** Size settings are remembered separately per device class (phone, tablet) and orientation (portrait, landscape), and the current one is used automatically. The look stays shared by all profiles. |
| P8-3 | Delivery | **One PR and test build per part:** 8a profiles, 8b the fit report, 8c the look. |
| P8-4 | Colors | **Wallpaper colors (Material You) are the default.** The options are presets (today's look, Samsung-like light and dark, high contrast) and a custom editor for background, keys, modifier keys, text and accent, each set separately for light and dark. |
| P8-5 | Keys | **Essentials:** a corner-radius slider (square to pill), a border on/off switch, label size and bold, hint labels on/off, the key preview popup on/off, and the press-highlight style. One style covers all keys, plus an accent color for Enter. |
| P8-6 | Fit report | **Preview, then Apply.** The report shows a heat map and a suggestion (for example "keys 8% taller"). The suggestion comes with an estimate, from replaying past touches against the proposed geometry, of how many fewer words would have been mistyped. The user taps Apply and can undo. A suggestion appears only after about 300 accepted words in that profile, and never applies automatically. |

## Parts

### 8a. Size profiles
- **Profile key:** `phone` or `tablet` (smallest width ≥ 600 dp), crossed with `portrait` or `landscape`.
- **Settings stored per profile:** key height, bottom-row side-key width, ?123/Enter width, edge padding, key gap and the number row. Every other setting stays global.
- **Defaults:** a profile with no stored value falls back to the current global value, so nothing changes for the user on upgrade.
- **Settings screens:** the size settings edit the current profile, with a line saying which one ("Phone, portrait"). Landscape or tablet can be chosen to edit the others.
- **Backup:** it carries all profiles. The existing screen-mismatch rule applies per profile.

### 8b. Keyboard-fit report
- **Data source:** the touch statistics from the learning layer (offsets and spread per key, per touch profile; never text).
- **What the screen shows:**
  - the heat map;
  - the evidence count;
  - the recommendation with a preview of the proposed keyboard;
  - the estimated effect;
  - Apply and Undo.
- **How the estimate works:** it is a Gaussian fit of the per-key statistics, not a replay of individual touches. Each letter key (a-z only; the number row and bottom row are left out) is modelled as an independent normal in x and y around its mean offset, with its own spread, rescaled from key-size units to dp using the cell size the statistics were recorded at. The miss rate is the chance a touch lands outside a cell of the proposed size, weighted by each key's sample count. The app never stores individual touches or text (privacy: only counts, means and spreads exist), so a replay is not possible, and the fit lets any proposed geometry be scored in closed form. It is an estimate: tails are trimmed by the outlier filter, so real misses can be somewhat higher.
- **Applies to:** the profile's size settings from 8a.

### 8c. Look
- A theme model in `core:designsystem`, with presets, the custom editor and wallpaper colors.
- Key style essentials, applied by the keyboard renderer while keeping the static- and pressed-layer drawing design (docs/PERF.md).
- A live preview in Settings.
- Themes are exported with backups.

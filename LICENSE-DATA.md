# Licence of the dictionary data

The compiled dictionary `app/src/main/assets/dictionaries/en_US.kcd` is a **derived work** of the
sources below. It is made available under the
[Creative Commons Attribution-ShareAlike 4.0 International licence (CC BY-SA 4.0)](https://creativecommons.org/licenses/by-sa/4.0/),
with the attributions given here. KeyCorrect's source code is licensed separately under
GPL-3.0-or-later (see [`LICENSE`](LICENSE)); CC BY-SA 4.0 material may be adapted into a GPLv3 work.

The dictionary contains aggregated word lists, word frequencies and n-gram statistics only. No
Wikipedia article text and no Tatoeba sentence text is included. Changes were made: the data was
filtered, counted, pruned, smoothed and quantised into KeyCorrect's binary format (see
[`docs/DATA_SOURCES.md`](docs/DATA_SOURCES.md) for the exact pipeline and pinned versions).

## Attribution

> **English dictionary data**
> Word list and word frequencies: Android Open Source Project, LatinIME English dictionary,
> licensed under the Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0).
> Next-word statistics derived from Simple English Wikipedia (text by Wikipedia contributors,
> https://simple.wikipedia.org, licensed under CC BY-SA 4.0,
> https://creativecommons.org/licenses/by-sa/4.0/) and from sentences on Tatoeba
> (https://tatoeba.org, by Tatoeba contributors, licensed under CC BY 2.0 FR,
> https://creativecommons.org/licenses/by/2.0/fr/). The statistics are aggregated word counts;
> no article or sentence text is included. Changes were made.

## Sources

| Source | Licence | Notes |
|---|---|---|
| AOSP LatinIME `en_US_wordlist.combined.gz` | Apache-2.0, Copyright (c) 2008 The Android Open Source Project | Vocabulary and word frequencies. Licence text: https://www.apache.org/licenses/LICENSE-2.0 |
| Simple English Wikipedia (dump `20261001`) | CC BY-SA 4.0 (also GFDL; used under CC BY-SA 4.0) | Formal-text n-gram counts. Authors: Wikipedia contributors, see page histories at https://simple.wikipedia.org |
| Tatoeba English sentences (export of 2026-10-03) | CC BY 2.0 FR | Conversational n-gram counts. Authors: Tatoeba contributors, https://tatoeba.org |

## Redistribution

If you redistribute `en_US.kcd` or a dictionary built from the same data, you must keep this
attribution, indicate that changes were made, link the licences above and license the dictionary
under CC BY-SA 4.0. Re-using the dictionary does not change the licence of the surrounding
application code.

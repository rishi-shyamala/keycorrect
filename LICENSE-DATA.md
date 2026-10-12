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

---

# Licence of the emoji data

The compiled emoji catalogue `app/src/main/assets/emoji/emoji_en.kce` is a **derived work** of the
Unicode emoji data and the CLDR English annotations listed below, both published under the
[Unicode License v3](https://www.unicode.org/license.txt) (SPDX: `Unicode-3.0`). The licence
permits use, copying, modification and distribution provided the notice below accompanies the
data. Changes were made: only fully-qualified emoji were kept, the "Component" group was dropped,
skin-tone and gender forms were folded under their base emoji, and the result was compiled into
KeyCorrect's text format (see [`core/emoji/FORMAT.md`](core/emoji/FORMAT.md)). The kaomoji list in
the same file was written for KeyCorrect and is part of KeyCorrect's own data (GPL-3.0-or-later).

| Source | Version | Licence |
|---|---|---|
| Unicode `emoji-test.txt` (emoji, order, groups) | Unicode 17.0 (`https://unicode.org/Public/17.0.0/emoji/emoji-test.txt`) | Unicode License v3 |
| CLDR `annotations/en.xml` and `annotationsDerived/en.xml` (names, keywords) | CLDR release-48.2 (`https://github.com/unicode-org/cldr/tree/release-48-2/common`) | Unicode License v3 |

The pinned downloads and their checksums are in `tools/data/fetch-sources.sh` and
`tools/data/sources.sha256`.

## Unicode License v3 notice

> UNICODE LICENSE V3
>
> COPYRIGHT AND PERMISSION NOTICE
>
> Copyright © 1991-2025 Unicode, Inc.
>
> NOTICE TO USER: Carefully read the following legal agreement. BY DOWNLOADING, INSTALLING,
> COPYING OR OTHERWISE USING DATA FILES, AND/OR SOFTWARE, YOU UNEQUIVOCALLY ACCEPT, AND AGREE TO
> BE BOUND BY, ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. IF YOU DO NOT AGREE, DO NOT
> DOWNLOAD, INSTALL, COPY, DISTRIBUTE OR USE THE DATA FILES OR SOFTWARE.
>
> Permission is hereby granted, free of charge, to any person obtaining a copy of data files and
> any associated documentation (the "Data Files") or software and any associated documentation
> (the "Software") to deal in the Data Files or Software without restriction, including without
> limitation the rights to use, copy, modify, merge, publish, distribute, and/or sell copies of
> the Data Files or Software, and to permit persons to whom the Data Files or Software are
> furnished to do so, provided that either (a) this copyright and permission notice appear with
> all copies of the Data Files or Software, or (b) this copyright and permission notice appear in
> associated Documentation.
>
> THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
> PARTICULAR PURPOSE AND NONINFRINGEMENT OF THIRD PARTY RIGHTS. IN NO EVENT SHALL THE COPYRIGHT
> HOLDER OR HOLDERS INCLUDED IN THIS NOTICE BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR
> CONSEQUENTIAL DAMAGES, OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
> WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN
> CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA FILES OR SOFTWARE.
>
> Except as contained in this notice, the name of a copyright holder shall not be used in
> advertising or otherwise to promote the sale, use or other dealings in these Data Files or
> Software without prior written authorization of the copyright holder.

Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in the U.S. and other
countries.

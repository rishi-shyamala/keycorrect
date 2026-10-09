# KCD1 dictionary format

Compiled by `DictionaryWriter` (offline), read zero-copy by `Dictionary.open(ByteBuffer)`.
All integers are **little-endian**; `u24` is a 3-byte unsigned integer; `varint` is unsigned
LEB128 (7 data bits per byte, low group first, high bit = continuation). Sections are 8-byte
aligned. Output is a pure function of the *set* of input words/bigrams/trigrams (input order does
not matter) and the metadata, so builds are reproducible.

## Header (32 bytes) and section table

| off | size | field |
|----:|-----:|-------|
| 0 | 4 | magic `"KCD1"` |
| 4 | 2 | format version (`1`) |
| 6 | 2 | reserved (0) |
| 8 | 4 | total length of the dictionary (buffer may be longer; trailing bytes are ignored) |
| 12 | 4 | section count (1..64) |
| 16 | 4 | CRC-32 (java.util.zip) of: header with this field zeroed + section table + sections META, PARAMS, ALPHABET |
| 20 | 12 | reserved (0) |

Section table at offset 32, 12 bytes per entry: `u32 id, u32 offset, u32 length`. Unknown ids
are ignored (forward compatibility); a missing required id, an out-of-bounds range, a duplicate id
or a length that disagrees with PARAMS raises `DictionaryFormatException`. `open` validates magic,
version, length, bounds, header CRC and expected section lengths only (O(sections), ~0.1 ms). It
does **not** checksum the bulk sections (that would touch every page of the mmap); a corrupted bulk
section is not detected.

Version policy: a reader accepts exactly `formatVersion == 1`. Any layout change bumps the version;
purely additive sections do not need a bump.

| id | section | contents |
|---:|---------|----------|
| 1 | META | `i32 dataVersion`, then 3 strings (`u32 byteLength` + UTF-8): language tag, build date, attribution |
| 2 | PARAMS | 64 bytes, see below |
| 3 | ALPHABET | `u16 n` (<= 255), then `n x u16` UTF-16 code units, strictly ascending |
| 4 | TRIE_NODES | `nodeCount x 3` bytes: `childCount u8, label u8, maxQ u8` |
| 5 | TRIE_SAMPLES | `ceil(nodeCount/16) x u32`: `firstChild` of node `16*j` |
| 6 | TERM_BITMAP | `ceil(nodeCount/64) x 12` bytes: `u32 terminalsBefore, u64 bits` |
| 7 | TERM_VALUES | `keyCount x u24`, one per terminal node in node order |
| 8 | MULTI_IDS | `u8 count, count x u24 id` records for keys with several surface forms |
| 9 | WORD_INFO | `wordCount x 2` bytes: `u8 q, u8 flagsAndCase` |
| 10 | ID_TO_NODE | `wordCount x u24` terminal node of the key of each id (`0xFFFFFF` for reserved ids) |
| 11 | CASE_INDEX | `caseCount x (u24 id, u24 poolOffset)` sorted by id |
| 12 | CASE_POOL | `varint byteLength` + UTF-8 surface, for case-irregular words |
| 13 | BIGRAM_INDEX | `ceil(bigramLists/32) x (u32 dataOffset, u32 flaggedBefore)` |
| 14 | BIGRAM_DATA | successor lists, see "N-gram tables" |
| 15 | TRIGRAM_INDEX | `ceil(trigramLists/32) x u32 dataOffset` |
| 16 | TRIGRAM_DATA | successor lists |

PARAMS (all 4 bytes): `wordCount` (incl. 2 reserved), `nodeCount`, `keyCount`, `caseCount`,
`f32 uniMin`, `f32 uniStep`, `f32 bigramMin`, `f32 bigramStep`, `f32 trigramMin`,
`f32 trigramStep`, `bigramLists`, `trigramLists`, `bigramCount` (entries with a probability),
`trigramCount`, `contextOnlyBigrams` (statistic), reserved.

## Words and ids

* Key = `surface.lowercase()` (Unicode lowercase, Locale.ROOT, accents kept). Each key owns one or
  more *surface forms*, each a distinct word id ("i" -> "I"; "iphone" -> "iPhone"; "us" -> "us" and
  "US"). Surfaces are unique across the dictionary.
* Ids: `0 = <s>` (sentence start), `1 = </s>`; real words from `2`, **ordered by descending unigram
  log-probability** (ties: surface string order). A smaller id is therefore a more frequent word,
  which keeps delta-coded n-gram ids small. Ids of one key are ascending, so the first is the most
  probable surface.
* WORD_INFO `flagsAndCase`: bits 0-4 = `WordFlags` (1 POSSIBLY_OFFENSIVE, 2 PROPER_NOUN,
  4 NOT_A_WORD, 8 HAS_CAPITAL (writer-set: surface != key), 16 SPECIAL (ids 0, 1)); bits 6-7 = case
  pattern: `0` surface == key, `1` first char upper-cased, `2` every char upper-cased (all via
  `Character.toUpperCase` on UTF-16 units), `3` custom (full surface stored in CASE_POOL). The
  writer picks the first pattern that reproduces the surface exactly, so decoding is lossless.
* Surface text is **not** stored for patterns 0-2: `word(id)` walks from the id's terminal node
  (ID_TO_NODE) up to the root and applies the pattern.

### Unigram quantization

`q = round((lp - uniMin) / uniStep)` clamped to 0..255 where `lp` is the natural log of the
probability, `uniMin = min(lp)`, `uniStep = (max(lp) - min(lp)) / 255` (0 if all equal). Decoded as
`uniMin + q * uniStep` (float). Max error is `uniStep/2` (about 0.03 nats for a 12-nat range). The
reserved ids have `q = 0`.

## Trie

Breadth-first array trie. Node 0 is the root; handles are BFS indices. Children of a node are
contiguous and sorted by character, and `firstChild(i) = 1 + sum(childCount[j] for j < i)`; only
every 16th value is stored (TRIE_SAMPLES), the rest is a short sum over `childCount` bytes. `label`
is an index into ALPHABET (max 255 distinct code units; the writer rejects larger alphabets).
`maxQ` is the maximum unigram `q` of any word in the node's subtree (including itself), decoded
like a unigram, so `maxLogProbInSubtree` is an exact upper bound.

A node is terminal if its bit is set in TERM_BITMAP; its rank (stored prefix count + popcount) indexes
TERM_VALUES: a `u24` that is either the single word id or, with bit 23 set, an offset into MULTI_IDS.
The parent of node `n` is found by binary search over TRIE_SAMPLES (largest node whose `firstChild
<= n`), so no parent pointers are stored.

## N-gram tables

Bigram and trigram data are *lists of successors*, addressed by ordinal:

* bigram lists: ordinal = `w1` (list `w1` holds `w2` ids); `bigramLists = maxW1 + 1`.
* trigram lists: ordinal = rank of the (w1, w2) context among all contexts that have trigrams,
  ordered by `(w1, w2)`. A bigram entry whose context has trigrams has bit 7 set; the trigram
  ordinal is `flaggedBefore(list) + flagged entries before it in the list`. If a trigram's (w1, w2)
  bigram is not in the input, the writer inserts a *context-only* bigram (`q = 0`) so the context is
  addressable; reads treat `q = 0` as "absent" (`bigramLogProb` returns null, successors skip it).

INDEX holds the data offset (and, for bigrams, the cumulative flagged-entry count) of every 32nd
list; the remaining lists are reached by skipping at most 31 headers.

List layout (empty list = one byte `0`):

```
varint n
if n > 1: varint byteLength        ; bytes after this field (flagged..entries)
          [bigram only] varint flaggedCount
          if n > 32: skip table, ceil(n/32) x { u24 firstId, u24 entryOffset, [bigram only] u24 flaggedBefore }
entries (n of them)
```

With `n == 1` the list is just `varint id, u8 entry` (length and flag count are derivable). Entries
are sorted by id; each is `varint (id - prevId - 1)` (prevId starts at -1) then the entry byte:
`bits 0-6 = q`, `bit 7 = has trigram list` (bigram table only). In lists with a skip table, the
first entry of every 32-entry block omits its id varint (it is `firstId` in the skip table) and
deltas restart inside the block, so a lookup is a binary search over the skip table plus a scan of
at most 32 entries. `topSuccessors` scans the whole list keeping the best `k` (tie: smaller id).

N-gram probabilities are conditional natural-log probabilities `ln P(w2 | w1)` /
`ln P(w3 | w1, w2)` (unsmoothed or discounted as the build tool chooses; **no back-off weights are
stored** - callers back off when a lookup returns null). Quantization is 7-bit per table:
`q = 1 + round((lp - min) / step)`, `step = (max - min) / 126`, decoded as `min + (q - 1) * step`
with `min/step` from PARAMS; `q = 0` is reserved. Max error `step/2` (about 0.05 nats for 13 nats).

## Size (typical)

Per word about 20 bytes in the synthetic data (3.4 trie nodes per word x 3 bytes + samples and
bitmap + terminal value 3 + id->node 3 + info 2); natural word lists share more prefixes
(~2.3 nodes per word). Per bigram about 2.9 bytes, per trigram about 3.7 bytes with the distribution
used in the
tests (real, more skewed data compresses better). A 200k-word / 1.5M-bigram / 500k-trigram synthetic
dictionary is 9.96 MiB.

## Compatibility with memory mapping

`Dictionary.open` takes a `ByteBuffer` (heap, direct or mapped) positioned at the start of the
dictionary; it slices it and sets its own little-endian order on the slice, so the caller's
position/limit/order are untouched. All reads are absolute `get*(index)` calls, so there is no
shared mutable state and any number of threads may read concurrently.

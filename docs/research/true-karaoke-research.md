# True Karaoke (word-synced lyrics) — research

**Status:** research only. No production code was written, changed or proposed for merge.
**Baseline:** `master` @ `147e7b7ece11cc7cb478f992e88b5cbab6a8be41` (verified).
**Researched:** 2026-09-17.

Every claim below is tagged:

| Tag | Meaning |
|---|---|
| **[FACT]** | Read directly from this repository at the baseline SHA, or from a primary source fetched during this research. |
| **[INFER]** | Derived from a source, but not stated by it verbatim. |
| **[REC]** | A recommendation of this document. |
| **[OPEN]** | Unresolved; needs a live experiment or a decision. |

> **Quarantine.** This research was carried out from current `master` only. PR #4, its diffs, its commits, and any abandoned karaoke branch were never opened, fetched by content, diffed, searched, or used. `git log`/`git grep` were never run against any ref other than the baseline working tree. One abandoned branch *name* appeared unavoidably in `git fetch` output; nothing in it was inspected.

---

## A. Current-master architecture map

Three files own lyrics; a fourth and fifth render them. **[FACT]**

| File | Role |
|---|---|
| `TMessagesProj/src/main/java/org/telegram/messenger/SyncedLyricsController.java` | Model, LRC parser, disk persistence, per-account cache |
| `TMessagesProj/src/main/java/org/telegram/ui/SyncedLyricsEditorFragment.java` | Editor: import, online search, save, delete, undo/redo |
| `TMessagesProj/src/main/java/org/telegram/ui/Components/LyricsOnlineSearch.java` | LRCLIB read-only client |
| `TMessagesProj/src/main/java/org/telegram/ui/Components/AudioPlayerAlert.java` | Full player: lyrics `RecyclerListView`, follow engine, emphasis |
| `TMessagesProj/src/main/java/org/telegram/ui/Components/FragmentContextView.java` | Compact player: one-line active lyric |

### A.1 Model

`SyncedLyricsController.Line` — `long timeMs`, `String text`, `boolean timed`. `Lyrics` — `ArrayList<Line> lines`, `String source` (the raw text as saved), `Kind {MISSING, SYNCED, PLAIN, MALFORMED}`, `Source {NONE, LOCAL, EMBEDDED}`. **[FACT]**

`Lyrics.lineAt(long positionMs)` is a binary search for the last line whose `timeMs <= positionMs`, returning `-1` before the first line. `isSynced()` is `lines.get(0).timed`. **[FACT]** (`SyncedLyricsController.java:115–134`)

### A.2 Parser — **the single most important finding in this document**

`SyncedLyricsController.java:29`:

```java
private static final Pattern WORD_TIMESTAMP = Pattern.compile("<\\d{1,3}:\\d{1,2}(?:[\\.:]\\d{1,3})?>");
```

and `:194`:

```java
String text = WORD_TIMESTAMP.matcher(sourceLine.substring(end)).replaceAll("").trim();
```

**The parser already recognises Enhanced-LRC word tags and deliberately strips them.** **[FACT]** `LOOKS_TIMED` (`:31`) also accepts a line beginning `<\d{1,3}:`, so a word-tagged line is classified as timing syntax rather than as untimed content.

Consequences, all **[FACT]**:

* An Enhanced LRC file imported, pasted or fetched today parses to **correct, clean lines** with the word tags invisibly discarded. There is no crash, no corruption, no `MALFORMED`.
* `SyncedLyricsController.save()` (`:463`) writes `source.getBytes(UTF_8)` — **the editor's raw text, verbatim** — to the `.lrc` sidecar. Word tags typed or pasted into the editor are therefore **already persisted losslessly and round-tripped** today; they are only dropped at *parse* time, not at *storage* time.

**[INFER]** The narrowest possible insertion point for word timing is one method: capture what `WORD_TIMESTAMP` currently throws away, and attach it to the `Line` it belongs to. Storage, the editor, the sidecar format and the online-search path need no change at all to *carry* word timing.

### A.3 Line selection and scrolling (full player)

* `updateLyrics(boolean)` (`AudioPlayerAlert.java:2571`) builds `visibleLyrics` — an index map from row → line that **omits timed blanks** (`!TextUtils.isEmpty(text)` for `SYNCED`). Rows are therefore *not* 1:1 with lines. **[FACT]**
* `updateLyricsFollow(boolean)` (`:3236`) is the follow engine. It reads `SyncedLyricsController.positionMs(message)`, resolves `lineAt(position)`, maps it to a row via `rowForLyricsLine`, and pre-rolls into the next line using `lead = min(LYRIC_FOLLOW_LEAD_MAX /*440ms*/, max(80, gap/2))`. **[FACT]**
* `setLyricsEmphasis(fromRow, toRow, progress)` / `lyricsEmphasisOf(row)` (`:3215`, `:3223`) hold a continuous 0..1 emphasis value.
* `applyLyricsDepth(View)` (`:3397`) applies emphasis **directly to the attached view** — `setAlpha`, `setScaleX/Y`, `setTextColor` via `ColorUtils.blendARGB`, and a typeface crossover at `emphasis >= 0.5f`. It explicitly does **not** rebind. **[FACT]**

### A.4 Row rendering

`LyricsAdapter.onCreateViewHolder` (`:3673`) creates a **plain `android.widget.TextView`**; `onBindViewHolder` calls `textView.setText(currentLyrics.lines.get(line).text)` with a `String`. No spans, no custom draw. **[FACT]**

### A.5 Playback clock — **the second most important finding**

`MediaController.startProgressTimer` (`MediaController.java:1589–1653`) ends with:

```java
}, 0, 17);
```

A `Timer` at **17 ms** (~58.8 Hz) that marshals to the UI thread and posts `NotificationCenter.messagePlayingProgressDidChanged`. **[FACT]**

`AudioPlayerAlert.didReceivedNotification` → `updateProgress(messageObject)` → (last statement) `updateLyrics(true)` → `updateLyricsFollow(true)`. **[FACT]**

**The lyrics UI in the full player is already driven at frame rate.** Word karaoke needs **no new clock, no `Choreographer` callback, and no extra invalidation source**. **[INFER]**

Position source is `MediaController.getProgressMs()` → `audioPlayer.getCurrentPosition()` (ExoPlayer), falling back to `audioProgress * duration` (`SyncedLyricsController.java:660`). **[FACT]**

### A.6 Compact player

`FragmentContextView.updateMusicLyrics()` (`:2026`) computes one active line and calls `titleTextView.setText(...)` on an `AudioPlayerAlert.ClippingTextViewSwitcher`. It is driven by **scheduled `advanceLyric` runnables and play-state changes only — it never subscribes to the 17 ms progress tick.** **[FACT]**

**[INFER]** Word karaoke in the compact player would require introducing a per-frame clock into a view that currently has none, on a surface that is one clipped, switching line of text. That is a disproportionate cost and a direct risk to protected compact-player behaviour.

---

## B. Existing behaviour that must be preserved

These are contracts, not preferences. **[REC]**

1. **One visible lyric line is one `RecyclerView` row.** Word timing lives *inside* a row. No adapter may ever emit a row per word.
2. **Line timestamps alone select the line and drive scrolling.** `updateLyricsFollow`, `rowForLyricsLine`, `scrollLyricsToRow` and the 440 ms pre-roll must be untouched. Word progress must never feed back into line selection.
3. `visibleLyrics` row↔line indirection and the timed-blank rule (`:2582`) stay exactly as they are.
4. `applyLyricsDepth`'s emphasis/alpha/scale/colour/typeface treatment of *surrounding* lines stays as-is.
5. Plain (`Kind.PLAIN`) rendering — no depth falloff, no invented focus — stays as-is.
6. `save()` writes the editor text verbatim; the `.lrc` sidecar keeps its current meaning.
7. The LRCLIB client's request flow, strict Synced/Normal separation, validation and error taxonomy stay as-is.
8. Compact player geometry and behaviour stay as-is, and **no timing markup may ever reach `titleTextView`**.

**Components that must not learn about karaoke:** `FragmentContextView`, `MediaController`, `FileLoader`, `LyricsOnlineSearch` (unless a second provider is added later, and then only as a sibling class), the editor's undo/redo and save machinery.

---

## C. Word-sync format findings

### C.1 Enhanced LRC ("A2 extension")

Syntax: a normal line timestamp, then inline `<mm:ss.xx>` tags before each timed fragment. **[FACT, corroborated across quicklrc.com, easylrc.com, grokipedia LRC page]**

```
[00:12.50]<00:12.50>I <00:12.80>see <00:13.10>trees
```

| Property | Finding |
|---|---|
| Timestamp semantics | **Start-only.** A fragment ends where the next begins. The last fragment of a line has **no end**. **[FACT]** |
| Line start | The `[mm:ss.xx]` prefix; conventionally equals the first word tag, but not required. **[INFER]** |
| Whitespace | Carried in the text between tags; the space usually trails the preceding word. Ambiguous and implementation-dependent. **[INFER]** |
| Punctuation | No special handling; punctuation rides along inside a fragment. **[INFER]** |
| Repeated words | Fine — timing is positional, not keyed by text. **[FACT]** |
| Multiple singers / background vocals | **Not representable.** **[FACT]** |
| Instrumental gaps | Only via the existing empty timed line. **[FACT]** |
| Malformed data | Degrades to plain LRC in any tolerant parser — including ours today. **[FACT]** |
| Obtainable | **Yes** — see §D. |

**[REC]** This is the format to target. It is the only word-timing format that our existing parser, storage and editor already tolerate end-to-end.

### C.2 TTML (Apple Music / AMLL flavour)

Primary sources: the AMLL TTML specification (`amll-dev/amll-ttml-db/instructions/ttml-specification-en.md`, fetched) and a **real file fetched from the database** (`raw-lyrics/1689087424000-39523898-47876155.ttml`).

Verified structure from the real file **[FACT]**:

```xml
<tt xmlns="http://www.w3.org/ns/ttml"
    xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
    xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
    xmlns:amll="http://www.example.com/ns/amll">
 <head><metadata>
   <ttm:agent type="person" xml:id="v1"/>
   <amll:meta key="musicName" value="かくれんぼ"/> …
 </metadata></head>
 <body dur="02:32.337">
  <div begin="00:17.292" end="02:32.337">
   <p begin="00:17.292" end="00:21.042" ttm:agent="v1" itunes:key="L1">
     <span begin="00:17.292" end="00:17.998">こ</span>
     <span begin="00:17.998" end="00:18.304">そ</span>
     …
     <span ttm:role="x-roman">ko so ko so ki mi ni chi ka zu i te</span>
   </p>
  </div>
 </body>
</tt>
```

| Property | Finding |
|---|---|
| Timestamp semantics | **`begin` AND `end` on every span** — true intervals, not just starts. **[FACT]** |
| Granularity | **Syllable, not word.** The verified Japanese sample has one span per kana. "Word" in `itunes:timing="Word"` is a mode name, not a promise about tokenisation. **[FACT]** |
| Mode switch | `itunes:timing="Word"` vs `"Line"`; in Line mode inner span timestamps are ignored. **[FACT]** |
| Whitespace | Three sanctioned encodings (inside span / bare text node between spans / a zero-length space span). The spec itself calls this a source of "strange issues". **[FACT]** |
| Multiple singers | `<ttm:agent>` in `<head>`, referenced by `<p ttm:agent="v1">`. **[FACT]** |
| Background vocals | `<span ttm:role="x-bg">`, nested inside the main `<p>`. **[FACT]** |
| Translation / romanisation | `ttm:role="x-translation"` / `"x-roman"`, or an Apple-style `<iTunesMetadata>` block. **[FACT]** |
| Validity rules | `begin < end`; children strictly contained in parents; `itunes:key="L1…"` must be continuous. **[FACT]** |
| Namespace stability | The spec documents `http://itunes.apple.com/lyric-ttml-extensions`; the **real file uses `http://music.apple.com/lyric-ttml-internal`**. A parser must not key on the namespace URI. **[FACT]** |
| Untimed text | A span with no `begin`/`end` (the `x-roman` span above) — must be skipped, not treated as timed. **[FACT]** |

**[INFER]** TTML is *richer* than we need and *heavier* than we want: XML parsing, agents, roles, nested background spans, and a syllable/word ambiguity. It is a good **source** format to convert *from*, not a good internal or storage format for this app.

### C.3 Other formats — brief

| Format | Word timing? | Verdict |
|---|---|---|
| **YRC** (Netease), **QRC** (QQ Music), **LYS** (Lyricify Syllable) | Yes — start+duration per fragment. **[INFER]**, from the AMLL database exposing `.yrc`/`.qrc`/`.lys` alongside `.ttml` **[FACT]** | Proprietary-platform formats; only reachable through the same sources as TTML. No independent advantage over Enhanced LRC. |
| **WebVTT** | Yes, via inline `<00:00:12.500>` cue timings | Designed for captions; no lyric ecosystem supplies it. Not obtainable. **[INFER]** |
| **SRT** | **No** — cue-level only | Excluded. |
| **ASS/SSA karaoke** (`\k`, `\kf`) | Yes — *durations* in centiseconds, not absolute times | Real word timing, but supplied by fansub/karaoke communities, not lyric providers. Not obtainable for arbitrary tracks. **[INFER]** |
| **ID3 SYLT** | Yes — synchronised lyrics frame, syllable or line granularity | Read-only relevance only. Our `AudioInfo` reads `USLT`/`ULT` and `©lyr`, **not `SYLT`** **[FACT]**, and embedding/writing is explicitly out of scope. |
| **Musixmatch RichSync** | Yes — JSON with per-word start/end | See §D.4. |

---

## D. Provider / data-source findings

### D.1 LRCLIB — **does not have word timing** (verified against the server source)

Fetched from `raw.githubusercontent.com/tranxuanthang/lrclib/main/server/src/`:

* `entities/lyrics.rs` — `Lyrics`/`SimpleLyrics` carry exactly `plain_lyrics`, `synced_lyrics`, `lyricsfile`, `instrumental`. **No word field.** **[FACT]**
* `lyricsfile.rs` — the newer `lyricsfile` YAML document. The **serialised** line type is:

  ```rust
  struct LyricsfileLine { text: String, start_ms: i64, end_ms: Option<i64> }
  ```

  Line-level only. `build_lyricsfile` constructs these purely from parsed LRC line timestamps. **[FACT]**

* **The trap.** The *deserialiser* accepts a `words` array:

  ```rust
  struct PartialLyricsfileLine { text: Option<String>, start_ms: Option<i64>, words: Vec<PartialLyricsfileWord> }
  struct PartialLyricsfileWord { text: Option<String> }          // <- no timestamp field at all
  ```

  and `line_text()` uses it only to *concatenate the line's text* when `text` is absent. **[FACT]**

**Conclusion [FACT]:** LRCLIB stores and returns **no word-level timing anywhere**, including `lyricsfile`. A `words:` key in a lyricsfile is a text-derivation fallback and **must never be mistaken for karaoke data**. Our existing `LyricsOnlineSearch` already ignores `lyricsfile` entirely, which is correct and should stay that way.

### D.2 AMLL TTML Database — strongest *open* candidate

Repository `amll-dev/amll-ttml-db`. Verified live during this research **[FACT]**:

* Per-platform folders `ncm-lyrics/`, `qq-lyrics/`, `am-lyrics/`, `spotify-lyrics/`, each with an `index.jsonl`; raw files under `raw-lyrics/<file>.ttml`.
* A real index row:

  ```json
  {"id":"1987638572","metadata":[["album",["かくれんぼ"]],["artists",["RUQOA"]],["musicName",["かくれんぼ"]],["ncmMusicId",["1987638572"]],…],"rawLyricFile":"1689087424000-39523898-47876155.ttml"}
  ```
* Index sizes measured by HTTP download: **ncm 5.57 MB, am 4.05 MB, spotify 3.13 MB, qq 2.39 MB** (~15 MB total, uncompressed JSONL).
* A fetched TTML is genuinely syllable-timed (§C.2).
* Licence: contributor-created content is **CC0 1.0**; the repository states lyrics otherwise follow their original providers' terms. **[FACT]** — a *mixed* position, not a clean blanket licence. **[OPEN]**

| Criterion | Assessment |
|---|---|
| Genuine word/segment timing | **Yes**, verified on a real file |
| Auth / tokens / secrets | **None** — plain `raw.githubusercontent.com` GETs |
| Rate limits | GitHub raw limits apply; undocumented for anonymous raw content **[OPEN]** |
| Track matching | **The hard part.** Indexed by platform music ID; we hold only artist/title/duration from the Telegram audio attributes. Matching requires the index. |
| Practical client cost | Downloading ~4 MB of JSONL to answer one lookup is not acceptable on mobile **[INFER]** |
| Falls back silently? | No — a file either exists and is word-timed or it does not exist |
| Maintenance | Active (thousands of commits) **[FACT]** |
| Commercial use | Contributor content CC0; underlying lyrics' status unresolved **[OPEN]** |

**[INFER]** Usable only with either (a) a cached/periodically-refreshed index, (b) a small lookup service we host, or (c) a third-party search front-end (`amlldb.bikonoo.com`, `SearchInAMLLDB`) whose stability and terms are unknown **[OPEN]**.

### D.3 SyncLRC — technically ideal, ethically disqualifying

`github.com/TharukRenuja/SyncLRC`, README fetched. **[FACT]**

* `GET https://api.synclrc.dev/lyrics?track=&artist=&type=karaoke[&album=&duration=]`, and `/search?q=`.
* Returns `"karaoke": "[00:00.00]<00:00.05>..."` — **Enhanced LRC**, exactly the shape our parser already tolerates.
* No authentication documented. Rate limits reported as 60/min search, 300/min lyrics, 2 000/day/IP. AGPLv3. Cloudflare-hosted.

**But its own Legal Disclaimer and Credits state** **[FACT]** that it "acts as an easy gateway", stores nothing, and fetches on demand from **LRCLIB plus LDDC and `syncedlyrics` for Netease, QQ Music, Kugou and Musixmatch**.

**[INFER]** That makes SyncLRC a relay in front of other services' private/unlicensed endpoints. Depending on it would put this app's karaoke feature on top of exactly the access pattern the brief forbids proposing, one volunteer-operated hop away, with no licence to the lyrics and no stability guarantee.

**[REC] Do not integrate SyncLRC.** Its README is, however, a useful corroborating primary source for the market state: *"there is no open and free word-by-word (karaoke-style) synced lyrics provider available."*

### D.4 Musixmatch RichSync

Word-level JSON exists. The free developer tier returns **~30 % lyric previews**; full and synced lyrics and any commercial use require a licensed paid tier; RichSync availability on self-serve tiers is not documented publicly. **[INFER — from secondary sources; developer.musixmatch.com was not reached]** **[OPEN]**

The widely-circulated "RichSync" integrations in open-source players use an undocumented mobile token endpoint, not the licensed API. **[INFER]** **[REC]** Out of scope on the same grounds as §D.3.

### D.5 Provider comparison

| Source | Real word timing | Format | Auth | Licence | Matching | Verdict |
|---|---|---|---|---|---|---|
| **LRCLIB** (current) | **No** | LRC lines | none | open DB | already solved | Keep for lines. Cannot ever supply karaoke. |
| **AMLL TTML DB** | **Yes** (syllable) | TTML | none | CC0 contributor content; underlying unresolved | hard (platform IDs + 4 MB index) | **Best open candidate**; needs a matching answer |
| **SyncLRC** | **Yes** | Enhanced LRC | none | AGPL code; relays others' data | easy (artist/title) | **Reject** — relay over unlicensed sources |
| **Musixmatch RichSync** | **Yes** | JSON | key/licence | commercial | good | **Reject** at current scope |
| **Local alignment** | **Yes** (generated) | ours | n/a | n/a | n/a | See §E |
| **User-authored** | **Yes** | Enhanced LRC | n/a | user's own | n/a | **Free today** — see §L |

---

## E. Forced-alignment findings (generating timing ourselves)

### E.1 What the literature reports

* State of the art for lyrics-to-audio alignment reaches **average absolute error below 0.2 s** on the Jamendo benchmark, robust across languages even when trained on English only. **[FACT, secondary]**
* MIREX's Automatic Lyrics-to-Audio Alignment task scores with a **0.3 s tolerance window**; a commonly cited acceptable word-onset tolerance is **≈250 ms**, about half an average word's duration. **[FACT, secondary]**
* Word-level alignments exist for **JamendoLyrics Multi-Lang**; other standard sets are Hansen, Mauch, DALI, NUS-48E. **[FACT, secondary]**
* Pipelines built from **WhisperX (ASR) + MFA (alignment) + a pitch tracker** are described in the 2026 STARS work as a *"fragmented toolchain"* that *"introduces cascading errors from tool mismatches while failing to capture expressive vocal styles."* **[FACT, secondary]**
* One reported measurement of reference-text-to-vocals forced alignment: **31.6 % gold-norm accuracy overall, 83–92 % on clean songs.** **[FACT, secondary]** — i.e. it works on clean solo vocals and collapses otherwise.

### E.2 Why singing is harder than speech

Named in the singing-ASR literature **[FACT, secondary]**: sustained vowels and **melisma** (one syllable over many notes), a far wider pitch range, vibrato, deliberate pronunciation changes for phrasing, overlapping backing vocals and ad-libs, long instrumental sections with no text to anchor to, and loud polyphonic accompaniment masking consonants — which are precisely the cues CTC/phoneme aligners rely on for boundaries.

**[INFER]** Melisma is the specific killer for *this* feature: it is exactly where a listener most expects the highlight to sit on one word for three seconds, and exactly where a speech-trained aligner is most likely to distribute the following words early.

### E.3 Vocal separation first

Singing-voice separation measurably benefits downstream lyrics alignment. **[FACT, secondary]** Demucs `mdx_extra` reaches ~8.76 dB vocal SDR on MUSDB18. **[FACT, secondary]**

On mobile: in one published comparison, **Open-Unmix took 94.6 ms and Spleeter 23.32 ms per second of audio, while Demucs could not be run at all because the model exceeded the test device's capacity.** **[FACT, secondary]**

### E.4 Candidate technologies

| Technology | Singing-suitable? | Notes |
|---|---|---|
| **wav2vec2 / torchaudio CTC forced alignment** | Speech-trained; usable *with* separation | The canonical reference implementation. `MMS_FA` bundle is **≈1.18 GB**. **[FACT, secondary]** |
| **WhisperX** | Partly — alignment stage is wav2vec2 | Inherits every speech bias; the "fragmented toolchain" critique applies. |
| **MFA** | Speech/phonetics tool | Needs a pronunciation dictionary per language; poor fit for sung, multilingual, emoji-bearing text. |
| **Singing-adapted acoustic models / contrastive audio-text alignment** | **Yes — purpose-built** | Where the <0.2 s numbers come from. Research code, not shipping SDKs. **[OPEN]** |
| **STARS (2026)** | Yes — unified singing transcription + alignment | Newest; maturity and licence unverified. **[OPEN]** |

**[REC]** If we ever generate timing: **separation → singing-adapted aligner**, not a bare speech aligner, and never a speech aligner on the full mix.

---

## F. Device vs backend

Target device: Samsung Galaxy A26 5G, Exynos 1380, 6 GB RAM.

### On-device **[INFER, from the figures in §E.3–E.4]**

| Stage | Estimate |
|---|---|
| Separation | Demucs: **not runnable** on a comparable phone. Spleeter-class: ~23 ms/s → ~5 s for a 3.5-min track, plus a several-hundred-MB-to-GB model and a TFLite/ONNX port that does not exist today |
| Alignment | `MMS_FA`-class at **1.18 GB** is a non-starter; int8 quantisation lands in the low hundreds of MB at best, still far beyond what is acceptable to add to a Telegram fork |
| Runtime | Would require adding ONNX Runtime / TFLite — **a new native dependency**, which contradicts the existing project constraint of adding none |
| Thermals | Minutes of sustained NN inference on a mid-range Exynos while audio plays: throttling and battery drain are certain |

**[REC] On-device alignment is not viable for this app.**

### Backend **[INFER]**

GPU inference for a 3.5-minute track is seconds; CPU inference is realistic but minutes per track. But a backend means: a service to build, host, monitor and pay for; user audio or its fingerprint leaving the device; a cache of derived timings whose copyright status is unclear; and a new secret to manage. None of that is in scope for a client fork, and the brief forbids committing secrets.

**[REC] Neither. Do not generate timing in v1.**

---

## G. Data-model options and trade-offs

### G.1 What the data actually is

The verified TTML sample is **syllable**-timed, and Enhanced LRC tags may sit mid-word. **[FACT]** Therefore the unit is a **timed segment of the line's text**, *not* a word. Naming it `TimedWord` would be a lie that leaks into every later decision. **[REC]**

### G.2 Offset representation

| Option | Pros | Cons |
|---|---|---|
| **UTF-16 offsets into `Line.text`** | Native to Java `String`, `TextView`, `Spannable`, `StaticLayout`, `Canvas` clipping — every consumer we have | Must never split a surrogate pair; must be validated |
| Code-point offsets | Script-neutral | Requires conversion at every render — per frame. Rejected **[REC]** |
| Substring storage | Simple | Duplicates text; breaks on repeated words; O(n) to locate. Rejected **[REC]** |

**[REC] UTF-16 `start`/`end` offsets into the existing `Line.text`, with a hard validation rule that neither offset may fall inside a surrogate pair.** This keeps emoji, Amharic, combining marks, RTL and CJK correct for free, because the offsets index the same string the `TextView` already lays out.

### G.3 Proposed shape (paper only)

```
Line                       (existing — unchanged)
  long   timeMs
  String text
  boolean timed
  Segments segments        // NEW, nullable — null for every line without word timing

Segments
  int[]  startOffset       // UTF-16 index into Line.text, ascending
  int[]  endOffset         // UTF-16 index, endOffset[i] >= startOffset[i]
  long[] startMs           // absolute, ascending
  long[] endMs             // absolute; may be derived (see H)
```

**[REC]** Parallel primitive arrays, not an `ArrayList<TimedWord>`: a 40-line song is ~300 segments, and per-frame lookup must not chase objects or allocate. A line with no word timing stores **`null`** — no empty arrays, no fake segments. This satisfies "existing line-only lyrics must not require fake word objects" literally.

**[OPEN]** Absolute vs line-relative timestamps. Absolute matches `lineAt()` and the existing clock; line-relative survives an `[offset:]` change. Recommend absolute, applying `offset` at parse time exactly as line timestamps already do.

---

## H. Qualification and validation rules

**Principle: no genuine word timing = not karaoke.** Nothing may be synthesised. **[REC]**

A line qualifies for karaoke rendering only if **all** hold:

1. It is `timed` and has non-empty text.
2. `segments != null` and covers **≥ 1** segment.
3. Every `startOffset`/`endOffset` is within `[0, text.length()]`, `start <= end`, and neither splits a surrogate pair.
4. Offsets are **non-overlapping and ascending**.
5. `startMs` is **strictly non-decreasing** across segments.
6. Every `startMs >= line.timeMs` and `< nextLine.timeMs` (or `< duration` for the last line).
7. Coverage of the line's non-whitespace text is **≥ a threshold** (suggest 80 %) — a line with two timed words out of nine is not karaoke.
8. No segment whose text range is whitespace-only.

Per-case handling **[REC]**:

| Case | Rule |
|---|---|
| Only some lines timed | **Per-line** decision. Timed lines render karaoke; the rest render exactly as today. No document-level all-or-nothing. |
| Missing end time (Enhanced LRC, always for the last segment) | `endMs = next segment's startMs`; for the last segment, `min(startMs + cap, nextLine.timeMs)`. **This is derivation of an *end* from a real *start*, not invention of timing** — and it must be flagged in the model so rendering can choose whole-word highlighting rather than a fabricated fill. |
| Decreasing timestamps | Reject the **line** (fall back to line sync). Do not resort. |
| Timestamps outside the line | Reject the line. |
| Duplicate timestamps | Allowed (zero-length segment); renders as an instant flip. |
| Empty / whitespace-only segment | Drop that segment; re-test rule 7. |
| Overlapping offsets | Reject the line. |
| Line text ≠ concatenated segment text | Offsets are authoritative; text is never rebuilt from segments. |
| Malformed tag | Already handled: the tag is stripped, the line stays a normal synced line. **[FACT]** |
| Provider returned plain/line data | Not karaoke. Distinct outcome, distinct message. |

---

## I. Network and security considerations

If a karaoke provider is ever added, it inherits every rule the LRCLIB client already enforces **[FACT, from `LyricsOnlineSearch.java`]** and must not weaken any of them: fixed HTTPS host, `setInstanceFollowRedirects(false)`, explicit connect/read timeouts, a hard response-byte ceiling, bounded reading with no unbounded allocation, streams and connections closed in `finally`, a cancellable request handle, one bounded retry on `429`/`503` with a short `Retry-After`, a static non-identifying client header, and **no account, chat or message identity in any request**.

**No secret may ever be committed.** A provider requiring a key is therefore out of scope for a client-only fork unless the user supplies their own. **[REC]**

Failure taxonomy must stay separable — the existing enum already distinguishes `NOT_FOUND`, `TYPE_UNAVAILABLE`, `NETWORK`, `RATE_LIMITED`, `SERVER`, `MALFORMED`. Karaoke adds one more meaning to `TYPE_UNAVAILABLE`: *"this track exists and has line lyrics, but no word timing"* — which must never be presented as failure, and must never silently downgrade without the user knowing which they got. **[REC]**

---

## J. Rendering options and trade-offs

Target: within the active row, sung text bright, unsung text dim, boundary advancing on real timestamps; **geometry absolutely stable**; surrounding rows keep today's emphasis.

| Option | How | Per-frame cost | Verdict |
|---|---|---|---|
| **A. Span swap** | `SpannableString` + `ForegroundColorSpan`, re-`setText` on each boundary | Re-layout risk; allocation per change; `setText` invalidates the `StaticLayout` | **Reject.** Violates "no per-frame rebuilding". |
| **B. Span with mutable bound** | One custom `CharacterStyle` whose bound is mutated, then `invalidate()` | No re-layout, but span redraw granularity is per-span, and `updateDrawState` cannot express a partial-glyph fill | Workable for whole-word only |
| **C. Two-pass clipped draw** | Subclass the row `TextView`; in `onDraw`, draw the dim layout, then `canvas.save(); canvas.clipRect(0,0,x,h); ` draw the bright layout; `restore()` | **One extra text draw per frame per active row; zero allocation; layout untouched** | **[REC] Recommended** |
| **D. Shader / `LinearGradient` mask** | `TextPaint.setShader` with a moving gradient | Elegant soft edge; shader recreated or matrix-animated per frame; interacts badly with the existing `setTextColor` blend in `applyLyricsDepth` | Possible later polish |

**[REC] Option C.** Reasons, all grounded in what the code already does **[FACT]**: the row is already a bare `TextView`; `applyLyricsDepth` already mutates the attached view every frame without rebinding; and the 17 ms tick already exists. C adds a clip-x computation and one `Layout.draw` to the **single active row only**. Every other row draws exactly as today.

The clip boundary is computed from the layout we already have:

* whole-segment mode → `x = layout.getPrimaryHorizontal(segment.endOffset)` at the segment boundary;
* progressive fill → interpolate between `getPrimaryHorizontal(startOffset)` and `getPrimaryHorizontal(endOffset)` by `(now - startMs) / (endMs - startMs)`.

**[OPEN]** RTL and bidi: `getPrimaryHorizontal` is correct per-run, but a single left-to-right clip rectangle is wrong for an RTL line and for bidi runs within a line. Needs either a per-run clip region or, for v1, **whole-segment highlighting only when the line is not LTR-uniform**. Must be settled with a device experiment.

**[OPEN]** Multi-line wrapped rows: clipping must be per text line within the row, not a single rect.

**[REC]** Progressive fill should be **opt-in per line**, used only when `endMs` is *real* rather than derived (§H) — otherwise the fill rate is a fabrication even though the start was genuine.

**Hard rule:** the karaoke renderer reads `lyricsEmphasisOf(row)`; it never writes it, never calls `notifyItemChanged`, and never touches `visibleLyrics`, `lyricsFollowRow` or the scroller. **[REC]**

---

## K. Playback-clock analysis

* Source: ExoPlayer `getCurrentPosition()` via `MediaController.getProgressMs()`. **[FACT]**
* Cadence: **17 ms**, already marshalled to the UI thread, already reaching `updateLyrics`. **[FACT]**
* Interpolation between callbacks: **none, and none needed** at 17 ms. **[INFER]**
* `Choreographer`: not required. Adding one would create a second clock that could disagree with the first. **[REC] Do not.**
* Battery: the tick runs today whenever music plays, whether or not lyrics are visible. Karaoke adds one clipped text draw on one row while the lyrics page is open. **[INFER]** Negligible relative to the existing tick.
* Seek: `updateLyricsFollow` already recomputes from the real position every tick **[FACT]** — karaoke inherits correct seek behaviour for free, provided segment lookup is also stateless (binary search on `startMs`, no "current segment" cursor). **[REC]**
* Playback speed: `getCurrentPosition()` is wall-clock media time, so speed changes need no special handling. **[INFER]**

---

## L. Recommended architecture for a first clean implementation

**Phase 1 delivers real karaoke with zero new network dependencies, zero new libraries, and one parser change.**

The data path already exists end-to-end (§A.2): the editor stores raw text verbatim, the sidecar round-trips it, and the parser already finds and discards the tags. Users who paste or import an Enhanced LRC file today already have real word timing sitting in their saved file — we are simply throwing it away at the last step.

```
 .lrc sidecar (unchanged, verbatim)
        │
        ▼
 SyncedLyricsController.parse()          ← ONLY parser change:
        │   capture <mm:ss.xx> offsets instead of discarding them
        ▼
 Line { timeMs, text, timed, segments? } ← additive, nullable
        │
        ├──► lineAt() / updateLyricsFollow() / scrollLyricsToRow()   UNCHANGED
        │
        └──► KaraokeRow (TextView subclass): two-pass clipped draw,
             fed by the existing 17 ms tick, reading the active row only
```

**Files that would change** (Phase 1) **[REC]**:

| File | Change |
|---|---|
| `SyncedLyricsController.java` | `Segments` type; capture word tags in `parse()`; validation (§H) |
| `AudioPlayerAlert.java` | Row `TextView` → karaoke-capable subclass; set segment + position on the active row each tick |

**Files that must not change:** `FragmentContextView.java`, `LyricsOnlineSearch.java`, `SyncedLyricsEditorFragment.java`, `MediaController.java`, `FileLoader*`, `build.gradle`, `strings.xml` (Phase 1 adds no user-facing string).

**Blast radius**

* **MUST change:** the parser's word-tag branch; the active row's draw.
* **MAY change:** `strings.xml` (only once a provider or a user-facing toggle exists).
* **MUST NOT change:** compact player, line-sync geometry, scrolling, plain lyrics, online search, editor save, Telegram downloading, Telegram networking, Gradle, workflows.

---

## M. Explicitly NOT to implement

1. Any synthesis of timing — equal division of a line, length-weighted division, character interpolation, or animation that merely *looks* word-synced.
2. One `RecyclerView` row per word, or any word-driven row selection.
3. Word timing in the compact player.
4. On-device or backend forced alignment.
5. SyncLRC, Musixmatch RichSync, or any relay over another service's private endpoints.
6. Reading `lyricsfile.words` from LRCLIB as if it were timing (§D.1).
7. A new JSON/XML/HTTP dependency, a native inference runtime, or any committed secret.
8. A new storage format, a new sidecar, or any change to `save()`.
9. A `Choreographer` or second playback clock.
10. Editor redesign.

---

## N. Editor and persistence implications

`save()` writes editor text verbatim **[FACT]**, so Enhanced LRC survives a round trip today. Therefore **[REC]**:

* Keep `.lrc` as the single source of truth. **No sidecar, no second format, no database column.**
* Treat parsed `segments` as **derived data**, recomputed from the text on every load. Nothing separate to keep in sync, nothing to migrate, nothing to corrupt.
* In the editor, word-timed lyrics remain **plain editable text with visible `<mm:ss.xx>` tags**. Editing a word's text leaves that word's tags intact unless the user deletes them; if they break a tag, validation (§H) demotes that line to line-sync on the next save. That is honest, requires no editor change, and needs no "derived timing" invalidation logic.
* **[OPEN]** Tag visibility. Raw `<00:12.80>` markers in the editor are noise. The existing `TimestampSpan` treatment for line timestamps could be extended to dim them. Deferred — it is presentation, not correctness, and it touches a protected file.

---

## O. Test strategy

**No unit-test source set exists on master** — only `TMessagesProj_AppTests/src/androidTest` **[FACT]**. Parser/model tests therefore need either a new `src/test` source set (a Gradle change, currently forbidden) or an out-of-repo harness of the kind already used for the LRCLIB client. **[OPEN]**

**Parser / model**

Standard line LRC unchanged · genuine word-timed lines · mixed document (some lines timed) · repeated words · punctuation attached to a segment · contractions (`I've`) as one or two segments · Amharic · emoji (segment boundary must not split a surrogate pair) · combining marks · RTL · CJK/Japanese kana syllables · leading/trailing/inner whitespace · timed blank lines · malformed tag · decreasing timestamps · timestamp before the line's own timestamp · timestamp past the next line · overlapping offsets · single-segment line · coverage below threshold · last segment with no end.

**Rendering (device QA)**

Short words · long sustained words (melisma) · rapid lyrics · repeated chorus · wrapped long lines · seek forward/backward · pause/resume mid-word · playback-speed change · track change · scroll away and back (row recycled mid-line) · rotation · BottomSheet drag · fullscreen player · compact player shows **no** markers and no behaviour change · end of song · instrumental gap · a document with no word timing renders byte-identically to today.

**Objective accuracy check [REC]**

Record the screen at 60 fps alongside the audio; for a hand-labelled reference track, measure the frame at which each word's highlight crosses vs the hand-labelled onset. Report mean absolute error and the fraction of onsets within **±250 ms** — the tolerance the alignment literature uses (§E.1) — so "it feels right" is replaced by a number.

---

## P. Risks and unknowns requiring live experimentation

| # | Risk / unknown | Severity |
|---|---|---|
| 1 | **Content availability.** With no provider in Phase 1, karaoke appears only for users who supply an Enhanced LRC file. The feature may be technically perfect and near-invisible. | **High** — product risk, not technical |
| 2 | RTL and bidi clipping correctness (§J) | High |
| 3 | AMLL matching strategy without a 4 MB index download (§D.2) | High |
| 4 | AMLL licence position on non-contributor lyric content | Medium |
| 5 | Wrapped multi-line rows: per-text-line clipping | Medium |
| 6 | Whether derived `endMs` is good enough for progressive fill, or whether whole-segment highlighting is the honest default | Medium |
| 7 | GitHub raw rate limits for anonymous clients | Low |
| 8 | Test infrastructure absence (`src/test`) | Low |

---

## Q. Recommendation

**Conditional GO — for Phase 1 only.**

* **Can we build true word karaoke?** **Yes.** The clock (17 ms), the render surface (a bare `TextView` already mutated per frame), the storage (verbatim `.lrc`), and even the parser's awareness of `<mm:ss.xx>` all exist on master today.
* **Without breaking line sync?** **Yes**, and unusually cleanly: `segments` is nullable and additive, line selection and scrolling are untouched, and a document without word timing takes exactly today's code path.
* **Best source of word timing?** Today, **the user's own file**. Of external sources, only the **AMLL TTML database** is both genuinely word-timed and free of an access-control problem — and it has an unsolved matching problem. LRCLIB **cannot** ever supply it (verified in its source).
* **If timing must be generated?** Separation + a **singing-adapted** aligner — not a bare speech aligner. But **not in this app**: the models are ~1 GB, Demucs does not fit on a comparable phone, and a backend is out of scope.
* **Accuracy to expect?** From a good provider, authored timing — effectively exact. From alignment, **~0.2 s mean absolute error at best**, degrading sharply on melisma, backing vocals and dense mixes.
* **Worth proceeding?** **Yes for Phase 1**, because it is small, additive, reversible, and delivers genuinely real timing. **Not yet** for any provider or alignment work.

### Recommended next engineering step (exactly one)

**Implement Phase 1a: capture, don't render.**

Change `SyncedLyricsController.parse()` to capture the `<mm:ss.xx>` offsets it currently discards into a nullable `Segments` on `Line`, apply the §H validation rules, and **change nothing else** — no rendering, no UI, no provider. The parser becomes strictly more informative; every existing code path ignores the new field and behaves identically.

That is independently reviewable, provably zero-risk to line sync (nothing reads `segments` yet), and it produces the data that makes the rendering phase a self-contained follow-up.

**Phases after approval:** 1a parser capture → 1b clipped-draw rendering on the active row (whole-segment first, progressive fill second) → 2 device QA with the objective accuracy check → 3 *only then* evaluate AMLL matching as a provider.

# RandomShortsToMyShorts — v1

Local Spring Boot app that re-voices a pre-edited short in English and exports it for
YouTube, TikTok and Instagram — without re-cutting the video.

You supply the video and a per-segment English script (timed to the original cuts). The app
synthesizes narration with a local, offline TTS engine (Piper), speeds it up or slows it down
to fit each segment's fixed duration, ducks the original audio under narrated segments (audio
elsewhere is untouched), mixes in sound effects and a background music bed, can blur a region
of the frame for a time range, optionally burns in captions, normalizes loudness, and renders
one file per platform. Before those platform files are rendered, the job pauses at **REVIEW**
with an in-browser preview of the fully composed edit so you can check it before committing to
the final exports.

No cloud calls, no per-video cost, no Content-ID-evasion tricks — this app does not attempt to
defeat copyright detection. If you don't hold rights to the source footage, get permission from
whoever does before uploading anywhere.

## The New Job wizard

The home page (`/`, UI in French) is a step-by-step wizard for creating a job, with a clickable
progress header (only steps you've already reached are clickable - no skipping ahead):

1. **Source video** - upload the file, optional label, and a choice between the guided wizard
   (below) or writing raw `instructions.json` directly (for scripted/batch workflows).
2. **Narration** *(optional - skippable)* - four ways to specify what gets re-voiced:
   - **Automatic (recommended)** - pick the source language, upload the video, done. The app
     transcribes the speech with real timestamps and translates it into English entirely offline
     (see "Automatic transcription + translation" below). No script to write at all.
   - **Timed script**: upload the original-language script as an `.srt` (or paste it) for its
     built-in timing, then paste the English translation as plain text, one line per cue in the
     same order. The page shows a live preview pairing each original line with its English
     translation and timing, and flags a line-count mismatch immediately.
   - **Manual segments**: type each segment's start/end time and English text yourself.
   - **Skip**: keep the original audio untouched (for jobs that only add music/effects/blur).
3. **Sound effects** *(optional)* - timed one-shot clips from the assets library.
4. **Background music** *(optional)* - a looped bed track.
5. **Blur regions** *(optional)* - cover a watermark/logo/face for a time range.
6. **Platforms** - pick YouTube/TikTok/Instagram and toggle loudness normalization.
7. **Review & submit** - a summary of everything configured, plus **Validate** and **Submit**.

"Next" validates whatever you've actually started in the current step and blocks with a specific
message if it's incomplete (e.g. mismatched cue/line counts); "Skip this step" always works on
optional steps and resets that step to empty first, so a half-finished attempt never leaks into
the submitted job. Uploading shows a real progress bar (not just a spinner) since a large video
can take a while and `fetch()` can't report upload progress at all - only `XMLHttpRequest` can.

**Validate** checks the video + instructions instantly - segment overlaps, out-of-range timings, a
missing sound-effect/music file, an undownloaded or non-commercial-licensed voice - all reported
at once, before a job is even created. This mirrors yt-auto's own `--dry-run` philosophy: catch it
before you spend a minute of TTS/ffmpeg work on it.

## Automatic transcription + translation

The single biggest time sink in earlier versions was preparing scripts by hand - timing an SRT,
writing the English translation line by line. "Automatic" mode removes both:

1. **faster-whisper** transcribes the video's original-language audio directly, producing real
   segment timestamps - no SRT needed.
2. **Argos Translate** translates each transcribed line into English.
3. The result is a normal `segments[]` list, fed into the exact same fit/duck/mix/compose pipeline
   as a hand-written script - nothing downstream knows or cares where segments came from.

Both tools are free, offline, and run in an isolated virtual environment (`.venv-ml/`) separate
from Piper's own Python install, since the two shouldn't depend on the same global environment.

**One-time setup:**

```powershell
.\scripts\setup-auto-script.ps1              # installs into .venv-ml, downloads an ar -> en package
.\scripts\setup-auto-script.ps1 -FromLang fr -ToLang en   # add another language pair
```

Then point `application.yml` at that venv (already the default if you keep `.venv-ml` at the
project root):

```yaml
app:
  auto-script:
    python-binary: ./.venv-ml/Scripts/python.exe
    script-path: ./scripts/transcribe_translate.py
    whisper-model: small        # tiny/base/small/medium/large-v3 - bigger = slower, more accurate
    device: cpu
```

Honest limitation: Argos Translate is less accurate than a script you wrote yourself, especially
for names, slang, or genre-specific vocabulary (sports commentary, brand names). For content where
that precision matters, either use "Timed script"/"Manual segments", or switch the translation
engine below. Full list of Argos Translate's supported language pairs:
<https://www.argosopentech.com/argospm/index/>.

### Better translation quality: Claude as the translation engine

"Automatic" mode lets you pick a **Claude** engine instead of Argos Translate for the translation
step (transcription is always faster-whisper either way - Claude only ever translates text, never
audio). There are two ways to use Claude, and they're genuinely different in cost and setup:

**`claude-code` (recommended if you have a claude.ai subscription):** uses your existing Pro/Max
login through the local Claude Code CLI - no API key, no separate bill. Point
`app.auto-script.claude-code-binary` at the `claude.exe` bundled with the Claude Code VS Code
extension:

```yaml
app:
  auto-script:
    translation-engine: claude-code
    claude-code-binary: C:/Users/<you>/.vscode/extensions/anthropic.claude-code-<version>/resources/native-binary/claude.exe
    claude-code-model: sonnet
```

That path is version-specific and **will break silently on the next extension auto-update** -
when a job fails validation saying the binary is missing, just find the new folder under
`.vscode/extensions/` and update the path. The app builds one JSON translation request per video
and pipes it to `claude.exe` over **stdin** (not as a command-line argument - Windows'
`ProcessBuilder` was found to mangle long arguments containing embedded quotes, which is also why
there's no `--json-schema` flag; the prompt just asks for a specific JSON shape in plain text and
the response is parsed out of it). Real cost tradeoff: each call also carries the CLI's own
default system prompt and tool definitions on top of the actual translation request, so it draws
more from your subscription's usage pool per call than the raw API option below - Anthropic's
prompt caching keeps that default system prompt cheap on repeated calls within the same rolling
window, which is why the app deliberately never overrides it with `--system-prompt`. Verified
end-to-end: a real job with `context: "energetic sports commentary"` produced noticeably punchier,
more natural phrasing than a literal pass-through (e.g. *"This next play is absolutely
unbelievable — nobody saw it coming!"* instead of a flat translation of the source line).

**`claude` (pay-per-token API):** needs an **Anthropic API key** from
<https://console.anthropic.com> - a separate, pay-per-token product from any claude.ai
subscription; a Pro/Max plan does not grant API access.

```powershell
$env:ANTHROPIC_API_KEY = "sk-ant-..."
```

Whisper already extracts the text for free, so this engine's only job is translating a handful of
already-short lines in one batched request per video (not one per line), so token volume is tiny -
a few hundred tokens each way for a typical short, billed directly instead of drawn from a
subscription. This path is built and compiles but hasn't been exercised against a real API call in
this environment (no key was available while building it) - the `claude-code` engine above has
been verified end-to-end and is the better-tested option if you already have a subscription.

Both Claude engines get the whole video's lines in one call, so Claude keeps names/terminology
consistent across lines, is told each line's actual time budget so it writes translations that are
naturally speakable in that duration (fewer speed-adjustment/truncation warnings from the fit
engine), and is prompted for natural spoken English rather than a stiff literal translation. An
optional "context" field (e.g. "football commentary, energetic tone") further steers tone and
vocabulary. A missing API key or missing `claude-code-binary` is caught instantly, before any
transcription runs - not after burning a minute of Whisper time.

Every job gets an auto-extracted thumbnail and can have an optional label, so `/jobs` is a list of
recognizable cards, not anonymous ids. A job's page updates its status/stepper live via polling -
no page reloads - and past the compose stage you get **Edit & resubmit**: tweak the instructions
in place and resubmit without re-uploading the video (it's reused from the original job). Once a
job is `DONE`, download files individually or as one ZIP.

---

## Requirements

| | |
|---|---|
| Java | 17+ |
| FFmpeg | `ffmpeg` and `ffprobe` on PATH (or set `app.pipeline.ffmpeg-binary`/`ffprobe-binary`) |
| Piper | the `piper` binary on PATH (or set `app.tts.piper.binary`), plus at least one voice model |

### Reusing the yt-auto Piper setup

If you already have Piper and voices set up for the `yt-auto` project, don't reinstall —
point this app at the same files instead of downloading a second copy:

```yaml
app:
  tts:
    piper:
      binary: C:/path/to/piper.exe          # wherever yt-auto's setup script installed it
      voices-dir: C:/Users/anasa/OneDrive/Desktop/bipath_Youtube/yt-auto/voices
      samples-dir: C:/Users/anasa/OneDrive/Desktop/bipath_Youtube/yt-auto/voice_samples
      default-voice: en_US-john-medium
```

## Choosing a voice

Open `/voices` to listen to every catalog voice before picking one (each row has an audio
preview - a shipped sample if there is one, otherwise the app synthesizes and caches a short
one on first request) and to see gender, quality and license side by side. Don't trust a voice
name or reputation on licensing - Piper's own `.onnx.json` files carry no license info, and the
best-known high-quality male voice (`ryan`) is **CC BY-NC-SA (non-commercial only)**, same as
`hfc_male`. The app also adds a warning to any job's results if it (or a per-segment override)
uses a voice the catalog knows isn't commercial-safe, so this can't slip through silently.

Verified-safe voices already in yt-auto's voices folder (all "medium" quality or below - that's
the ceiling among the ones confirmed safe so far):

| Voice | Gender | Dataset | License |
|---|---|---|---|
| `en_US-bryce-medium` | Male | private recordings | Public domain |
| `en_US-john-medium` | Male | LibriVox | Public domain |
| `en_US-norman-medium` | Male | LibriVox | Public domain |
| `en_US-joe-medium` | Male | OHF-Voice dataset | CC0 |
| `en_US-mike-medium` | Male | OHF-Voice dataset | CC0 |
| `en_US-kristin-medium` | Female | LibriVox | Public domain |
| `en_US-kathleen-low` | Female | rhasspy dataset | CC0 |
| `en_US-ljspeech-high` | Female | LJSpeech | Public domain |
| `en_GB-cori-high` | Female | LibriVox | Public domain |

Verified-safe but **not downloaded yet** - `/voices` shows the exact `python -m
piper.download_voices ...` command for each:

| Voice | Gender | Dataset | License |
|---|---|---|---|
| `en_GB-northern_english_male-medium` | Male | OpenSLR-83 | CC-BY-SA 4.0 |
| `en_GB-southern_english_female-low` | Female | OpenSLR-83 | CC-BY-SA 4.0 |
| `en_GB-aru-medium` | Multi-speaker (12) | Acoustics Research Unit corpus | CC BY 4.0 |
| `en_US-sam-medium` | Non-binary voice project | Sam-Accenture-Non-Binary-Voice | Apache 2.0 |
| `en_US-arctic-medium` | Multi-speaker (18) | CMU ARCTIC | BSD-style, unrestricted |

The two multi-speaker ones (`aru`, `arctic`) need a speaker index appended, e.g.
`en_US-arctic-medium#3` - Piper supports this natively. Which index sounds male/female/best isn't
documented anywhere I could verify, so that's genuine trial and error: download it, then try a
few indices via `/voices` or a quick job.

**Explicitly avoid** - these are real Piper voices you might reach for, each with a specific,
verified reason not to:

| Voice | Why not |
|---|---|
| `en_US-lessac-medium` (Piper's upstream default) | Unclear/restrictive Blizzard Challenge 2013 license |
| `en_US-ryan-high` | CC BY-NC-SA 4.0 - non-commercial only |
| `en_US-hfc_male-medium` | CC BY-NC-SA 4.0 - non-commercial only |
| `en_US-l2arctic-medium` | CC BY-NC 4.0 - non-commercial only |
| `en_US-amy-medium`, `en_US-danny-low`, `en_US-kusal-medium`, `en_GB-alan-medium` | Piper's own card just says "see URL" (MycroftAI mimic3-voices/mimic2) - not independently verifiable as commercial-safe |

Set `voice` per job in `instructions.json`, or per-segment for a mixed-voice video. If you use any
voice not in this catalog at all, the app can't warn you either way - verify it yourself.

Honest limitation: medium quality is a real ceiling on naturalness for the free/local, verified-
commercial-safe options. It's decent, not indistinguishable from a human. Closing that gap would
mean either a paid neural TTS engine or real voiceover - a cost/quality tradeoff, not a code fix.

## Build

```powershell
.\mvnw.cmd clean package
```

## Run

```powershell
java -jar target\random-shorts-to-my-shorts.jar
```

Then open <http://localhost:8080>.

## Using it

1. (Optional) On `/assets`, upload any sound effects and music beds you want to reuse — they're
   a shared library referenced by filename, uploaded once rather than per job.

2. On the home page, upload the source video and an `instructions.json`:

   ```json
   {
     "voice": "en_US-lessac-medium",
     "captions": true,
     "platforms": ["youtube", "tiktok", "instagram"],
     "loudnessNormalize": true,
     "segments": [
       { "start": 0.0, "end": 4.2, "text": "Welcome back to the channel!" },
       { "start": 9.0, "end": 14.5, "text": "This next play is unbelievable." }
     ],
     "soundEffects": [
       { "start": 4.2, "file": "whoosh.mp3", "volume": 1.0 }
     ],
     "music": { "file": "trending_beat.mp3", "volume": 0.15 },
     "blurRegions": [
       { "start": 0, "end": 20, "x": 20, "y": 30, "width": 260, "height": 70, "strength": 18 }
     ]
   }
   ```

   Segments must not overlap and must fall inside the video's duration. Anything *outside* a
   segment keeps the original audio exactly as-is — only narrated windows get replaced.
   `soundEffects`, `music`, and `blurRegions` are all optional.

3. The compose stage runs in the background (`/jobs` lists all of them, `/jobs/{id}`
   auto-refreshes while running). When it reaches **REVIEW**, the page embeds a preview player
   for `work/master.mp4` — narration, ducking, effects, music, blur and captions are all baked
   in exactly as they'll appear in the final files. Click **Render platform exports** to produce
   the three platform files, or fix `instructions.json` and resubmit if something's off.

4. Check the **Warnings** section on a job. If a segment's English script didn't fit even after
   speeding up narration by up to 15%, the audio for that segment was truncated to avoid
   overlapping the next one — the fix is to shorten that segment's script and resubmit.

## Sound effects, music and blur

- **Sound effects** (`soundEffects[]`): a one-shot clip from the assets/sfx library, mixed in at
  `start` on top of everything else at the given `volume` multiplier (1.0 = original level).
- **Music bed** (`music`): a single track from assets/music, looped and trimmed to the video's
  full length, mixed in continuously at `volume` (default 0.15 — deliberately low so it sits
  under narration rather than competing with it).
- **Blur regions** (`blurRegions[]`): blurs a fixed rectangle (`x`/`y`/`width`/`height`, in
  source-video pixels) for a `start`-`end` time range — e.g. to cover a watermark or a face.
  `strength` is the blur radius (higher = blurrier); values above 14 still work, they're
  internally capped only on the chroma plane to satisfy an ffmpeg constraint.
- **Loudness normalization** (`loudnessNormalize`, default `true`): a single-pass `loudnorm`
  pass targeting -14 LUFS so volume is consistent across videos regardless of how many audio
  layers went in. Two-pass (more accurate) normalization is a possible future improvement.

## How segment fitting works

Piper speaks at a controllable rate. For each segment: synthesize once at natural pace, measure
it, then re-synthesize at whatever rate (clamped to 0.85x–1.15x, configurable under `app.fit`)
closes the gap to the segment's fixed duration. If the natural length is still off by more than
that after clamping, the result is hard-trimmed to the slot and a warning is recorded rather than
silently producing an overlapping or unnaturally fast/slow line.

## Known limits in v1

- No ASR/translation — you provide the English segment scripts yourself.
- Blur covers on-screen text/logos if you mark the region yourself; there's no automatic
  detection of what to cover.
- Every platform gets the same edit; only duration cap / crf / audio bitrate vary per platform.
  Per-platform pacing or content differences are a v2 idea.
- No visual timeline editor — instructions are JSON, matching the batch-friendly workflow this
  was built for. The REVIEW-stage video preview is the check-before-you-commit step instead.
- Loudness normalization is single-pass (approximate); two-pass would be more accurate.
- Not yet built: auto-generated thumbnails, reusable intro/outro clips, freeform text overlays
  beyond the segment captions. Worth adding if useful — ask.

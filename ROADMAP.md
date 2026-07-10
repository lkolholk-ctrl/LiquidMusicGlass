# LiquidMusicGlass — Roadmap

Planning notes for what comes after the **11.07 stable** release.

---

## 0. Stabilize 11.07 (first, before any new features)

The 11.07 release shipped a lot at once (the whole Yandex Music section, the
reworked Wave, the self-pause fix). Before building on top, shake out the
real-device edge cases.

- Bug-fix pass on the Yandex section and the Wave, driven by real-device testing.
- Polish what already exists rather than stacking new features on unproven ground.

---

## 1. Audio settings — full JUCE-based audio tuning (easy → hard)

Goal: **maximum audio customization.** Build out the Audio settings on top of the
existing native JUCE FX chain, working from cheapest/safest to most advanced.

**Current chain** (`app/src/main/cpp/automix/AudioFxChain.*`, RT-safe, no alloc in `process`):
`Preamp → EQ(10) → Bass (low shelf) → Loudness comp → Stereo width (M/S) → Compressor → Limiter`

### Tier 1 — quick wins (same math already in the chain)
- **Treble (high shelf)** — symmetric to the existing Bass low-shelf; reuse `makeHighShelf`.
- **Balance L/R** — per-channel gain (or `juce::dsp::Panner`).
- **Mono downmix** — accessibility / single earbud.
- **Fade in/out** on play / pause / seek — anti-click; reuse existing `SmoothedValue`.
- **Reverb / ambience presets** — `juce::dsp::Reverb` (room / hall), RT-safe.

### Tier 2 — medium (headphone-focused)
- **Crossfeed** — `juce::dsp::DelayLine` + IIR; removes the "in-head" stereo image on headphones.
- **Warmth / saturation** — `juce::dsp::WaveShaper` (light tube-style coloration).
- **Playback speed** — JUCE resampling. ⚠️ changes pitch; independent tempo/pitch
  (speed up without the chipmunk effect) is **not** in JUCE — needs an external lib
  (SoundTouch / Rubber Band).
- **Resampler quality** — SRC interpolator choice (Lagrange / WindowedSinc) as an output-quality setting.

### Tier 3 — advanced / audiophile
- **Multiband compressor** — split with `juce::dsp::LinkwitzRileyFilter`, then per-band dynamics.
- **Oversampling ("HQ" toggle)** — `juce::dsp::Oversampling` for cleaner limiter / saturation (heavier CPU).
- **Convolution reverb (IR halls)** — `juce::dsp::Convolution`; needs impulse-response assets, heavier (partitioned FFT).
- **Spectrum analyzer** — `juce::dsp::FFT`; upgrade the existing visualizer (`FFT.kt` / `WaveformVisualizer`).

**Not available in JUCE (would need an external library):** independent time-stretch /
pitch-shift, and any proprietary Poweramp-grade DSP.

---

## Candidate directions (not yet scheduled)

Parked ideas from earlier planning, to slot in after stabilization + audio work:

- **Yandex, deeper** — mixes / personal playlists, podcasts, wave settings (mood / tempo / language), user profile.
- **Player features** — downloads manager, sleep timer, queue management (reorder, "play next", save queue as playlist), crossfade / gapless settings UI.
- **Insights & integrations** — listening stats (top artists, minutes, "wrapped"), home-screen widget, lock-screen / Android Auto, sharing.

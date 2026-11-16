# Tab Player

Learn songs with **staff notation and guitar tablature in sync** — the standard
5-line staff on top, the tab staff with fret numbers directly underneath,
measure by measure.

## Features

- **Dual notation view**: staff + tab, with a moving playhead that highlights
  the current note in both. Tap the score to jump to a spot.
- **Playback at any speed**: tempo slider from 40 to 200 BPM, play/pause/stop,
  loop the whole song.
- **5 sounds per instrument**, all synthesized on-device (no samples):
  - Guitar: Acoustic Steel, Nylon Classic, Clean Electric, Distorted, Muted Pluck
  - Bass: Fingered, Picked, Slap, Fretless, Synth Bass
  - Drums: Rock, Pop, Electronic, Jazz and Lo-Fi kits
  - Keyboard: Grand Piano, Electric Piano, Organ, Music Box, Synth Lead
- **Multi-track files**: pick which track to view and play; guitar, bass,
  drum and keyboard tracks are recognised automatically.
- **Built-in demo**: "Ode to Joy" with a guitar and a drum track.

## File support

| Format | Status |
| --- | --- |
| Guitar Pro 3/4/5 (`.gp3`, `.gp4`, `.gp5`) | Supported — binary parser verified against the reference corpus |
| Guitar Pro 7 (`.gp`, ZIP + GPIF XML) | Supported |
| ASCII text tab (`.txt`) | Supported |
| Guitar Pro 6 (`.gpx`, BCFZ container) | Not supported — re-save as `.gp` (GP7) or `.gp5` |
| Power Tab (`.ptb`) | Not supported yet |

## Building

Built by GitHub Actions (`.github/workflows/build-apk.yml`): unit tests for
the parsers run first, then the release APK is signed and published on tags.

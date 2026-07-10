# TapMeteors

A **synthwave, high-neon, isometric remix of the 1979 arcade rock-blaster**
for the RayNeo X3 Pro AR glasses — hue-cycling wireframe meteors drifting
over a glowing grid, a particle storm for every kill, and imperial saucers
with their own beat. Built on OpenGL ES 3.0, additive vector lines on black
(transparent on the waveguide), rendered side-by-side per eye on the glasses.

You are the galaxy's least appreciated **space sweeper**: a gloomy,
long-suffering custodian whose job — nobody ever asks if he *wants* it — is
saving the planet from meteors and imperial space ships by sweeping the
cosmos. He mutters about it, aloud, in a permanently unimpressed voice.

## Controls — two gestures, no settings menu

| Gesture | Action |
|---|---|
| **Swipe** on the right temple pad | Turn the ship 36° per swipe — forward = clockwise, back = counter-clockwise |
| **Tap** | Thrust (inertia + drift, classic feel). Also starts / retries. |
| — | **The cannon fires itself.** Point the nose, ride the drift. |

## The game

- **Waves of neon meteors** — large rocks split into two mediums, mediums
  into two smalls (20 / 50 / 100 points). Each wave adds a rock and a bit of
  speed. **No walls, no arena rectangle**: fly through the edge of the screen
  and appear on the opposite side, arcade-style (with wrap-ghost rendering so
  rocks never pop), over an endless heartbeat-throbbing floor grid.
- **3 ships**, +1 every 10,000 points.
- **Power-ups that change with the level** — once a wave (and again if you
  let one fade), a spinning diamond shimmers onto the field. Its gift is
  keyed to the wave, cycling through five: **RAPID FIRE** (wave 1, 6, ...),
  **TRIPLE SHOT**, **SHIELD**, **PIERCING BOLTS** (shots carve through whole
  rock chains), and **TIME WARP** (the rocks slow to half speed — you don't).
  Ten seconds each, shown as a draining color bar under the wave label; lost
  with your ship.
- **The imperial cruiser** — a disco saucer of stacked spinning rings and
  hue-strobing spokes that wanders the field throwing **rotating spiral
  bursts**, and every six seconds **drops the beat**: a sub-boom, a screen
  throb, and a full radial ring of bolts (200 points). From wave 4 the
  empire sends the **hunter** — faster, wobblier, and it aims at *you*
  (1,000 points). Imperial bolts crack meteors too, so the empire
  redecorates the field as it goes. The sweeper is not thrilled about any
  of this and says so.
- **The heartbeat** — the iconic two-tone thump pulses underneath play and
  **tightens as the field empties** (and quickens while a saucer prowls),
  with the arena border throbbing in time.
- Wave clears are logged in rainbow text with the enthusiasm of a man
  stamping forms ("PLANET SAVED. AGAIN.", "DEBRIS FILED UNDER D", "DON'T
  PANIC. IT'S HANDLED.") while the sweeper mutters a matching remark.

## The sweeper's voice

Eighteen lines of dry cosmic resignation — clock-in grumbles, wave-clear
non-celebrations, imperial complaints ("They never wipe their boots."),
death soliloquies, and a high-score line delivered with total indifference.
Pre-generated with **fish.audio S2.1 Pro**
([free developer API](https://fish.audio/blog/s2-1-pro-free-api/)) using the
player voice model
[`1864d40339ae4dbabf832f844c8d1d6f`](https://fish.audio/app/m/1864d40339ae4dbabf832f844c8d1d6f/):

```bash
export FISH_API_KEY=...   # free at fish.audio
python3 tools/generate_tts.py
./gradlew assembleDebug   # clips ship inside the APK; no network at run time
```

Until the clips are generated the app falls back to Android TTS pitched low
and slow (gloom is non-negotiable), so the character works out of the box.

## Sound

All synthesized at first launch, zero audio binaries: the two heartbeat
thumps, auto-cannon zaps, thrust rumble, three sizes of bit-crushed rock
explosion, ship derez, the visitor's **portamento warble loop**, its spiral
volleys and beat-drop sub-boom, warp-in shimmer, wave fanfares, 1UP jingle,
spawn chime, power-up shimmer/collect/expiry, game-over dirge, and a
high-score arpeggio.

## Build & install

```bash
cd ~/Projects/TapMeteors
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

JDK 17, AGP 8.7.3, Kotlin 2.0.21, compileSdk 35 / minSdk 29, zero
dependencies, zero vendor AARs. Binocular SBS auto-enables on RayNeo hardware
(detected by manufacturer identity, never `Build.MODEL` — it reports
`ARGF20`). High score and best wave persist across sessions.

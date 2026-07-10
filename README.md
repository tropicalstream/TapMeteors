# TapMeteors

A **synthwave, high-neon, isometric remix of the 1979 arcade rock-blaster**
for the RayNeo X3 Pro AR glasses — hue-cycling wireframe meteors drifting
over a glowing grid, a particle storm for every kill, and a psychedelic
visitor with its own beat. Built on OpenGL ES 3.0, additive vector lines on
black (transparent on the waveguide), rendered side-by-side per eye on the
glasses.

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
- **The visitor** — a disco saucer of stacked spinning rings and hue-strobing
  spokes that wanders the field throwing **rotating spiral bursts**, and
  every six seconds **drops the beat**: a sub-boom, a screen throb, and a
  full radial ring of bolts (200 points). From wave 4, its little cousin the
  **hunter** warps in — faster, wobblier, and it aims at *you* (1,000
  points). Saucer bolts crack meteors too, so the visitor redecorates the
  field as it goes.
- **The heartbeat** — the iconic two-tone thump pulses underneath play and
  **tightens as the field empties** (and quickens while a saucer prowls),
  with the arena border throbbing in time.
- Wave clears are celebrated with rainbow proclamations of a distinctly
  ungulate persuasion.

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

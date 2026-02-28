<div align="center">
  <img src="docs/screenshots/fog-of-war-hero.jpg" width="300" />
</div>

<h1 align="center">🌫️ Organic Maps — Fog of War Edition</h1>

<p align="center">
  <b>Explore the real world like a video game.</b><br/>
  A fork of <a href="https://github.com/organicmaps/organicmaps">Organic Maps</a> that adds a <i>fog of war</i> overlay — the map is hidden until you physically visit a place.
</p>

---

## What is this?

This is a modified version of [Organic Maps](https://organicmaps.app) with a **fog of war** layer. Unexplored areas are covered by a dark overlay. As you walk, cycle, or drive through the world, the fog lifts along your path, revealing the map underneath — just like in a strategy game.

The fog is revealed by:
- **Your current GPS position** — a circle around you clears in real time
- **Imported GPX tracks** — load your past routes and watch the map light up

<p align="center">
  <img src="docs/screenshots/fog-of-war-map.png" width="300" />
</p>

## Features

- 🗺️ **Fog of war overlay** rendered natively in the Drape engine (no performance hacks)
- 📍 **Real-time reveal** around your GPS position
- 📂 **GPX track support** — import tracks to reveal previously visited areas
- ⚙️ **Configurable settings:**
  - **Reveal radius** (50–2000 m)
  - **Fog opacity** (10–100%)
  - **Fog color** — full HSV color picker

<p align="center">
  <img src="docs/screenshots/fog-of-war-settings.png" width="300" />
  &nbsp;&nbsp;
  <img src="docs/screenshots/fog-of-war-colorpicker.png" width="300" />
</p>

## How it works

The fog is implemented as a second tile-rendering layer in the Drape graphics engine. Each 256×256 map tile gets a corresponding fog tile — a semi-transparent overlay filled with the fog color. Circular cutouts are drawn for GPS tracks and the current position, with anti-aliased edges for smooth transitions.

The fog renders **after** map labels and transit overlays, so it covers everything cleanly. Settings are persisted to `settings.ini` and take effect immediately.

For a deep dive into the architecture, see [`docs/FOG_OF_WAR_ARCHITECTURE.md`](docs/FOG_OF_WAR_ARCHITECTURE.md).

## Building

Follow the standard [Organic Maps build instructions](https://github.com/organicmaps/organicmaps/blob/master/docs/INSTALL.md).

```bash
git clone --recursive https://github.com/denlogv/organicmaps-fog-of-war.git
cd organicmaps-fog-of-war/android
./gradlew assembleWebDebug
```

## Credits

This project is a fork of [**Organic Maps**](https://organicmaps.app) — a free, open-source, privacy-focused offline maps app built by the community.

Organic Maps was created by the founders of MapsWithMe (MAPS.ME) and is powered by [OpenStreetMap](https://www.openstreetmap.org) data. All credit for the incredible map engine, rendering pipeline, and offline capabilities goes to the Organic Maps team and contributors.

**Support the original project:**
- 🌐 Website: [organicmaps.app](https://organicmaps.app)
- 💬 Telegram: [@OrganicMapsApp](https://t.me/OrganicMapsApp) (news) · [@OrganicMaps](https://t.me/OrganicMaps) (community)
- 💬 Matrix: [#organicmaps:matrix.org](https://matrix.to/#/#organicmaps:matrix.org)
- ❤️ Donate: [organicmaps.app/donate](https://organicmaps.app/donate)
- 📖 GitHub: [organicmaps/organicmaps](https://github.com/organicmaps/organicmaps)

## License

Licensed under the Apache License 2.0 — same as the original Organic Maps project. See [`LICENSE`](LICENSE).

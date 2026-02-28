# Fog of War — Architecture

This document describes the implementation of the Fog of War feature in the Organic Maps fork. It covers the rendering pipeline integration, settings system, and Android UI.

## Overview

The fog of war renders a semi-transparent overlay ("fog") over unexplored map areas. Circular cutouts reveal the map along GPS tracks and around the user's current position, with anti-aliased edges for smooth transitions.

The implementation lives entirely within the existing Drape rendering engine — no Canvas fallbacks or post-processing hacks. Fog tiles are generated as raw RGBA pixel buffers and rendered through a second `TileBackgroundRenderer` instance with blending enabled.

## Rendering Pipeline

### Tile Generation

Each map tile (256×256 pixels) gets a corresponding fog tile. Generation happens in a lambda registered as the `tileBackgroundReadFn` callback on the fog renderer.

**File:** `libs/map/framework.cpp` (fog tile lambda in `CreateDrapeEngine()`)

The lambda:
1. Fills the entire 256×256 buffer with the fog color + alpha
2. Iterates over all loaded track points and the current GPS position
3. For each reveal point within range, maps Mercator coordinates → tile pixel coordinates
4. Draws circular cutouts with anti-aliased edges (inner 70% fully clear, gradient to full fog at outer edge)

**Coordinate mapping:** `toPixel()` converts Mercator coordinates to tile-local pixel space. Row 0 = south (OpenGL ES convention, Y-axis is flipped relative to screen space).

**Radius:** The reveal radius is specified in meters and converted to Mercator units via `revealRadiusMercator = revealRadiusMeters / 111320.0`. This ensures consistent physical size regardless of zoom level.

### Fog Renderer

**File:** `libs/drape_frontend/frontend_renderer.cpp`

The fog uses a dedicated `TileBackgroundRenderer` (`m_fogOfWarRenderer`) separate from the normal map background renderer. Key differences:

| Property | Map Background | Fog Overlay |
|----------|---------------|-------------|
| Render step | 2 (below everything) | After TransitSchemeLayer |
| Blending | Disabled | **Enabled** |
| Purpose | Solid base color | Semi-transparent overlay |

The fog renderer is inserted into the render sequence after transit overlays, so it covers map tiles, labels, and routes — creating a true "fog" effect.

### Tile Invalidation

When fog state changes (new GPS position, tracks loaded, settings changed), `InvalidateFogTiles()` is called. This forces the fog renderer to regenerate all visible fog tiles with updated reveal data.

## Settings System

Settings are stored in `settings.ini` using the native `settings::Set/Get` API.

| Key | Type | Default | Range |
|-----|------|---------|-------|
| `FogOfWarEnabled` | bool | `true` | — |
| `FogOfWarRevealRadius` | int | 500 | 50–2000 (meters) |
| `FogOfWarOpacity` | int | 100 | 10–100 (percent) |
| `FogOfWarColor` | int | 0x000000 | Packed RGB (0xRRGGBB) |

**Color storage:** Colors are stored as packed RGB integers. A legacy migration path converts old index values (0–5) to their corresponding RGB values for backwards compatibility.

### Settings Flow

```
prefs_main.xml (UI definition)
  → SettingsPrefsFragment.java (callbacks)
    → Framework.java (native method declarations)
      → Framework.cpp (JNI bridge)
        → framework.cpp (settings::Set/Get + tile invalidation)
```

Changing any fog setting triggers `InvalidateFogTiles()` to immediately reflect the change on the map.

## Android UI

### Settings Section

**File:** `android/app/src/main/res/xml/prefs_main.xml`

The Fog of War settings appear as the first section (`android:order="0"`) in the main settings screen:
- **Reveal Radius** — `SeekBarPreference` (50–2000), displays value with "m" suffix
- **Map Opacity** — `SeekBarPreference` (10–100), displays percentage
- **Fog Color** — Plain `Preference` that opens a custom color picker dialog

### Color Picker

**File:** `android/app/src/main/java/app/organicmaps/settings/ColorPickerView.java`

A custom HSV color picker view with:
- **Hue bar** — horizontal rainbow gradient for hue selection (0°–360°)
- **Saturation-Value panel** — two overlapping `LinearGradient` shaders (white→hue horizontal, transparent→black vertical)
- **Preview** — colored rounded rectangle with hex color code display
- **Adaptive thumbs** — dark stroke on light backgrounds, white stroke on dark backgrounds, with drop shadows

The picker is presented in an `AlertDialog` from `SettingsPrefsFragment`. The preference row shows a `GradientDrawable` swatch icon and hex summary text.

### Fog Layer Toggle

The fog is activated via the layers panel (the diamond/layers icon in the top-left of the map). No separate enable/disable toggle exists in settings — fog is enabled by default.

## Key Files

| File | Role |
|------|------|
| `libs/map/framework.cpp` | Fog tile generation, settings, track management |
| `libs/map/framework.hpp` | Method declarations |
| `libs/drape_frontend/frontend_renderer.cpp` | Fog renderer integration |
| `android/sdk/src/main/cpp/app/organicmaps/sdk/Framework.cpp` | JNI bridge |
| `android/sdk/src/main/java/app/organicmaps/sdk/Framework.java` | Native method declarations |
| `android/app/src/main/java/app/organicmaps/settings/SettingsPrefsFragment.java` | Settings UI callbacks |
| `android/app/src/main/java/app/organicmaps/settings/ColorPickerView.java` | HSV color picker |
| `android/app/src/main/res/xml/prefs_main.xml` | Settings layout |

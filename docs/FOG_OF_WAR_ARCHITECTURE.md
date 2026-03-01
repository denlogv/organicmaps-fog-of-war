# Fog of War — Architecture

Detailed technical reference for the Fog of War feature in this Organic Maps fork. Covers every component from pixel math to Android UI.

---

## 1. High-Level Design

The fog is a **full-screen semi-transparent overlay** tiled identically to the map. Every visible map tile has a paired 128×128 RGBA fog tile computed on the CPU. The GPU blends the fog over the fully-rendered map scene (tiles, labels, routes) using standard alpha blending.

No post-processing, no shaders, no Canvas fallbacks. The implementation hooks into the existing `TileBackgroundRenderer` infrastructure that was already present for the map's solid background color.

**Data flow in one sentence:** GPS positions and saved KML/GPX tracks are cached in framework-side C++ vectors, protected by a mutex. On demand the fog tile lambda reads those vectors, rasterises reveal corridors into a pixel buffer, and ships the buffer to the GPU as a texture.

---

## 2. Data Sources and State

All fog state lives in `Framework` (`libs/map/framework.hpp`).

### 2.1 Member variables

| Member | Type | Purpose |
|--------|------|---------|
| `m_fogTrackSegments` | `vector<vector<PointD>>` | Decimated polylines from all saved KML/GPX tracks (Mercator) |
| `m_fogSegmentBounds` | `vector<RectD>` | Per-segment bounding rect, parallel to `m_fogTrackSegments`, used for tile culling |
| `m_fogGpsPositions` | `vector<PointD>` | Live GPS positions accumulated since fog was enabled (Mercator) |
| `m_fogCurrentPosition` | `optional<PointD>` | Most recent GPS fix; always revealed even if the track list is stale |
| `m_fogDataBoundingRect` | `RectD` | Union bbox of all data above; used for the tile fast-path check |
| `m_fogTrackPointsMutex` | `mutex` | Protects all five members above; tile lambda runs on a background thread |
| `m_fogInvalidateTimer` | `base::Timer` | Throttle timer — prevents re-generating tiles more often than the configured interval |

### 2.2 Two distinct data streams

**Stream 1 — saved tracks** (`m_fogTrackSegments`)  
Loaded from `BookmarkManager` on demand by `UpdateFogTrackPoints()`. Iterates all bookmark groups → track IDs → geometry lines → individual points. The points are decimated (see §4.3) and stored as a flat list of polylines.

**Stream 2 — live GPS** (`m_fogGpsPositions` + `m_fogCurrentPosition`)  
Every call to `Framework::OnLocationUpdate()` appends to `m_fogGpsPositions` when the device has moved ≥10 m since the last sample. The most recent fix is always stored separately in `m_fogCurrentPosition` and revealed regardless of movement threshold.

On `UpdateFogTrackPoints()`, any GPS points already accumulated in `m_fogGpsPositions` are skipped via a set-like merge with `GpsTracker`'s history (matching the last known point, then appending everything after it). This prevents duplicates while picking up positions collected while the app was suspended.

---

## 3. Tile Generation Pipeline

### 3.1 Registration

The fog tile lambda is registered as `tileBackgroundReadFn` when `DrapeEngine` is constructed inside `Framework::CreateDrapeEngine()`. The same function pointer is used for both the map background renderer and the fog renderer — `mode == dp::BackgroundMode::FogOfWar` selects the fog branch.

```
Framework::CreateDrapeEngine()
  tileBackgroundReadFn = [this](TileKey, BackgroundMode) { ... fog lambda ... }
  DrapeEngine::Params.m_tileBackgroundReadFn = tileBackgroundReadFn
    → FrontendRenderer ctor
        m_tileBackgroundRenderer(tileBackgroundReadFn, ..., Default)
        m_fogOfWarRenderer(tileBackgroundReadFn, ..., Default)  // enabled later
```

### 3.2 Request flow

```
OnUpdateViewport()                            ← called every frame from FrontendRenderer
  for each new tile in coverage:
    m_awaitingTiles.insert(key)
    tileBackgroundReadFn(key, FogOfWar)       ← synchronous call on the render thread
      fog lambda executes (CPU rasterisation)
      DrapeEngine::SetTileBackgroundData()
        post SetTileBackgroundDataMessage
          FrontendRenderer processes message
            TexturePool::AllocateTexture()
            upload pixels → GPU texture
            post AssignTileBackgroundTextureMessage
              FrontendRenderer processes message
                m_fogOfWarRenderer->AssignTileBackgroundTexture()
                  m_tileTextures[key] = {pool, textureId}
```

### 3.3 Fog tile lambda — step by step

**File:** `libs/map/framework.cpp`, lambda inside `CreateDrapeEngine()`.

#### Step 1 — Parameters

```cpp
constexpr uint32_t kTileSize = 128;   // 128×128 px; GPU bilinear-filters seamlessly
int fogOpacityPct = GetFogOfWarOpacity();
uint8_t fogAlpha  = fogOpacityPct * 255 / 100;
double revealRadiusMercator = GetFogOfWarRadius() / 111320.0;
```

Resolution is 128×128 rather than 256×256. Since fog has no sharp features, bilinear interpolation at the GPU makes this indistinguishable while being 4× cheaper.

The reveal radius is in **meters converted to Mercator degrees**. 1 degree of latitude ≈ 111 320 m everywhere (longitude varies, but this approximation is accurate enough for a circular fog brush).

#### Step 2 — Effective pixel radius

```cpp
constexpr double kMinRadiusPx = 8.0;
double radiusPx = max(kMinRadiusPx, revealRadiusMercator / tileWidth * kTileSize);
```

`tileWidth` is the tile's Mercator extent. Dividing the reveal radius by this and multiplying by `kTileSize` converts meters → pixels for this specific tile at this specific zoom level.

`kMinRadiusPx = 8` ensures the corridor is always at least 8 pixels wide on the tile even at extreme zoom-out. Without this floor the trail disappears at low zoom levels.

#### Step 3 — Bounding rect fast-path

Before allocating the pixel buffer, the lambda checks `m_fogDataBoundingRect` (inflated by `revealRadiusMercator`) against the tile rect. If there is no GPS data anywhere near this tile it fills the buffer with solid fog and returns immediately — no per-pixel work.

#### Step 4 — Fill with fog colour

```cpp
uint32_t fogPixel = R | (G << 8) | (B << 16) | (fogAlpha << 24);
fill(pixelData, pixelData + 128*128, fogPixel);
```

32-bit writes; the pixel format is RGBA8 with R in the lowest byte.

#### Step 5 — Gradient parameters

```cpp
double innerFraction = 1.0 - gradientPercent / 100.0;
double innerRadiusSq = radiusSq * innerFraction²;
```

`gradientPercent` (0–100) controls what fraction of the radius is the "hard" reveal zone. At 0% the entire disc is a hard edge. At 30% (default) the outer 30% of the radius is a smooth cubic ease-in gradient from fully-revealed to full fog.

The `clearPixel(x, y, distSq)` lambda writes alpha using:
- `distSq ≤ innerRadiusSq` → alpha = 0 (fully revealed)
- `innerRadiusSq < distSq ≤ radiusSq` → alpha = smoothstep(t)² × fogAlpha
- `distSq > radiusSq` → unchanged (full fog)

#### Step 6 — Reveal sources (under `m_fogTrackPointsMutex`)

Three sources are processed in order:

**a) Live GPS positions** (`m_fogGpsPositions`)  
Consecutive positions are connected with `revealSegment()` rather than being rendered as isolated circles. Tiles that both endpoints fall outside of are skipped (the clip test covers both `curInside` and `prevInside` to handle segments that cross a tile boundary).

**b) Current GPS fix** (`m_fogCurrentPosition`)  
Always rendered as a circle regardless of whether it has moved. This keeps the current position revealed even when the throttle has suppressed a full tile refresh.

**c) Saved track segments** (`m_fogTrackSegments`)  
Each segment is first checked against its pre-computed `m_fogSegmentBounds` (inflated by `revealRadiusMercator`). Segments whose bbox doesn't overlap the tile are skipped entirely. Within a segment, individual points are checked against `expandedRect` before being passed to `revealSegment()`.

#### Step 7 — `revealSegment(x0,y0,x1,y1)`

Stroked line segment in pixel space. Bounding box of the segment expanded by `radiusPxF` restricts the pixel loop. For each pixel in the bbox the perpendicular distance to the segment is computed:

```
t = clamp(dot(p-a, b-a) / |b-a|², 0, 1)
dist² = |p - (a + t*(b-a))|²
```

If `dist² ≤ radiusSq`, `clearPixel` is called.

---

## 4. TileBackgroundRenderer

**File:** `libs/drape_frontend/tile_background_renderer.cpp`

One renderer instance is used for the map background (`m_tileBackgroundRenderer`) and a separate one for fog (`m_fogOfWarRenderer`). They share the same class but have different `BackgroundMode` and blending state.

### 4.1 State machine

```
m_currentMode = Default         ← blending off, does nothing
m_currentMode = FogOfWar        ← blending on, tiles actively managed
```

Switching modes via `SetBackgroundMode()` calls `ClearContextDependentResources()` to release all GPU textures, then re-requests the full viewport via `OnUpdateViewport()`.

### 4.2 Tile lifecycle

```
OnUpdateViewport(coverage, zoomLevel, tilesToDelete)
  ┌─ for deleted tiles at current zoom: release texture, erase from m_tileTextures
  ├─ if m_needInvalidation:
  │    flush m_removedTextures cache
  │    re-request ALL tiles in coverage (even if already cached)
  └─ for new tiles not in cache:
       m_awaitingTiles.insert(key)
       tileBackgroundReadFn(key)  ← triggers lambda

AssignTileBackgroundTexture(tileKey, pool, textureId)
  if zoomLevel != m_lastCurrentZoomLevel: discard (stale)
  replace existing texture for this key
  if m_awaitingTiles.empty():
    purge all textures at old zoom levels   ← deferred old-zoom cleanup
```

### 4.3 Zoom transition strategy

Old-zoom tiles are deliberately kept alive as visual fallback during zoom. They render on screen while new-zoom tiles are being computed. Only when `m_awaitingTiles` drains to zero are old-zoom textures released. This prevents the "flash of naked map" that occurred with eager cleanup.

During panning at constant zoom, `tilesToDelete` contains tiles that scrolled off-screen and they are cleaned up immediately (same-zoom check on line 53).

### 4.4 `m_removedTextures` LRU cache

Up to 16 recently-released textures are kept in a deque. On a non-invalidation viewport update, `RestoreRemovedTexture()` checks this cache before requesting a new tile from the lambda. This avoids re-computing fog tiles for tiles that briefly scrolled off-screen and back.

On full invalidation (`m_needInvalidation = true`) the cache is flushed first, so stale reveals don't get restored.

### 4.5 Render

`Render()` batches tiles that share the same GPU texture object, minimising `glBindTexture` calls. Tiles outside `screen.ClipRect()` are skipped. Up to `kTileBackgroundMaxCount` tiles are submitted per draw call via `DrawInstancedTriangleStrip`.

Each tile is a quad in world (Mercator) space positioned relative to `screen.GlobalRect().Center()`. The vertex shader receives `(minX, minY, maxX, maxY)` as a vec4 per instance.

---

## 5. Invalidation Flow

### 5.1 `InvalidateFogTiles()`

```cpp
void Framework::InvalidateFogTiles() {
  UpdateFogTrackPoints();            // re-read BookmarkManager + merge GpsTracker
  m_drapeEngine->EnableFogOfWar(true);
}
```

Called on: settings change, bookmark change, `EnterForeground()`.

### 5.2 `EnableFogOfWar(true)` message path

```
Framework::m_drapeEngine->EnableFogOfWar(true)
  post EnableFogOfWarMessage(enable=true)   ← MessagePriority::High
    FrontendRenderer::ProcessMessage()
      case EnableFogOfWar:
        if mode unchanged:
          m_fogOfWarRenderer->InvalidateTiles(context)  ← sets m_needInvalidation
          m_forceUpdateScene = true                      ← triggers next OnUpdateViewport
        else:
          m_fogOfWarRenderer->SetBackgroundMode(context, FogOfWar)
```

`m_forceUpdateScene = true` is the critical bit: it forces the renderer to call `OnUpdateViewport()` on the next frame even if the camera hasn't moved, which is where `m_needInvalidation` is consumed and new tiles are requested.

### 5.3 Live GPS throttle

Every `OnLocationUpdate()` call that moves ≥10 m:

1. Appends to `m_fogGpsPositions` under mutex
2. Checks `m_fogInvalidateTimer` against a speed-adaptive threshold:

| Speed | Throttle interval |
|-------|-------------------|
| < speed1 km/h (default 5) | interval1 × 0.1 s (default 1.0 s) |
| < speed2 km/h (default 30) | interval2 × 0.1 s (default 0.8 s) |
| < speed3 km/h (default 80) | interval3 × 0.1 s (default 0.5 s) |
| ≥ speed3 km/h | interval4 × 0.1 s (default 0.3 s) |

3. If the timer threshold is reached: resets timer, calls `m_drapeEngine->EnableFogOfWar(true)` — **without** calling `UpdateFogTrackPoints()` (tracks don't change during live GPS movement).

---

## 6. GPS Tracker Integration

When fog is enabled (either at startup or from settings), `GpsTracker` is auto-enabled:

```cpp
// In CreateDrapeEngine(), after drape engine is constructed:
auto & tracker = GpsTracker::Instance();
if (!tracker.IsEnabled())
    tracker.SetEnabled(true);
tracker.Connect(bind(&Framework::OnUpdateGpsTrackPointsCallback, this, _1, _2, _3));
UpdateFogTrackPoints();
m_drapeEngine->EnableFogOfWar(true);
```

`OnUpdateGpsTrackPointsCallback` is the standard GpsTrack diff callback — it receives `(toAdd, toRemove, stats)` tuples and forwards them to the drape engine for the blue GPS trail rendering. Fog does **not** use this callback for its own data; instead it reads `GpsTracker::ForEachTrackPointSafe()` bulk snapshots inside `UpdateFogTrackPoints()`.

### 6.1 Background accumulation and wake-up

`GpsTrack` runs its own background thread and accumulates fixes even while the app is in the background (the OS may still deliver location updates to a foreground service). When the app returns to the foreground:

```
Framework::EnterForeground()
  → InvalidateFogTiles()
      → UpdateFogTrackPoints()
          merge GpsTracker snapshot into m_fogGpsPositions
          re-read BookmarkManager (in case track file was flushed)
      → EnableFogOfWar(true)  ← full tile regeneration
```

The merge logic in `UpdateFogTrackPoints()` walks `GpsTracker::ForEachTrackPointSafe()` and finds the last point already in `m_fogGpsPositions` by exact coordinate match, then appends everything after it. This avoids duplicates while capturing any positions recorded during the suspended period.

**Track file timing:** `GpsTrack` writes to its `.dat` file on its worker thread. A short recording session might not have flushed by the time `UpdateFogTrackPoints()` is called. This is acceptable — the next GPS fix (once the app is back in foreground and location updates resume) will trigger another invalidation via the throttle path.

---

## 7. Track Point Decimation

```cpp
double decimateDist = max(10.0, revealRadiusMeters * 0.3) / 111320.0;  // Mercator
DecimatePoints(seg, decimateDist²);
```

Points closer than 30% of the reveal radius to the previous kept point are dropped. For a 500 m radius this means one point every ~150 m. A typical 1-second GPS track at walking speed (1.5 m/s) produces one useful point every 100 s, reducing point count by ~100×.

`DecimatePoints()` is a simple greedy scan: always keeps the first and last points, advances a write cursor, and only writes a new point when `dist² ≥ threshold`. Applied to both `m_fogTrackSegments` (on load) and `m_fogGpsPositions` (on each `UpdateFogTrackPoints()` call).

---

## 8. Settings System

Settings are stored in `settings.ini` via `platform/settings.hpp`.

### 8.1 Keys and defaults

| settings.ini key | C++ getter | Default | Range |
|-----------------|-----------|---------|-------|
| `FogOfWarEnabled` | `LoadFogOfWarEnabled()` | `true` | bool |
| `FogOfWarRadius` | `GetFogOfWarRadius()` | 500 m | 50–2000 |
| `FogOfWarOpacity` | `GetFogOfWarOpacity()` | 100 % | 10–100 |
| `FogOfWarColor` | `GetFogOfWarColor()` | `0x000000` | packed 0xRRGGBB |
| `FogOfWarGradient` | `GetFogOfWarGradient()` | 30 % | 0–100 |
| `FogThrottleSpeed1/2/3` | `GetFogThrottleSpeed(tier)` | 5/30/80 km/h | int |
| `FogThrottleInterval1/2/3/4` | `GetFogThrottleInterval(tier)` | 10/8/5/3 (×0.1s) | int |

Color is stored as a packed RGB integer (`0xRRGGBB`). A legacy migration path in `GetFogOfWarColor()` converts old index values (0–5) to RGB if the stored value is in that range.

### 8.2 Settings → invalidation chain

Every setter calls `InvalidateFogTiles()`:

```
SetFogOfWarRadius(meters)
  settings::Set(kFogOfWarRadiusKey, meters)
  InvalidateFogTiles()
    UpdateFogTrackPoints()   ← re-decimates at new radius
    m_drapeEngine->EnableFogOfWar(true)
```

### 8.3 Settings flow: Android UI → native

```
prefs_main.xml
  SeekBarPreference / Preference (fog section, order=0)
    ↓ onChange
SettingsPrefsFragment.java
  Framework.nativeSetFogOfWarRadius(value)
    ↓ JNI
android/sdk/src/main/cpp/app/organicmaps/sdk/Framework.cpp
  Java_..._nativeSetFogOfWarRadius()
    frm()->SetFogOfWarRadius(meters)
      ↓
libs/map/framework.cpp
  Framework::SetFogOfWarRadius()
    settings::Set(...)
    InvalidateFogTiles()
```

---

## 9. Android UI

### 9.1 Settings screen

**File:** `android/app/src/main/res/xml/prefs_main.xml`

Fog settings appear at `android:order="0"` — the topmost section of the main settings screen — inside a `PreferenceCategory` titled "Fog of War".

| Preference | Type | Key |
|-----------|------|-----|
| Fog of War (toggle) | `SwitchPreferenceCompat` | `FogOfWarEnabled` |
| Reveal Radius | `SeekBarPreference` (50–2000) | `FogOfWarRadius` |
| Map Opacity | `SeekBarPreference` (10–100) | `FogOfWarOpacity` |
| Edge Gradient | `SeekBarPreference` (0–100) | `FogOfWarGradient` |
| Fog Colour | `Preference` → dialog | `FogOfWarColor` |
| Advanced throttle | nested `PreferenceScreen` | speed/interval keys |

### 9.2 Color picker

**File:** `android/app/src/main/java/app/organicmaps/settings/ColorPickerView.java`

Custom `View` painted entirely in `onDraw()`:
- **Hue bar** — horizontal `LinearGradient` from red(0°) through the spectrum back to red(360°)
- **SV panel** — two overlapping `LinearGradient` shaders: white→hue (horizontal) and transparent→black (vertical), composited with `PorterDuff.Mode.MULTIPLY`
- **Circle thumb on hue bar** and **rectangle thumb on SV panel** — auto-contrasting stroke (dark/light) plus drop shadow

The picker opens in an `AlertDialog` from `SettingsPrefsFragment`. The preference summary displays a `GradientDrawable` colour swatch and a hex string like `#1A2B3C`.

### 9.3 Auto-start track recording

**File:** `android/app/src/main/java/app/organicmaps/MwmActivity.java`

On `onResume()`, if fog is enabled and track recording is not already active, `TrackRecordingService` is started as a foreground service:

```java
if (Framework.nativeIsFogOfWarEnabled() && !TrackRecorder.nativeIsTrackRecordingEnabled())
    TrackRecordingService.startForegroundService(getApplicationContext());
```

This ensures the KML/GPX track file is written continuously so that `UpdateFogTrackPoints()` always has fresh data when reading via `BookmarkManager`.

---

## 10. Render Order

The full render sequence in `FrontendRenderer::RenderScene()`:

```
1. Map background (TileBackgroundRenderer, blending off)
2. Map tiles, buildings, labels, routes ...
3. Traffic overlay
4. Transit scheme
5. ── Fog of War (TileBackgroundRenderer, blending ON) ──
6. My-position arrow, search marks, UI widgets
```

The fog sits above all map content but below the position indicator and UI, so it obscures the map while leaving the user's location visible.

---

## 11. Key Files

| File | Role |
|------|------|
| `libs/map/framework.cpp` | Fog tile lambda, settings getters/setters, `UpdateFogTrackPoints()`, `InvalidateFogTiles()`, `OnLocationUpdate()` throttle, `EnterForeground()` hook |
| `libs/map/framework.hpp` | All fog member variables and method declarations |
| `libs/drape_frontend/frontend_renderer.cpp` | `m_fogOfWarRenderer` lifetime, `EnableFogOfWar` message handler, render order |
| `libs/drape_frontend/tile_background_renderer.cpp` | Tile request/assign lifecycle, zoom-transition fallback, LRU texture cache, `Render()` batching |
| `libs/drape_frontend/tile_background_renderer.hpp` | Renderer state: `m_awaitingTiles`, `m_tileTextures`, `m_removedTextures`, `m_needInvalidation` |
| `libs/drape_frontend/message_subclasses.hpp` | `SetTileBackgroundDataMessage`, `AssignTileBackgroundTextureMessage`, `EnableFogOfWarMessage` |
| `libs/drape_frontend/drape_engine.cpp` | `DrapeEngine::SetTileBackgroundData()`, `DrapeEngine::EnableFogOfWar()` — message posting |
| `libs/map/gps_tracker.hpp` / `gps_track.hpp` | `ForEachTrackPointSafe()`, background thread accumulation |
| `android/sdk/src/main/cpp/app/organicmaps/sdk/Framework.cpp` | JNI bridge for all `nativeGet/Set*` fog methods |
| `android/sdk/src/main/java/app/organicmaps/sdk/Framework.java` | Java declarations for the JNI methods |
| `android/app/src/main/java/app/organicmaps/MwmActivity.java` | Auto-start `TrackRecordingService` when fog is enabled |
| `android/app/src/main/java/app/organicmaps/settings/SettingsPrefsFragment.java` | Settings UI callbacks |
| `android/app/src/main/java/app/organicmaps/settings/ColorPickerView.java` | HSV colour picker widget |
| `android/app/src/main/res/xml/prefs_main.xml` | Settings screen layout |

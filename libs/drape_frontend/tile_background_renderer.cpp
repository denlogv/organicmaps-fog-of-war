#include "drape_frontend/tile_background_renderer.hpp"

#include "drape_frontend/render_state_extension.hpp"
#include "drape_frontend/shape_view_params.hpp"

#include <algorithm>

namespace df
{
TileBackgroundRenderer::TileBackgroundRenderer(
    MapDataProvider::TTileBackgroundReadFn && tileBackgroundReadFn,
    MapDataProvider::TCancelTileBackgroundReadingFn && cancelTileBackgroundReadingFn, dp::BackgroundMode currentMode)
  : m_tileBackgroundReadFn(std::move(tileBackgroundReadFn))
  , m_cancelTileBackgroundReadingFn(std::move(cancelTileBackgroundReadingFn))
  , m_currentMode(currentMode)
  , m_state(CreateRenderState(gpu::Program::TileBackground, DepthLayer::GeometryLayer))
  , m_stateArray(CreateRenderState(gpu::Program::TileBackgroundArray, DepthLayer::GeometryLayer))
  , m_instancing(std::make_unique<dp::Instancing>())
{
  CHECK(m_tileBackgroundReadFn != nullptr, ());
  CHECK(m_cancelTileBackgroundReadingFn != nullptr, ());

  m_state.SetBlending(dp::Blending(false /* isEnabled */));
  m_state.SetDepthTestEnabled(false);

  m_stateArray.SetBlending(dp::Blending(false /* isEnabled */));
  m_stateArray.SetDepthTestEnabled(false);
}

void TileBackgroundRenderer::OnUpdateViewport(ref_ptr<dp::GraphicsContext> context, CoverageResult const & coverage,
                                              int currentZoomLevel, buffer_vector<TileKey, 8> const & tilesToDelete)
{
  m_lastCoverage = coverage;
  m_lastCurrentZoomLevel = currentZoomLevel;
  if (m_currentMode == dp::BackgroundMode::Default)
    return;

  if (context == nullptr || currentZoomLevel <= 0)
    return;

  // Cancel awaiting tile background reading requests for deleted tiles.
  // For fog tiles, keep old-zoom textures as fallback during zoom transitions
  // to avoid flash-of-revealed-map. They'll be cleaned up in
  // AssignTileBackgroundTexture once new-zoom tiles arrive.
  for (auto const & tileKey : tilesToDelete)
  {
    if (m_awaitingTiles.erase(tileKey) > 0)
      m_cancelTileBackgroundReadingFn(tileKey, m_currentMode);

    // Only remove textures at the CURRENT zoom level (same-zoom panning).
    // Old-zoom tiles are kept as fallback during zoom transitions.
    auto it = m_tileTextures.find(tileKey);
    if (it != m_tileTextures.end() && tileKey.m_zoomLevel == currentZoomLevel)
    {
      RemoveTexture(context, it->first, it->second);
      m_tileTextures.erase(it);
    }
  }

  // Request tile background reading for new tiles in the coverage area
  bool const fullRefresh = m_needInvalidation;
  m_needInvalidation = false;

  if (fullRefresh)
  {
    // Flush stale cached textures — fog tiles must always be regenerated
    // with the latest GPS data.
    for (auto const & [k, info] : m_removedTextures)
      info.m_texturePool->ReleaseTexture(context, info.m_textureId);
    m_removedTextures.clear();
  }

  for (int x = coverage.m_minTileX; x < coverage.m_maxTileX; ++x)
  {
    for (int y = coverage.m_minTileY; y < coverage.m_maxTileY; ++y)
    {
      TileKey const key(x, y, static_cast<uint8_t>(currentZoomLevel));

      if (!fullRefresh)
      {
        // Normal path: restore from cache, skip existing tiles.
        auto maybeTextureInfo = RestoreRemovedTexture(key);
        if (maybeTextureInfo)
        {
          m_tileTextures[key] = *maybeTextureInfo;
          continue;
        }

        if (m_tileTextures.count(key) > 0)
          continue;
      }

      // Request (or re-request) the tile. Old textures keep rendering
      // until each is individually replaced — no flicker.
      if (fullRefresh)
      {
        m_awaitingTiles.insert(key);
        m_tileBackgroundReadFn(key, m_currentMode);
      }
      else if (m_awaitingTiles.insert(key).second)
      {
        m_tileBackgroundReadFn(key, m_currentMode);
      }
    }
  }
}

void TileBackgroundRenderer::AssignTileBackgroundTexture(ref_ptr<dp::GraphicsContext> context, TileKey const & tileKey,
                                                         ref_ptr<dp::TexturePool> texturePool,
                                                         dp::TexturePool::TextureId textureId, dp::BackgroundMode mode)
{
  if (context == nullptr)
    return;

  // Ignore textures for wrong background mode.
  if (mode != m_currentMode)
  {
    m_awaitingTiles.erase(tileKey);
    RemoveTexture(context, tileKey, TextureInfo{texturePool, textureId});
    return;
  }

  // Viewport tiles: ignore if zoom level doesn't match current zoom.
  if (tileKey.m_zoomLevel != m_lastCurrentZoomLevel)
  {
    m_awaitingTiles.erase(tileKey);
    RemoveTexture(context, tileKey, TextureInfo{texturePool, textureId});
    return;
  }

  m_awaitingTiles.erase(tileKey);

  // Replace existing tile texture for this key (in-place update, no flicker).
  auto prevIt = m_tileTextures.find(tileKey);
  if (prevIt != m_tileTextures.end())
    prevIt->second.m_texturePool->ReleaseTexture(context, prevIt->second.m_textureId);

  m_tileTextures[tileKey] = {texturePool, textureId};

  // Remove old-zoom tiles only when ALL awaiting new tiles have arrived,
  // so old tiles keep rendering as fallback during the transition.
  if (m_awaitingTiles.empty())
  {
    size_t removedCount = 0;
    auto tileIt = m_tileTextures.begin();
    while (tileIt != m_tileTextures.end())
      if (tileIt->first.m_zoomLevel != tileKey.m_zoomLevel)
      {
        tileIt->second.m_texturePool->ReleaseTexture(context, tileIt->second.m_textureId);
        tileIt = m_tileTextures.erase(tileIt);
        ++removedCount;
      }
      else
        ++tileIt;
    if (removedCount > 0)
    {
      // Old-zoom tiles cleaned up after all new tiles arrived.
    }
  }
}

void TileBackgroundRenderer::Render(ref_ptr<dp::GraphicsContext> context, ref_ptr<gpu::ProgramManager> mng,
                                    ScreenBase const & screen, int zoomLevel, FrameValues const & frameValues)
{
  if (m_currentMode == dp::BackgroundMode::Default)
    return;

  // Render tiles relative to the screen center.
  frameValues.SetTo(m_programParams);
  auto const pivot = screen.GlobalRect().Center();
  math::Matrix<float, 4, 4> const mv = screen.GetModelView(pivot, 1.0f);
  m_programParams.m_modelView = glsl::make_mat4(mv.m_data);

  static std::vector<std::pair<TileKey, TextureInfo>> sortedTiles;
  sortedTiles.clear();

  for (auto const & [tileKey, textureInfo] : m_tileTextures)
    if (screen.ClipRect().IsIntersect(tileKey.GetGlobalRect()))
      sortedTiles.emplace_back(tileKey, textureInfo);

  if (sortedTiles.empty())
    return;

  std::sort(sortedTiles.begin(), sortedTiles.end(), [](auto const & lhs, auto const & rhs)
  {
    auto const lhsTex = lhs.second.m_texturePool->GetTexture(lhs.second.m_textureId);
    auto const rhsTex = rhs.second.m_texturePool->GetTexture(rhs.second.m_textureId);
    if (lhsTex == rhsTex)
      return lhs.first < rhs.first;
    return lhsTex < rhsTex;
  });

  // Render tiles in batches with the same texture.
  uint32_t instanceIndex = 0;
  ref_ptr<dp::GpuProgram> prevProgram = nullptr;
  for (size_t i = 0; i < sortedTiles.size(); ++i)
  {
    auto const & [tileKey, textureInfo] = sortedTiles[i];
    auto const r = tileKey.GetGlobalRect();
    auto const minR = (m2::PointD(r.minX(), r.minY()) - pivot);
    auto const maxR = (m2::PointD(r.maxX(), r.maxY()) - pivot);
    m_programParams.m_tileCoordsMinMax[instanceIndex] = glsl::vec4(
        static_cast<float>(minR.x), static_cast<float>(minR.y), static_cast<float>(maxR.x), static_cast<float>(maxR.y));
    m_programParams.m_textureIndex[instanceIndex] = static_cast<int>(textureInfo.m_textureId);

    auto const tex = textureInfo.m_texturePool->GetTexture(textureInfo.m_textureId);
    bool const nextTexDiff = (i + 1 < sortedTiles.size() && tex != sortedTiles[i + 1].second.m_texturePool->GetTexture(
                                                                       sortedTiles[i + 1].second.m_textureId));
    if ((instanceIndex + 1) == gpu::kTileBackgroundMaxCount || (i + 1 == sortedTiles.size()) || nextTexDiff)
    {
      auto & state = textureInfo.m_texturePool->IsHardwareTexture2dArrayUsed() ? m_stateArray : m_state;
      state.SetColorTexture(tex);

      auto program = mng->GetProgram(state.GetProgram<gpu::Program>());
      if (prevProgram != program)
      {
        context->SetCullingEnabled(false);
        program->Bind();
        prevProgram = program;
      }
      dp::ApplyState(context, program, state);
      mng->GetParamsSetter()->Apply(context, program, m_programParams);
      m_instancing->DrawInstancedTriangleStrip(context, instanceIndex + 1, 4);
      instanceIndex = 0;
    }
    else
      ++instanceIndex;
  }

  if (prevProgram != nullptr)
  {
    prevProgram->Unbind();
    context->SetCullingEnabled(true);
  }
}

void TileBackgroundRenderer::ClearContextDependentResources(ref_ptr<dp::GraphicsContext> context)
{
  CHECK(context != nullptr, ());

  for (auto const & tileKey : m_awaitingTiles)
    m_cancelTileBackgroundReadingFn(tileKey, m_currentMode);
  m_awaitingTiles.clear();

  for (auto const & [tileKey, info] : m_tileTextures)
    info.m_texturePool->ReleaseTexture(context, info.m_textureId);
  m_tileTextures.clear();

  for (auto const & [tileKey, info] : m_removedTextures)
    info.m_texturePool->ReleaseTexture(context, info.m_textureId);
  m_removedTextures.clear();
}

void TileBackgroundRenderer::SetBackgroundMode(ref_ptr<dp::GraphicsContext> context, dp::BackgroundMode mode)
{
  if (m_currentMode == mode)
    return;

  m_currentMode = mode;

  if (context == nullptr)
    return;

  ClearContextDependentResources(context);

  if (m_currentMode != dp::BackgroundMode::Default)
    OnUpdateViewport(context, m_lastCoverage, m_lastCurrentZoomLevel, {});
}

dp::BackgroundMode TileBackgroundRenderer::GetBackgroundMode() const
{
  return m_currentMode;
}

void TileBackgroundRenderer::SetBlendingEnabled(bool enabled)
{
  m_state.SetBlending(dp::Blending(enabled));
  m_stateArray.SetBlending(dp::Blending(enabled));
}

void TileBackgroundRenderer::InvalidateTiles(ref_ptr<dp::GraphicsContext> context)
{
  if (m_currentMode == dp::BackgroundMode::Default || context == nullptr)
    return;

  m_needInvalidation = true;

  for (auto const & [k, info] : m_removedTextures)
    info.m_texturePool->ReleaseTexture(context, info.m_textureId);
  m_removedTextures.clear();
}

void TileBackgroundRenderer::RemoveTexture(ref_ptr<dp::GraphicsContext> context, TileKey const & tileKey,
                                           TextureInfo const & info)
{
  CHECK(context != nullptr, ());

  constexpr size_t kMaxRemovedTexturesInCache = 16;
  if (m_removedTextures.size() == kMaxRemovedTexturesInCache)
  {
    // Remove the oldest texture from the cache
    auto & [oldTileKey, oldInfo] = m_removedTextures.front();
    oldInfo.m_texturePool->ReleaseTexture(context, oldInfo.m_textureId);
    m_removedTextures.pop_front();
  }

  m_removedTextures.emplace_back(tileKey, info);
}

std::optional<TileBackgroundRenderer::TextureInfo> TileBackgroundRenderer::RestoreRemovedTexture(
    TileKey const & tileKey)
{
  for (auto it = m_removedTextures.begin(); it != m_removedTextures.end(); ++it)
  {
    if (it->first == tileKey)
    {
      auto info = it->second;
      m_removedTextures.erase(it);
      return info;
    }
  }
  return std::nullopt;
}

}  // namespace df

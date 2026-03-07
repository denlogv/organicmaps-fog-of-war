package app.organicmaps.settings;

import android.graphics.drawable.GradientDrawable;
import android.widget.FrameLayout;
import androidx.appcompat.app.AlertDialog;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;
import androidx.preference.TwoStatePreference;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.downloader.OnmapDownloader;
import app.organicmaps.editor.LanguagesFragment;
import app.organicmaps.editor.ProfileActivity;
import app.organicmaps.help.HelpActivity;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.downloader.MapManager;
import app.organicmaps.sdk.editor.OsmOAuth;
import app.organicmaps.sdk.editor.data.Language;
import app.organicmaps.sdk.location.LocationHelper;
import app.organicmaps.sdk.routing.RoutingOptions;
import app.organicmaps.sdk.search.SearchRecents;
import app.organicmaps.sdk.settings.MapLanguageCode;
import app.organicmaps.sdk.settings.UnitLocale;
import app.organicmaps.sdk.util.Config;
import app.organicmaps.sdk.util.NetworkPolicy;
import app.organicmaps.sdk.util.PowerManagment;
import app.organicmaps.sdk.util.SharedPropertiesUtils;
import app.organicmaps.sdk.util.log.LogsManager;
import app.organicmaps.util.ThemeSwitcher;
import app.organicmaps.util.Utils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.Locale;

public class SettingsPrefsFragment extends BaseXmlSettingsFragment implements LanguagesFragment.Listener
{
  @Override
  protected int getXmlResources()
  {
    return R.xml.prefs_main;
  }

  private boolean isSubScreen()
  {
    final Bundle args = getArguments();
    return args != null
        && args.getString(androidx.preference.PreferenceFragmentCompat.ARG_PREFERENCE_ROOT) != null;
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState)
  {
    super.onViewCreated(view, savedInstanceState);

    // When navigating into a sub-screen (e.g. "Advanced" fog settings), Android reuses this
    // fragment class with the sub-screen as root. Skip main-screen inits to avoid crashes.
    if (isSubScreen())
    {
      initFogOfWarAdvancedCallbacks();
      return;
    }

    initStoragePrefCallbacks();
    initMeasureUnitsPrefsCallbacks();
    initZoomPrefsCallbacks();
    initMapStylePrefsCallbacks();
    initAutoDownloadPrefsCallbacks();
    initLargeFontSizePrefsCallbacks();
    initTransliterationPrefsCallbacks();
    init3dModePrefsCallbacks();
    initPerspectivePrefsCallbacks();
    initAutoZoomPrefsCallbacks();
    initLoggingEnabledPrefsCallbacks();
    initEmulationBadStorage();
    initUseMobileDataPrefsCallbacks();
    initPowerManagementPrefsCallbacks();
    initBookmarksTextPlacementPrefsCallbacks();
    initShowDownloadedRegionsPrefsCallbacks();
    initPlayServicesPrefsCallbacks();
    initSearchPrivacyPrefsCallbacks();
    initScreenSleepEnabledPrefsCallbacks();
    initShowOnLockScreenPrefsCallbacks();
    initNightNavigationPrefsCallbacks();
    initFogOfWarBasicCallbacks();
  }

  private void updateVoiceInstructionsPrefsSummary()
  {
    final Preference pref = getPreference(getString(R.string.pref_tts_screen));
    pref.setSummary(Config.TTS.isEnabled() ? R.string.on : R.string.off);
  }

  private void updateMapLanguageCodeSummary()
  {
    final Preference pref = getPreference(getString(R.string.pref_map_locale));
    Locale locale = new Locale(MapLanguageCode.getMapLanguageCode());
    pref.setSummary(locale.getDisplayLanguage());
  }

  private void updateRoutingSettingsPrefsSummary()
  {
    final Preference pref = getPreference(getString(R.string.prefs_routing));
    pref.setSummary(RoutingOptions.hasAnyOptions() ? R.string.on : R.string.off);
  }

  private void updateProfileSettingsPrefsSummary()
  {
    final Preference pref = getPreference(getString(R.string.pref_osm_profile));
    if (OsmOAuth.isAuthorized())
    {
      final String username = OsmOAuth.getUsername();
      pref.setSummary(username);
    }
    else
      pref.setSummary(R.string.not_signed_in);
  }

  @Override
  public void onResume()
  {
    super.onResume();

    // Sub-screens (e.g. Advanced fog settings) don't have main-screen prefs.
    if (isSubScreen())
      return;

    updateProfileSettingsPrefsSummary();
    updateVoiceInstructionsPrefsSummary();
    updateRoutingSettingsPrefsSummary();
    updateMapLanguageCodeSummary();
  }

  @Override
  public boolean onPreferenceTreeClick(Preference preference)
  {
    final String key = preference.getKey();
    if (key != null)
    {
      if (key.equals(getString(R.string.pref_osm_profile)))
      {
        startActivity(new Intent(requireActivity(), ProfileActivity.class));
      }
      else if (key.equals(getString(R.string.pref_tts_screen)))
      {
        getSettingsActivity().stackFragment(VoiceInstructionsSettingsFragment.class,
                                            getString(R.string.pref_tts_enable_title), null);
      }
      else if (key.equals(getString(R.string.pref_help)))
      {
        startActivity(new Intent(requireActivity(), HelpActivity.class));
      }
      else if (key.equals(getString(R.string.pref_map_locale)))
      {
        LanguagesFragment langFragment = (LanguagesFragment) getSettingsActivity().stackFragment(
            LanguagesFragment.class, getString(R.string.change_map_locale), null);
        langFragment.setListener(this);
      }
    }
    return super.onPreferenceTreeClick(preference);
  }

  private void initLargeFontSizePrefsCallbacks()
  {
    final Preference pref = getPreference(getString(R.string.pref_large_fonts_size));

    ((TwoStatePreference) pref).setChecked(Config.isLargeFontsSize());
    pref.setOnPreferenceChangeListener((preference, newValue) -> {
      final boolean oldVal = Config.isLargeFontsSize();
      final boolean newVal = (Boolean) newValue;
      if (oldVal != newVal)
        Config.setLargeFontsSize(newVal);

      return true;
    });
  }

  private void initTransliterationPrefsCallbacks()
  {
    final Preference pref = getPreference(getString(R.string.pref_transliteration));

    ((TwoStatePreference) pref).setChecked(Config.isTransliteration());
    pref.setOnPreferenceChangeListener((preference, newValue) -> {
      final boolean oldVal = Config.isTransliteration();
      final boolean newVal = (Boolean) newValue;
      if (oldVal != newVal)
        Config.setTransliteration(newVal);

      return true;
    });
  }

  private void initUseMobileDataPrefsCallbacks()
  {
    final ListPreference mobilePref = getPreference(getString(R.string.pref_use_mobile_data));

    NetworkPolicy.Type curValue = Config.getUseMobileDataSettings();
    if (curValue == NetworkPolicy.Type.NOT_TODAY || curValue == NetworkPolicy.Type.TODAY)
      curValue = NetworkPolicy.Type.ASK;
    mobilePref.setValue(curValue.name());
    mobilePref.setOnPreferenceChangeListener((preference, newValue) -> {
      final String valueStr = (String) newValue;
      NetworkPolicy.Type type = NetworkPolicy.Type.valueOf(valueStr);
      Config.setUseMobileDataSettings(type);
      return true;
    });
  }

  private void initPowerManagementPrefsCallbacks()
  {
    final ListPreference powerManagementPref = getPreference(getString(R.string.pref_power_management));

    @PowerManagment.SchemeType
    int curValue = PowerManagment.getScheme();
    powerManagementPref.setValue(String.valueOf(curValue));

    powerManagementPref.setOnPreferenceChangeListener((preference, newValue) -> {
      @PowerManagment.SchemeType
      int scheme = Integer.parseInt((String) newValue);

      PowerManagment.setScheme(scheme);

      disableOrEnable3DBuildingsForPowerMode(scheme);

      return true;
    });
  }

  private void initLoggingEnabledPrefsCallbacks()
  {
    final Preference pref = getPreference(getString(R.string.pref_enable_logging));

    ((TwoStatePreference) pref).setChecked(LogsManager.INSTANCE.isFileLoggingEnabled());
    pref.setOnPreferenceChangeListener((preference, newValue) -> {
      if (!LogsManager.INSTANCE.setFileLoggingEnabled((Boolean) newValue))
      {
        // It's a very rare condition when debugging, so we can do without translation.
        Utils.showSnackbar(requireView(), "ERROR: Can't create a logs folder!");
        return false;
      }
      return true;
    });
  }

  private void initEmulationBadStorage()
  {
    final Preference pref = findPreference(getString(R.string.pref_emulate_bad_external_storage));
    if (pref == null)
      return;

    if (!SharedPropertiesUtils.shouldShowEmulateBadStorageSetting())
      removePreference(getString(R.string.pref_settings_general), pref);
    else
      pref.setVisible(true);
  }

  private void initAutoZoomPrefsCallbacks()
  {
    final TwoStatePreference pref = getPreference(getString(R.string.pref_auto_zoom));

    boolean autozoomEnabled = Framework.nativeGetAutoZoomEnabled();
    pref.setChecked(autozoomEnabled);
    pref.setOnPreferenceChangeListener((preference, newValue) -> {
      Framework.nativeSetAutoZoomEnabled((boolean) newValue);
      return true;
    });
  }

  private void initPlayServicesPrefsCallbacks()
  {
    final Preference pref = findPreference(getString(R.string.pref_play_services));
    if (pref == null)
      return;

    if (!MwmApplication.from(requireContext())
             .getLocationProviderFactory()
             .isGoogleLocationAvailable(requireActivity().getApplicationContext()))
      removePreference(getString(R.string.pref_privacy), pref);
    else
    {
      ((TwoStatePreference) pref).setChecked(Config.useGoogleServices());
      pref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
        @SuppressLint("MissingPermission")
        @Override
        public boolean onPreferenceChange(@NonNull Preference preference, Object newValue)
        {
          final LocationHelper locationHelper = MwmApplication.from(requireContext()).getLocationHelper();
          boolean oldVal = Config.useGoogleServices();
          boolean newVal = (Boolean) newValue;
          if (oldVal != newVal)
          {
            Config.setUseGoogleService(newVal);
            if (locationHelper.isActive())
            {
              locationHelper.stop();
              locationHelper.start();
            }
          }
          return true;
        }
      });
    }
  }

  private void initSearchPrivacyPrefsCallbacks()
  {
    final Preference pref = findPreference(getString(R.string.pref_search_history));
    if (pref == null)
      return;

    final boolean isHistoryEnabled = Config.isSearchHistoryEnabled();
    ((TwoStatePreference) pref).setChecked(isHistoryEnabled);
    pref.setOnPreferenceChangeListener((preference, newValue) -> {
      boolean newVal = (Boolean) newValue;
      if (newVal != Config.isSearchHistoryEnabled())
      {
        Config.setSearchHistoryEnabled(newVal);
        if (newVal)
          SearchRecents.refresh();
        else
          SearchRecents.clear();
      }
      return true;
    });
  }

  private void init3dModePrefsCallbacks()
  {
    final TwoStatePreference pref = getPreference(getString(R.string.pref_3d_buildings));

    final Framework.Params3dMode _3d = new Framework.Params3dMode();
    Framework.nativeGet3dMode(_3d);

    // Read power managements preference.
    final ListPreference powerManagementPref = getPreference(getString(R.string.pref_power_management));
    final String powerManagementValueStr = powerManagementPref.getValue();
    final Integer powerManagementValue =
        (powerManagementValueStr != null) ? Integer.parseInt(powerManagementValueStr) : null;
    disableOrEnable3DBuildingsForPowerMode(powerManagementValue);

    pref.setOnPreferenceChangeListener((preference, newValue) -> {
      Framework.Params3dMode current = new Framework.Params3dMode();
      Framework.nativeGet3dMode(current);
      Framework.nativeSet3dMode(current.enabled, (Boolean) newValue);
      return true;
    });
  }

  // Argument `powerManagementValue` could be null on the first app run.
  private void disableOrEnable3DBuildingsForPowerMode(@Nullable Integer powerManagementValue)
  {
    final TwoStatePreference pref = getPreference(getString(R.string.pref_3d_buildings));

    if (powerManagementValue != null && powerManagementValue == PowerManagment.HIGH)
    {
      pref.setShouldDisableView(true);
      pref.setEnabled(false);
      pref.setSummary(getString(R.string.pref_map_3d_buildings_disabled_summary));
      pref.setChecked(false);
    }
    else
    {
      final Framework.Params3dMode _3d = new Framework.Params3dMode();
      Framework.nativeGet3dMode(_3d);

      pref.setShouldDisableView(false);
      pref.setEnabled(true);
      pref.setSummary("");
      pref.setChecked(_3d.buildings);
    }
  }

  private void initPerspectivePrefsCallbacks()
  {
    final TwoStatePreference pref = getPreference(getString(R.string.pref_3d));

    final Framework.Params3dMode _3d = new Framework.Params3dMode();
    Framework.nativeGet3dMode(_3d);

    pref.setChecked(_3d.enabled);

    pref.setOnPreferenceChangeListener((preference, newValue) -> {
      Framework.Params3dMode current = new Framework.Params3dMode();
      Framework.nativeGet3dMode(current);
      Framework.nativeSet3dMode((Boolean) newValue, current.buildings);
      return true;
    });
  }

  private void initAutoDownloadPrefsCallbacks()
  {
    final TwoStatePreference pref = getPreference(getString(R.string.pref_autodownload));

    pref.setChecked(Config.isAutodownloadEnabled());
    pref.setOnPreferenceChangeListener((preference, newValue) -> {
      final boolean value = (boolean) newValue;
      Config.setAutodownloadEnabled(value);

      if (value)
        OnmapDownloader.setAutodownloadLocked(false);

      return true;
    });
  }

  private void initMapStylePrefsCallbacks()
  {
    final ListPreference pref = getPreference(getString(R.string.pref_map_appearance));
    pref.setEntryValues(
        new CharSequence[] {Config.UiTheme.SYSTEM.value, Config.UiTheme.LIGHT.value, Config.UiTheme.DARK.value});
    pref.setValue(Config.UiTheme.getUiThemePreference().value);
    pref.setSummary(pref.getEntry());
    pref.setOnPreferenceChangeListener((preference, newValue) -> {
      final var newTheme = Config.UiTheme.ofValue((String) newValue);
      if (!Config.UiTheme.setUiThemePreference(newTheme))
        return true;

      ThemeSwitcher.INSTANCE.synchronizeApplicationTheme();

      final CharSequence summary = pref.getEntries()[newTheme.ordinal()];
      pref.setSummary(summary);
      return true;
    });
  }

  private void initZoomPrefsCallbacks()
  {
    final Preference pref = getPreference(getString(R.string.pref_show_zoom_buttons));

    ((TwoStatePreference) pref).setChecked(Config.showZoomButtons());
    pref.setOnPreferenceChangeListener((preference, newValue) -> {
      Config.setShowZoomButtons((boolean) newValue);
      return true;
    });
  }

  private void initMeasureUnitsPrefsCallbacks()
  {
    final Preference pref = getPreference(getString(R.string.pref_munits));

    ((ListPreference) pref).setValue(String.valueOf(UnitLocale.getUnits()));
    pref.setOnPreferenceChangeListener((preference, newValue) -> {
      UnitLocale.setUnits(Integer.parseInt((String) newValue));
      return true;
    });
  }

  private void initStoragePrefCallbacks()
  {
    final Preference pref = getPreference(getString(R.string.pref_storage));
    pref.setOnPreferenceClickListener(preference -> {
      if (MapManager.nativeIsDownloading())
      {
        new MaterialAlertDialogBuilder(requireActivity(), R.style.MwmTheme_AlertDialog)
            .setTitle(R.string.downloading_is_active)
            .setMessage(R.string.cant_change_this_setting)
            .setPositiveButton(R.string.ok, null)
            .show();
      }
      else
      {
        getSettingsActivity().stackFragment(StoragePathFragment.class, getString(R.string.maps_storage), null);
      }

      return true;
    });
  }

  private void initScreenSleepEnabledPrefsCallbacks()
  {
    final Preference pref = getPreference(getString(R.string.pref_keep_screen_on));

    final boolean isKeepScreenOnEnabled = Config.isKeepScreenOnEnabled();
    ((TwoStatePreference) pref).setChecked(isKeepScreenOnEnabled);
    pref.setOnPreferenceChangeListener((preference, newValue) -> {
      boolean newVal = (Boolean) newValue;
      if (isKeepScreenOnEnabled != newVal)
      {
        Config.setKeepScreenOnEnabled(newVal);
        // No need to call Utils.keepScreenOn() here, as relevant activities do it when starting / stopping.
      }
      return true;
    });
  }

  private void initShowOnLockScreenPrefsCallbacks()
  {
    final Preference pref = getPreference(getString(R.string.pref_show_on_lock_screen));

    final boolean isShowOnLockScreenEnabled = Config.isShowOnLockScreenEnabled();
    ((TwoStatePreference) pref).setChecked(isShowOnLockScreenEnabled);
    pref.setOnPreferenceChangeListener((preference, newValue) -> {
      boolean newVal = (Boolean) newValue;
      if (isShowOnLockScreenEnabled != newVal)
      {
        Config.setShowOnLockScreenEnabled(newVal);
        Utils.showOnLockScreen(newVal, requireActivity());
      }
      return true;
    });
  }

  private void initNightNavigationPrefsCallbacks()
  {
    final Preference pref = getPreference(getString(R.string.pref_auto_night_in_navigation));

    final var isAutoDarkNavigationEnabled = Config.UiTheme.isAutoDarkNavigationEnabled();
    ((TwoStatePreference) pref).setChecked(isAutoDarkNavigationEnabled);
    pref.setOnPreferenceChangeListener((preference, newValue) -> {
      var isChanged = Config.UiTheme.setAutoDarkNavigationEnabled((Boolean) newValue);
      if (isChanged)
      {
        ThemeSwitcher.INSTANCE.synchronizeApplicationTheme();
      }
      return isChanged;
    });
  }

  private void initBookmarksTextPlacementPrefsCallbacks()
  {
    final ListPreference bookmarksTextPlacementPref = getPreference(getString(R.string.pref_bookmarks_text_placement));

    final int currentPlacement = Framework.nativeGetBookmarksTextPlacement();
    bookmarksTextPlacementPref.setValue(String.valueOf(currentPlacement));

    bookmarksTextPlacementPref.setOnPreferenceChangeListener((preference, newValue) -> {
      final int placement = Integer.parseInt((String) newValue);
      Framework.nativeSetBookmarksTextPlacement(placement);
      return true;
    });
  }

  private void initShowDownloadedRegionsPrefsCallbacks()
  {
    final Preference pref = getPreference(getString(R.string.pref_show_downloaded_regions));

    ((TwoStatePreference) pref).setChecked(Framework.nativeIsShowDownloadedRegions());
    pref.setOnPreferenceChangeListener((preference, newValue) -> {
      Framework.nativeSetShowDownloadedRegions((boolean) newValue);
      return true;
    });
  }

  private void initFogOfWarBasicCallbacks()
  {
    final SeekBarPreference radiusPref = getPreference(getString(R.string.pref_fog_of_war_radius));
    radiusPref.setValue(Framework.nativeGetFogOfWarRadius());
    radiusPref.setSummary(radiusPref.getValue() + " m");
    radiusPref.setOnPreferenceChangeListener((preference, newValue) -> {
      Framework.nativeSetFogOfWarRadius((Integer) newValue);
      radiusPref.setSummary(newValue + " m");
      return true;
    });

    final SeekBarPreference opacityPref = getPreference(getString(R.string.pref_fog_of_war_opacity));
    opacityPref.setValue(Framework.nativeGetFogOfWarOpacity());
    opacityPref.setOnPreferenceChangeListener((preference, newValue) -> {
      Framework.nativeSetFogOfWarOpacity((Integer) newValue);
      return true;
    });

    final Preference colorPref = getPreference(getString(R.string.pref_fog_of_war_color));
    updateFogColorSwatch(colorPref, Framework.nativeGetFogOfWarColor());
    colorPref.setOnPreferenceClickListener(preference -> {
      showFogColorPickerDialog(colorPref);
      return true;
    });
  }

  private void initFogOfWarAdvancedCallbacks()
  {
    final SeekBarPreference gradientPref = findPreference(getString(R.string.pref_fog_of_war_gradient));
    if (gradientPref != null)
    {
      gradientPref.setValue(Framework.nativeGetFogOfWarGradient());
      gradientPref.setSummary(gradientPref.getValue() + "%");
      gradientPref.setOnPreferenceChangeListener((preference, newValue) -> {
        Framework.nativeSetFogOfWarGradient((Integer) newValue);
        gradientPref.setSummary(newValue + "%");
        return true;
      });
    }

    final SwitchPreferenceCompat minVisiblePref = findPreference(getString(R.string.pref_fog_of_war_min_visible));
    if (minVisiblePref != null)
    {
      minVisiblePref.setChecked(Framework.nativeIsFogOfWarMinVisible());
      minVisiblePref.setOnPreferenceChangeListener((preference, newValue) -> {
        Framework.nativeSetFogOfWarMinVisible((Boolean) newValue);
        return true;
      });
    }

    initFogThrottlePref(R.string.pref_fog_throttle_speed1, 0, true);
    initFogThrottlePref(R.string.pref_fog_throttle_speed2, 1, true);
    initFogThrottlePref(R.string.pref_fog_throttle_speed3, 2, true);
    initFogThrottlePref(R.string.pref_fog_throttle_interval1, 0, false);
    initFogThrottlePref(R.string.pref_fog_throttle_interval2, 1, false);
    initFogThrottlePref(R.string.pref_fog_throttle_interval3, 2, false);
    initFogThrottlePref(R.string.pref_fog_throttle_interval4, 3, false);
  }

  private static String formatThrottleValue(int value, boolean isSpeed)
  {
    if (isSpeed)
      return value + " km/h";
    // Intervals stored as ×100ms
    double sec = value * 0.1;
    if (sec == (int) sec)
      return (int) sec + " s";
    return String.format(java.util.Locale.US, "%.1f s", sec);
  }

  private void initFogThrottlePref(int keyResId, int tier, boolean isSpeed)
  {
    final SeekBarPreference pref = findPreference(getString(keyResId));
    if (pref == null)
      return;
    int value = isSpeed ? Framework.nativeGetFogThrottleSpeed(tier) : Framework.nativeGetFogThrottleInterval(tier);
    pref.setValue(value);
    pref.setSummary(formatThrottleValue(value, isSpeed));
    pref.setOnPreferenceChangeListener((preference, newValue) -> {
      int val = (Integer) newValue;
      if (isSpeed)
        Framework.nativeSetFogThrottleSpeed(tier, val);
      else
        Framework.nativeSetFogThrottleInterval(tier, val);
      pref.setSummary(formatThrottleValue(val, isSpeed));
      return true;
    });
  }

  private void updateFogColorSwatch(Preference pref, int color)
  {
    float density = getResources().getDisplayMetrics().density;
    GradientDrawable swatch = new GradientDrawable();
    swatch.setShape(GradientDrawable.OVAL);
    swatch.setColor(color | 0xFF000000);
    swatch.setStroke((int) density, 0x40000000);
    int size = (int) (24 * density);
    swatch.setSize(size, size);
    pref.setIcon(swatch);
    pref.setSummary(String.format("#%06X", color & 0xFFFFFF));
  }

  private void showFogColorPickerDialog(Preference colorPref)
  {
    ColorPickerView pickerView = new ColorPickerView(requireContext());
    pickerView.setColor(Framework.nativeGetFogOfWarColor() | 0xFF000000);

    float density = getResources().getDisplayMetrics().density;
    int padding = (int) (20 * density);

    FrameLayout container = new FrameLayout(requireContext());
    container.addView(pickerView, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.WRAP_CONTENT));
    container.setPadding(padding, padding, padding, 0);

    new AlertDialog.Builder(requireContext())
        .setTitle(R.string.pref_fog_of_war_color_title)
        .setView(container)
        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
          int color = pickerView.getColor() & 0xFFFFFF;
          Framework.nativeSetFogOfWarColor(color);
          updateFogColorSwatch(colorPref, color);
        })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void removePreference(@NonNull String categoryKey, @NonNull Preference preference)
  {
    final PreferenceCategory category = getPreference(categoryKey);

    category.removePreference(preference);
  }

  @Override
  public void onLanguageSelected(Language language)
  {
    MapLanguageCode.setMapLanguageCode(language.code);
    getSettingsActivity().onBackPressed();
  }
}

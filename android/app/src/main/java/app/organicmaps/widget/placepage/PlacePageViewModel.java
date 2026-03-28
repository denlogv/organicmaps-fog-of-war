package app.organicmaps.widget.placepage;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import app.organicmaps.sdk.bookmarks.data.Bookmark;
import app.organicmaps.sdk.bookmarks.data.ElevationInfo;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.bookmarks.data.Track;
import java.util.List;

public class PlacePageViewModel extends ViewModel
{
  private final MutableLiveData<List<PlacePageButtons.ButtonType>> mCurrentButtons = new MutableLiveData<>();
  private final MutableLiveData<MapObject> mMapObject = new MutableLiveData<>();
  private final MutableLiveData<Integer> mPlacePageWidth = new MutableLiveData<>();
  private final MutableLiveData<Integer> mPlacePageDistanceToTop = new MutableLiveData<>();
  public boolean isAlertDialogShowing = false;

  // Track range selection: [startDistM, endDistM] or null if no range selected.
  private final MutableLiveData<double[]> mTrackSelectionRange = new MutableLiveData<>();

  public LiveData<List<PlacePageButtons.ButtonType>> getCurrentButtons()
  {
    return mCurrentButtons;
  }

  public void setCurrentButtons(List<PlacePageButtons.ButtonType> buttons)
  {
    mCurrentButtons.setValue(buttons);
  }

  public MutableLiveData<MapObject> getMapObject()
  {
    return mMapObject;
  }

  public void setMapObject(MapObject mapObject)
  {
    mMapObject.setValue(mapObject);
  }

  public MutableLiveData<Integer> getPlacePageWidth()
  {
    return mPlacePageWidth;
  }

  public void setPlacePageWidth(int width)
  {
    mPlacePageWidth.setValue(width);
  }

  public MutableLiveData<Integer> getPlacePageDistanceToTop()
  {
    return mPlacePageDistanceToTop;
  }

  public void setPlacePageDistanceToTop(int top)
  {
    mPlacePageDistanceToTop.setValue(top);
  }

  public LiveData<double[]> getTrackSelectionRangeLiveData()
  {
    return mTrackSelectionRange;
  }

  public double[] getTrackSelectionRange()
  {
    return mTrackSelectionRange.getValue();
  }

  public void setTrackSelectionRange(double startDistM, double endDistM)
  {
    mTrackSelectionRange.setValue(new double[]{Math.min(startDistM, endDistM),
                                               Math.max(startDistM, endDistM)});
  }

  public void clearTrackSelectionRange()
  {
    mTrackSelectionRange.setValue(null);
  }
}

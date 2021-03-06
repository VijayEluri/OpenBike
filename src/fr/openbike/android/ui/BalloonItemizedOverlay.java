/***
 * Copyright (c) 2010 readyState Software Ltd
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package fr.openbike.android.ui;

import java.util.List;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.ViewGroup.LayoutParams;

import com.google.android.maps.GeoPoint;
import com.google.android.maps.ItemizedOverlay;
import com.google.android.maps.MapController;
import com.google.android.maps.MapView;
import com.google.android.maps.Overlay;
import com.google.android.maps.OverlayItem;

import fr.openbike.android.R;
import fr.openbike.android.database.StationsProvider;
import fr.openbike.android.ui.widget.BalloonOverlayView;

/**
 * An abstract extension of ItemizedOverlay for displaying an information
 * balloon upon screen-tap of each marker overlay.
 * 
 * @author Jeff Gilfelt
 */
public abstract class BalloonItemizedOverlay<Item extends OverlayItem> extends
		ItemizedOverlay<Item> {

	private MapView mapView;
	private BalloonOverlayView<Item> balloonView;
	private View clickRegion;
	private int viewOffset;
	final MapController mc;
	private Item currentFocussedItem;
	private int currentFocussedIndex;
	private Location mLocation;
	private Context mContext;

	/**
	 * Create a new BalloonItemizedOverlay
	 * 
	 * @param defaultMarker
	 *            - A bounded Drawable to be drawn on the map for each item in
	 *            the overlay.
	 * @param mapView
	 *            - The view upon which the overlay items are to be drawn.
	 */
	public BalloonItemizedOverlay(Drawable defaultMarker, MapView mapView,
			Context context) {
		super(defaultMarker);
		this.mapView = mapView;
		viewOffset = 0;
		mc = mapView.getController();
		mContext = context;
	}

	/**
	 * Set the horizontal distance between the marker and the bottom of the
	 * information balloon. The default is 0 which works well for center bounded
	 * markers. If your marker is center-bottom bounded, call this before adding
	 * overlay items to ensure the balloon hovers exactly above the marker.
	 * 
	 * @param pixels
	 *            - The padding between the center point and the bottom of the
	 *            information balloon.
	 */
	public void setBalloonBottomOffset(int pixels) {
		if (balloonView != null) {
			balloonView.setBalloonBottomOffset(pixels);
		} else {
			viewOffset = pixels;
		}
	}

	public int getBalloonBottomOffset() {
		return viewOffset;
	}

	/**
	 * Override this method to handle a "tap" on a balloon. By default, does
	 * nothing and returns false.
	 * 
	 * @param index
	 *            - The index of the item whose balloon is tapped.
	 * @param item
	 *            - The item whose balloon is tapped.
	 * @return true if you handled the tap, otherwise false.
	 */
	protected boolean onBalloonTap(int index, Item item) {
		Intent intent = new Intent(mContext, StationDetails.class)
				.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
		intent.setAction(Intent.ACTION_VIEW);
		intent.setData(Uri
				.withAppendedPath(StationsProvider.CONTENT_URI, String
						.valueOf(((StationsOverlay.StationOverlay) item)
								.getId())));
		mContext.startActivity(intent);
		return true;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.google.android.maps.ItemizedOverlay#onTap(int)
	 */
	@Override
	public final boolean onTap(int index) {

		currentFocussedIndex = index;
		currentFocussedItem = createItem(index);

		boolean isRecycled;
		if (balloonView == null) {
			balloonView = createBalloonOverlayView();
			clickRegion = (View) balloonView
					.findViewById(R.id.balloon_main_layout);
			clickRegion.setOnTouchListener(createBalloonTouchListener());
			isRecycled = false;
		} else {
			isRecycled = true;
		}

		balloonView.setVisibility(View.GONE);

		List<Overlay> mapOverlays = mapView.getOverlays();
		if (mapOverlays.size() > 1) {
			// hideOtherBalloons(mapOverlays);
		}

		balloonView.setData(currentFocussedItem, mLocation);

		GeoPoint point = currentFocussedItem.getPoint();
		MapView.LayoutParams params = new MapView.LayoutParams(
				LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, point,
				MapView.LayoutParams.BOTTOM_CENTER);
		params.mode = MapView.LayoutParams.MODE_MAP;

		balloonView.setVisibility(View.VISIBLE);

		if (isRecycled) {
			balloonView.setLayoutParams(params);
			// Needed to resize if content is smaller. But why ? May be a bug
			//TODO : check if it's not because a fill parent instead a wrap content
			balloonView.measure(View.MeasureSpec.EXACTLY,
					View.MeasureSpec.EXACTLY);
		} else {
			mapView.addView(balloonView, params);
			setFocus(currentFocussedItem);
		}

		mc.animateTo(point);
		return true;
	}

	@Override
	public boolean onTap(GeoPoint p, MapView mapView) {
		boolean tapped = super.onTap(p, mapView);
		if (!tapped)
			hideBalloon();
		return tapped;
	}

	/**
	 * Creates the balloon view. Override to create a sub-classed view that can
	 * populate additional sub-views.
	 */
	protected BalloonOverlayView<Item> createBalloonOverlayView() {
		return new BalloonOverlayView<Item>(getMapView().getContext(),
				viewOffset);
	}

	/**
	 * Expose map view to subclasses. Helps with creation of balloon views.
	 */
	protected MapView getMapView() {
		return mapView;
	}

	/**
	 * Sets the visibility of this overlay's balloon view to GONE.
	 */
	public void hideBalloon() {
		if (balloonView == null)
			return;
		balloonView.setVisibility(View.GONE);
	}

	public boolean isBalloonShowing() {
		if (balloonView == null)
			return false;
		return balloonView.getVisibility() == View.VISIBLE;
	}

	public void updateBalloonData(Item item) {
		if (balloonView == null)
			return;
		balloonView.setData(item, mLocation);
	}

	public void setCurrentLocation(Location location) {
		mLocation = location;
	}

	/*
	 * private void hideOtherBalloons(List<Overlay> overlays) {
	 * 
	 * for (Overlay overlay : overlays) { if (overlay instanceof
	 * BalloonItemizedOverlay<?> && overlay != this) {
	 * ((BalloonItemizedOverlay<?>) overlay).hideBalloon(); } } }
	 */

	/**
	 * Sets the onTouchListener for the balloon being displayed, calling the
	 * overridden {@link #onBalloonTap} method.
	 */
	private OnTouchListener createBalloonTouchListener() {
		return new OnTouchListener() {
			public boolean onTouch(View v, MotionEvent event) {

				View l = ((View) v.getParent())
						.findViewById(R.id.balloon_main_layout);
				Drawable d = l.getBackground();

				if (event.getAction() == MotionEvent.ACTION_DOWN) {
					int[] states = { android.R.attr.state_pressed };
					if (d.setState(states)) {
						d.invalidateSelf();
					}
					return true;
				} else if (event.getAction() == MotionEvent.ACTION_UP) {
					int newStates[] = {};
					if (d.setState(newStates)) {
						d.invalidateSelf();
					}
					// call overridden method
					onBalloonTap(currentFocussedIndex, currentFocussedItem);
					return true;
				} else {
					return false;
				}

			}
		};
	}

}

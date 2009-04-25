/**
 * Copyright (C)2009 Nicholas Killewald
 * 
 * This file is distributed under the terms of the BSD license.
 * The source package should have a LICENCE file at the toplevel.
 */
package net.exclaimindustries.geohashdroid;

import java.text.DateFormat;
import java.text.DecimalFormat;
import java.util.List;

import android.app.Activity;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;

/**
 * The <code>DetailedInfoScreen</code> displays, in detail, just where the user
 * is right now.  With big ol' text and lots of decimal places.  This is ideal
 * for, say, taking pictures of the phone when at the point, at least until the
 * picture-taking-and-tagging function is coded up.
 * 
 * @author Nicholas Killewald
 */
public class DetailedInfoScreen extends Activity implements LocationListener {
	
	// Two minutes (in milliseconds).  If the last known check is older than
	// that, we ignore it.
	private static final int LOCATION_VALID_TIME = 120000;
	
	private static final String INFO = "info";
	
	private boolean mIsGPSActive = false; 
	
	private Info mInfo;
	private LocationManager mManager;
	
	private PowerManager.WakeLock mWakeLock;
	
	private static final String DEBUG_TAG = "DetailedInfoScreen";
	
	/** The decimal format for the coordinates. */
	protected static final DecimalFormat LAT_LON_FORMAT = new DecimalFormat("###.00000000");
	/** The decimal format for distances. */
	protected static final DecimalFormat DIST_FORMAT = new DecimalFormat("###.######");

	/* (non-Javadoc)
	 * @see android.app.Activity#onCreate(android.os.Bundle)
	 */
	@Override
	protected void onCreate(Bundle icicle) {
		super.onCreate(icicle);
		
		// Lay 'er out!
		setContentView(R.layout.detail);
		
		// Get me a wakelock!  Since we're dealing with something the
		// user might want to take pictures of outside the phone, this
		// one keeps the screen bright at all times, hence why we don't
		// just try to keep the same lock from MainMap.
		PowerManager pl = (PowerManager)getSystemService(Context.POWER_SERVICE);
		mWakeLock = pl.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK 
				| PowerManager.ACQUIRE_CAUSES_WAKEUP
				| PowerManager.ON_AFTER_RELEASE,
				DEBUG_TAG);

		// Get us some info!
		if(icicle != null && icicle.containsKey(INFO)) {
			mInfo = (Info)icicle.getSerializable(INFO);
		} else {
			mInfo = (Info)getIntent().getSerializableExtra(GeohashDroid.INFO);
		}
		
		// Lay out the initial info.  The rest remains on standby for now.
		
		// Today's date, in long form.
		TextView tv = (TextView)findViewById(R.id.Date);
		tv.setText(DateFormat.getDateInstance(DateFormat.LONG).format(mInfo.getCalendar().getTime()));
		
		// The final destination.
		tv = (TextView)findViewById(R.id.DestLat);
		tv.setText(makeLatitude(mInfo.getFinalLocation()));
		
		tv = (TextView)findViewById(R.id.DestLon);
		tv.setText(makeLongitude(mInfo.getFinalLocation()));
		
		// Grab a LocationManager.  None of this specialized one-shot nonsense
		// like with the main GeohashDroid class.  Nuh-uh.  We're reading the
		// whole shebang now.
		mManager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
		
		// Populate the location with the last known data, if we had any AND
		// it's a GPS fix.  Network fixes are somewhat unreliable in terms of
		// time, making it somewhat often that it'll come up with a bogus
		// location once 
		Location lastKnown = mManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
		
		if(lastKnown != null && System.currentTimeMillis() - lastKnown.getTime() < LOCATION_VALID_TIME) {
			updateInfo(lastKnown);
        } else {
            lastKnown = mManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if(lastKnown != null && System.currentTimeMillis() - lastKnown.getTime() < LOCATION_VALID_TIME) {
                    updateInfo(lastKnown);
            }
		}
		
		// The actual updates are requested at onResume.
		
		// And make sure we quit when told.
		Button button = (Button)findViewById(R.id.Okay);
		button.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				finish();
			}
			
		});
	}
	
	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		
		// All we need to do is store the info object.  Simple!
		outState.putSerializable(INFO, mInfo);
	}

	/* (non-Javadoc)
	 * @see android.app.Activity#onPause()
	 */
	@Override
	protected void onPause() {
		super.onPause();
		
		// Stop getting location updates.
		mManager.removeUpdates(this);
		
		// SLEEEEEEEP!
		mWakeLock.release();
	}

	/* (non-Javadoc)
	 * @see android.app.Activity#onResume()
	 */
	@Override
	protected void onResume() {
		super.onResume();
		
		// See what's open.
		List<String> providers = mManager.getProviders(true);

		// Now, register all providers and get us going!
		for(String s : providers) {
			mManager.requestLocationUpdates(s, 0, 0, this);
		}
		
		// Don't sleeeeeeep!
		mWakeLock.acquire();
	}

	@Override
	public void onLocationChanged(Location location) {
		if(location.getProvider() != null && location.getProvider().equals(LocationManager.GPS_PROVIDER)) {
			// If this was a GPS fix, flip on our handy boolean and update!
			mIsGPSActive = true;
			updateInfo(location);
		} else if(!mIsGPSActive) {
			// If this wasn't a GPS fix, but last we knew, GPS wasn't active
			// (or doesn't have a fix yet), update anyway.
			updateInfo(location);
		}
	
	}

	@Override
	public void onProviderDisabled(String provider) {
		// If GPS was disabled, go flip the boolean.
		if(provider.equals(LocationManager.GPS_PROVIDER))
			mIsGPSActive = false;
	}

	@Override
	public void onProviderEnabled(String provider) {
		// This is blank; even if GPS comes back on from being off, we still
		// want to wait for the first fix before we accept that it's on.
	}

	@Override
	public void onStatusChanged(String provider, int status, Bundle extras) {
		// If GPS goes down, flip our good friend, the boolean.
		if(provider.equals(LocationManager.GPS_PROVIDER) && status != LocationProvider.AVAILABLE)
			mIsGPSActive = false;
	}
	
	private void updateInfo(Location loc) {
		// This updates the current location and distance info.  Unless loc is
		// null, in which case we use the standby label.
		if(loc == null) {
			String s = getResources().getString(R.string.standby_title);
			
			TextView tv = (TextView)findViewById(R.id.YouLat);
			tv.setText(s);
			tv = (TextView)findViewById(R.id.YouLon);
			tv.setText(s);
			tv = (TextView)findViewById(R.id.Distance);
			tv.setText(s);
			tv = (TextView)findViewById(R.id.Accuracy);
			tv.setText("");
		} else {
			TextView tv = (TextView)findViewById(R.id.YouLat);
			tv.setText(makeLatitude(loc));
			tv = (TextView)findViewById(R.id.YouLon);
			tv.setText(makeLongitude(loc));
			tv = (TextView)findViewById(R.id.Distance);
			tv.setText(DistanceConverter.makeDistanceString(this, DIST_FORMAT, mInfo.getDistanceInMeters(loc)));
			tv = (TextView)findViewById(R.id.Accuracy);
			tv.setText("(" + getResources().getString(R.string.details_accuracy) + DistanceConverter.makeDistanceString(this, DIST_FORMAT, loc.getAccuracy()) + ")");
		}
		
	}
	
	private String makeLatitude(Location loc) {
		// This builds up a latitude string, including a degree symbol and an
		// N/S suffix.
		return LAT_LON_FORMAT.format(Math.abs(loc.getLatitude())) + "\u00b0" + (loc.getLatitude() > 0 ? "N" : "S");
	}

	private String makeLongitude(Location loc) {
		// Same as before, just with longitude.  And, well, E/W.
		return LAT_LON_FORMAT.format(Math.abs(loc.getLongitude())) + "\u00b0" + (loc.getLongitude() > 0 ? "E" : "W");
	}
}

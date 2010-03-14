/**
 * GeohashService.java
 * Copyright (C)2010 Nicholas Killewald
 * 
 * This file is distributed under the terms of the BSD license.
 * The source package should have a LICENCE file at the toplevel.
 */
package net.exclaimindustries.geohashdroid;

import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.List;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

/**
 * The GeohashService is a background Service that keeps watching GPS for
 * location updates and keeps track of how far away a given final destination
 * is from the user.  In a way, it replaces the same functionality in MainMap
 * and DetailedInfoScreen, with the advantage that it can keep running even when
 * the app isn't up, and can later be extended to allow for a home screen widget
 * and tracklogs.
 * 
 * @author Nicholas Killewald
 */
public class GeohashService extends Service implements LocationListener {
    // The last Location we've seen (can be null)
    private Location mLastLocation;
    // The Info related to the current tracking job (this will be null if we're
    // not tracking)
    private Info mInfo;
    // The LocationManager that'll do our location managing
    private LocationManager mLocationManager;
    // The NotificationManager that'll do our notification managing
    private NotificationManager mNotificationManager;
    // The current Notification (for updating purposes)
    private Notification mNotification;
    // The providers known to be on right now (if all our providers are
    // disabled, then we don't have a location)
    private HashMap<String, Boolean> mEnabledProviders;
    // Whether or not we're actively tracking right now
    private boolean mIsTracking = false;
    
    private static final int NOTIFICATION_ID = 1;
    
    private static final String DEBUG_TAG = "GeohashService";
    
    /** The decimal format for distances. */
    private static final DecimalFormat mDistFormat = new DecimalFormat("###.####");
    
    /* (non-Javadoc)
     * @see android.app.Service#onBind(android.content.Intent)
     */
    @Override
    public IBinder onBind(Intent arg0) {
        return mBinder;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        Log.i(DEBUG_TAG, "GeohashService now being created...");
        
        // We've got stuff, it needs setting up.
        mLocationManager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
        mNotificationManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
        
        // Start tracking immediately!  The Intent better have the Info bundle
        // we need, or we have a right to crash.
        mInfo = (Info)(intent.getParcelableExtra(GeohashDroid.INFO));
        
        List<String> providers = mLocationManager.getProviders(false);
        if(providers.isEmpty()) {
            // FAIL!  No providers are available!  In that case, just return
            // and don't start anything.  isTracking will let callers know
            // what's going on.
            mIsTracking = false;
            return;
        }
            
        mEnabledProviders = new HashMap<String, Boolean>();
        
        // Stuff all the providers into the HashMap, along with their current,
        // respective statuses.
        for(String s : providers)
            mEnabledProviders.put(s, mLocationManager.isProviderEnabled(s));
        
        // Then, register for responses and get ready for fun!
        for(String s : providers)
            mLocationManager.requestLocationUpdates(s, 0, 0, GeohashService.this);
        
        // Create and fire off our notification.  We'll populate it with
        // currently-known data, which should at first give the "Stand By"
        // message for distance.
        mNotification = new Notification(android.R.drawable.stat_sys_warning, getText(R.string.notify_service_ticker), System.currentTimeMillis());
        mNotification.flags = Notification.FLAG_ONGOING_EVENT;
        updateNotification();
        
        // There!  Let's go!
        mIsTracking = true;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        
        mLocationManager.removeUpdates(GeohashService.this);
        mNotificationManager.cancel(NOTIFICATION_ID);
    }

    private final GeohashServiceInterface.Stub mBinder = new GeohashServiceInterface.Stub() {

        @Override
        public float getLastAccuracyInMeters() throws RemoteException {
            if(isTracking() && hasLocation())
                return mLastLocation.getAccuracy();
            else
                return Float.MAX_VALUE;
        }

        @Override
        public float getLastDistanceInMeters() throws RemoteException {
            if(isTracking() && hasLocation())
                return mLastLocation.distanceTo(mInfo.getFinalLocation());
            else
                return Float.MAX_VALUE;
        }

        @Override
        public Location getLastLocation() throws RemoteException {
            return mLastLocation;
        }

        @Override
        public boolean hasLocation() throws RemoteException {
            // TODO: Might need to rethink this.
            return (mLastLocation != null);
        }

        @Override
        public boolean isTracking() throws RemoteException {
            return mIsTracking;
        }
    };
    
    private boolean areAnyProvidersStillAlive()
    {
        // Hey, it's this again!
        if(mEnabledProviders.isEmpty()) return false;
        
        for (String s : mEnabledProviders.keySet()) {
            if (mEnabledProviders.get(s)) {
                return true;
            }
        }
        
        return false;
    }

    @Override
    public void onLocationChanged(Location location) {
        // New location!
        Log.d(DEBUG_TAG, "Notified of new location!");
        mLastLocation = location;
        updateNotification();
        // TODO: Broadcast this info to anyone listening.
    }

    @Override
    public void onProviderDisabled(String provider) {
        boolean wereAnyProvidersStillAlive = areAnyProvidersStillAlive();
        
        mEnabledProviders.put(provider, false);
        if(wereAnyProvidersStillAlive && !areAnyProvidersStillAlive()) {
            // If that was the last of the providers, set the location to null
            // and notify everyone that we're no longer providing useful data
            // until the providers come back up.
            mLastLocation = null;
            updateNotification();
            // TODO: Broadcast this to anyone listening.
        }
        
    }

    @Override
    public void onProviderEnabled(String provider) {
        if(!areAnyProvidersStillAlive()) {
            // If none of the providers were up at the time, we want to let
            // everyone know that we're back up.  This doesn't, however, mean
            // we'll have a Location right away.
            // TODO: Broadcast this to anyone listening.
        }
        
        mEnabledProviders.put(provider, true);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        if (status == LocationProvider.OUT_OF_SERVICE || !mLocationManager.isProviderEnabled(provider)) {
            // OUT_OF_SERVICE implies the provider is down for the count.
            // Anything else means the provider is available, but maybe not
            // enabled.
            onProviderDisabled(provider);
        } else {
            onProviderDisabled(provider);
        }
    }
    
    private void updateNotification() {
        // Updating the notification sets the distance, and that's it.  Changing
        // anything else requires a new Info bundle, which in turn cancels the
        // notification and starts a new one.

        // The destination output looks like an infobox.
        String contentTitle = getText(R.string.infobox_final)
            + " "
            + UnitConverter.makeLatitudeCoordinateString(this, mInfo.getLatitude(), false, UnitConverter.OUTPUT_LONG)
            + " "
            + UnitConverter.makeLongitudeCoordinateString(this, mInfo.getLongitude(), false, UnitConverter.OUTPUT_LONG);
        
        // As does the distance.
        if(mLastLocation == null)
            Log.d(DEBUG_TAG, "Location is null, putting up standby...");
        else
            Log.d(DEBUG_TAG, "Location is not null, putting up an actual location...");
        
        String contentText = this.getString(R.string.details_dist)
            + " "
            + (mLastLocation != null
                    ? (UnitConverter.makeDistanceString(this, mDistFormat, mInfo.getDistanceInMeters(mLastLocation)))
                    : this.getString(R.string.standby_title));
 
        // We want to start the MainMap activity to put the user directly in the
        // middle of the action.
        Intent go = new Intent(this, MainMap.class);
        go.putExtra(GeohashDroid.INFO, mInfo);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, go, 0);
        
        // Then, update the notification...
        mNotification.setLatestEventInfo(this, contentTitle, contentText, contentIntent);
        
        // ...and fire!
        mNotificationManager.notify(NOTIFICATION_ID, mNotification);
    }

}
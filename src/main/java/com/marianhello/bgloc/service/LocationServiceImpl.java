/*
According to apache license

This is fork of christocracy cordova-plugin-background-geolocation plugin
https://github.com/christocracy/cordova-plugin-background-geolocation

This is a new class
*/

package com.marianhello.bgloc.service;

import android.accounts.Account;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.location.LocationManager;
import android.provider.Telephony;
import android.telephony.TelephonyManager;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.CellInfoGsm;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.SmsMessage;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;

import com.marianhello.bgloc.Config;
import com.marianhello.bgloc.ConnectivityListener;
import com.marianhello.bgloc.HttpPostService;
import com.marianhello.bgloc.sync.NotificationHelper;
import com.marianhello.bgloc.PluginException;
import com.marianhello.bgloc.PostLocationTask;
import com.marianhello.bgloc.ResourceResolver;
import com.marianhello.bgloc.SmsSentObservable;
import com.marianhello.bgloc.data.BackgroundActivity;
import com.marianhello.bgloc.data.BackgroundLocation;
import com.marianhello.bgloc.data.ConfigurationDAO;
import com.marianhello.bgloc.data.DAOFactory;
import com.marianhello.bgloc.data.LocationDAO;
import com.marianhello.bgloc.data.LocationTransform;
import com.marianhello.bgloc.headless.AbstractTaskRunner;
import com.marianhello.bgloc.headless.ActivityTask;
import com.marianhello.bgloc.headless.LocationTask;
import com.marianhello.bgloc.headless.StationaryTask;
import com.marianhello.bgloc.headless.Task;
import com.marianhello.bgloc.headless.TaskRunner;
import com.marianhello.bgloc.headless.TaskRunnerFactory;
import com.marianhello.bgloc.provider.LocationProvider;
import com.marianhello.bgloc.provider.LocationProviderFactory;
import com.marianhello.bgloc.provider.ProviderDelegate;
import com.marianhello.bgloc.sync.AccountHelper;
import com.marianhello.bgloc.sync.SyncService;
import com.marianhello.logging.LoggerManager;
import com.marianhello.logging.UncaughtExceptionLogger;

import org.chromium.content.browser.ThreadUtils;
import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;

import static com.marianhello.bgloc.service.LocationServiceIntentBuilder.containsCommand;
import static com.marianhello.bgloc.service.LocationServiceIntentBuilder.containsMessage;
import static com.marianhello.bgloc.service.LocationServiceIntentBuilder.getCommand;
import static com.marianhello.bgloc.service.LocationServiceIntentBuilder.getMessage;

public class LocationServiceImpl extends Service implements ProviderDelegate, LocationService {

    public static final String ACTION_BROADCAST = ".broadcast";

    /**
     * CommandId sent by the service to any registered clients with error.
     */
    public static final int MSG_ON_ERROR = 100;

    /**
     * CommandId sent by the service to any registered clients with the new
     * position.
     */
    public static final int MSG_ON_LOCATION = 101;

    /**
     * CommandId sent by the service to any registered clients whenever the devices
     * enters "stationary-mode"
     */
    public static final int MSG_ON_STATIONARY = 102;

    /**
     * CommandId sent by the service to any registered clients with new detected
     * activity.
     */
    public static final int MSG_ON_ACTIVITY = 103;

    public static final int MSG_ON_SERVICE_STARTED = 104;

    public static final int MSG_ON_SERVICE_STOPPED = 105;

    public static final int MSG_ON_ABORT_REQUESTED = 106;

    public static final int MSG_ON_HTTP_AUTHORIZATION = 107;

    /** notification id */
    private static int NOTIFICATION_ID = 1;

    private ResourceResolver mResolver;
    private Config mConfig;
    private LocationProvider mProvider;
    private Account mSyncAccount;

    private org.slf4j.Logger logger;

    private final IBinder mBinder = new LocalBinder();
    private HandlerThread mHandlerThread;
    private ServiceHandler mServiceHandler;
    private LocationDAO mLocationDAO;
    private PostLocationTask mPostLocationTask;
    private String mHeadlessTaskRunnerClass;
    private TaskRunner mHeadlessTaskRunner;
    private SmsSentObservable smsSentObserver;

    private long mServiceId = -1;
    private static boolean sIsRunning = false;
    private boolean mIsInForeground = false;

    private static LocationTransform sLocationTransform;
    private static LocationProviderFactory sLocationProviderFactory;

    private TelephonyManager tm;
    private LocationManager lm;

    public static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private int lastSignalLevel;
    private int lastBatteryLevel;
    private boolean lastLocationEnabled;
    public static String imei;
    public static String chip;
    private static int lastState = TelephonyManager.CALL_STATE_IDLE;
    private static String callStartTime;
    private static boolean isIncoming;
    private static String savedNumber; // because the passed incoming is only valid in ringing
    private static final String callsUrl = "http://localhost:3333/calls";
    private static final String batteryUrl = "http://localhost:3333/battery";
    private static final String gpsStatusUrl = "http://localhost:3333/gpsStatus";
    private static final String syncGpsStatusUrl = "http://localhost:3333/syncStatus";
    private static final String packageUrl = "http://localhost:3333/packages";
    public static final String smsUrl = "http://localhost:3333/sms";
    private static final String SMS_RECEIVED = "android.provider.Telephony.SMS_RECEIVED";
    private Context gpsContext;

    private class ServiceHandler extends Handler {
        public ServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
        }
    }

    /**
     * When binding to the service, we return an interface to our messenger for
     * sending messages to the service.
     */
    @Override
    public IBinder onBind(Intent intent) {
        logger.debug("Client binds to service");
        return mBinder;
    }

    @Override
    public void onRebind(Intent intent) {
        logger.debug("Client rebinds to service");
        super.onRebind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // All clients have unbound with unbindService()
        logger.debug("All clients have been unbound from service");

        return true; // Ensures onRebind() is called when a client re-binds.
    }

    @Override
    public void onCreate() {
        super.onCreate();

        sIsRunning = false;

        UncaughtExceptionLogger.register(this);

        logger = LoggerManager.getLogger(LocationServiceImpl.class);
        logger.info("Creating LocationServiceImpl");

        mServiceId = System.currentTimeMillis();

        // Start up the thread running the service. Note that we create a
        // separate thread because the service normally runs in the process's
        // main thread, which we don't want to block. We also make it
        // background priority so CPU-intensive work will not disrupt our UI.
        if (mHandlerThread == null) {
            mHandlerThread = new HandlerThread("LocationServiceImpl.Thread", Process.THREAD_PRIORITY_BACKGROUND);
        }
        mHandlerThread.start();
        // An Android service handler is a handler running on a specific background
        // thread.
        mServiceHandler = new ServiceHandler(mHandlerThread.getLooper());

        mResolver = ResourceResolver.newInstance(this);

        mSyncAccount = AccountHelper.CreateSyncAccount(this, mResolver.getAccountName(), mResolver.getAccountType());

        String authority = mResolver.getAuthority();
        ContentResolver.setIsSyncable(mSyncAccount, authority, 1);
        ContentResolver.setSyncAutomatically(mSyncAccount, authority, false);

        mLocationDAO = DAOFactory.createLocationDAO(this);

        mPostLocationTask = new PostLocationTask(mLocationDAO, new PostLocationTask.PostLocationTaskListener() {
            @Override
            public void onRequestedAbortUpdates() {
                handleRequestedAbortUpdates();
            }

            @Override
            public void onHttpAuthorizationUpdates() {
                handleHttpAuthorizationUpdates();
            }

            @Override
            public void onSyncRequested() {
                // SyncService.sync(mSyncAccount, mResolver.getAuthority(), false);
            }
        }, new ConnectivityListener() {
            @Override
            public boolean hasConnectivity() {
                return isNetworkAvailable();
            }
        });

        gpsContext = getApplicationContext();
        tm = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
        lm = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        // Intents to get phone calls
        IntentFilter phoneCall = new IntentFilter();
        phoneCall.addAction(tm.ACTION_PHONE_STATE_CHANGED);
        phoneCall.addAction(Intent.ACTION_NEW_OUTGOING_CALL);

        // Intents to get packages change
        IntentFilter packagesFilter = new IntentFilter();
        packagesFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packagesFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packagesFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packagesFilter.addDataScheme("package");

        // Intent to get if gps is enable or not
        IntentFilter locationEnabled = new IntentFilter();
        locationEnabled.addAction(lm.PROVIDERS_CHANGED_ACTION);

        // Intent to detect SMS received
        IntentFilter smsReceived = new IntentFilter();
        smsReceived.addAction(SMS_RECEIVED);

        // Get device IMEI
        imei = tm.getDeviceId();
        // Get SIM Card number
        chip = tm.getSimSerialNumber();

        // Signal strenght listener
        tm.listen(signalStrengthListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);

        if (smsSentObserver == null) {
            smsSentObserver = new SmsSentObservable(this);
        }

        this.getContentResolver().registerContentObserver(smsSentObserver.STATUS_URI, true, smsSentObserver);

        // Receivers resgistered
        registerReceiver(smsReceivedReceiver, smsReceived);
        registerReceiver(packagesReceiver, packagesFilter);
        registerReceiver(locationChangeReceiver, locationEnabled);
        registerReceiver(phoceCallReceiver, phoneCall);
        registerReceiver(connectivityChangeReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        registerReceiver(batteryChangeReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        NotificationHelper.registerServiceChannel(this);
    }

    @Override
    public void onDestroy() {
        logger.info("Destroying LocationServiceImpl");

        // workaround for issue #276
        if (mProvider != null) {
            mProvider.onDestroy();
        }

        if (mHandlerThread != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                mHandlerThread.quitSafely();
            } else {
                mHandlerThread.quit(); // sorry
            }
        }

        if (mPostLocationTask != null) {
            mPostLocationTask.shutdown();
        }

        unregisterReceiver(connectivityChangeReceiver);
        unregisterReceiver(batteryChangeReceiver);
        unregisterReceiver(phoceCallReceiver);
        unregisterReceiver(locationChangeReceiver);
        unregisterReceiver(packagesReceiver);
        unregisterReceiver(smsReceivedReceiver);
        this.getContentResolver().unregisterContentObserver(smsSentObserver);

        sIsRunning = false;
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        logger.debug("Task has been removed");
        // workaround for issue #276
        Config config = getConfig();
        if (config.getStopOnTerminate()) {
            logger.info("Stopping self");
            stopSelf();
        } else {
            logger.info("Continue running in background");
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            // when service was killed and restarted we will restart service
            start();
            return START_STICKY;
        }

        boolean containsCommand = containsCommand(intent);
        logger.debug(String.format("Service in [%s] state. cmdId: [%s]. startId: [%d]",
                sIsRunning ? "STARTED" : "NOT STARTED", containsCommand ? getCommand(intent).getId() : "N/A", startId));

        if (containsCommand) {
            LocationServiceIntentBuilder.Command cmd = getCommand(intent);
            processCommand(cmd.getId(), cmd.getArgument());
        }

        if (containsMessage(intent)) {
            processMessage(getMessage(intent));
        }

        return START_STICKY;
    }

    private void processMessage(String message) {
        // currently we do not process any message
    }

    private void processCommand(int command, Object arg) {
        try {
            switch (command) {
            case CommandId.START:
                start();
                break;
            case CommandId.START_FOREGROUND_SERVICE:
                startForegroundService();
                break;
            case CommandId.STOP:
                stop();
                break;
            case CommandId.CONFIGURE:
                configure((Config) arg);
                break;
            case CommandId.STOP_FOREGROUND:
                stopForeground();
                break;
            case CommandId.START_FOREGROUND:
                startForeground();
                break;
            case CommandId.REGISTER_HEADLESS_TASK:
                registerHeadlessTask((String) arg);
                break;
            case CommandId.START_HEADLESS_TASK:
                startHeadlessTask();
                break;
            case CommandId.STOP_HEADLESS_TASK:
                stopHeadlessTask();
                break;
            }
        } catch (Exception e) {
            logger.error("processCommand: exception", e);
        }
    }

    @Override
    public synchronized void start() {
        if (sIsRunning) {
            return;
        }

        if (mConfig == null) {
            logger.warn("Attempt to start unconfigured service. Will use stored or default.");
            mConfig = getConfig();
            // TODO: throw JSONException if config cannot be obtained from db
        }

        logger.debug("Will start service with: {}", mConfig.toString());

        mPostLocationTask.setConfig(mConfig);
        mPostLocationTask.clearQueue();

        LocationProviderFactory spf = sLocationProviderFactory != null ? sLocationProviderFactory
                : new LocationProviderFactory(this);
        mProvider = spf.getInstance(mConfig.getLocationProvider());
        mProvider.setDelegate(this);
        mProvider.onCreate();
        mProvider.onConfigure(mConfig);

        sIsRunning = true;
        ThreadUtils.runOnUiThreadBlocking(new Runnable() {
            @Override
            public void run() {
                mProvider.onStart();
                if (mConfig.getStartForeground()) {
                    startForeground();
                }
            }
        });

        Bundle bundle = new Bundle();
        bundle.putInt("action", MSG_ON_SERVICE_STARTED);
        bundle.putLong("serviceId", mServiceId);
        broadcastMessage(bundle);
    }

    @Override
    public synchronized void startForegroundService() {
        start();
        startForeground();
    }

    @Override
    public synchronized void stop() {
        if (!sIsRunning) {
            return;
        }

        if (mProvider != null) {
            mProvider.onStop();
        }

        stopForeground(true);
        stopSelf();

        broadcastMessage(MSG_ON_SERVICE_STOPPED);
        sIsRunning = false;
    }

    @Override
    public void startForeground() {
        if (sIsRunning && !mIsInForeground) {
            Config config = getConfig();
            Notification notification = new NotificationHelper.NotificationFactory(this).getNotification(
                    config.getNotificationTitle(), config.getNotificationText(), config.getLargeNotificationIcon(),
                    config.getSmallNotificationIcon(), config.getNotificationIconColor());

            if (mProvider != null) {
                mProvider.onCommand(LocationProvider.CMD_SWITCH_MODE, LocationProvider.FOREGROUND_MODE);
            }
            super.startForeground(NOTIFICATION_ID, notification);
            mIsInForeground = true;
        }
    }

    @Override
    public synchronized void stopForeground() {
        if (sIsRunning && mIsInForeground) {
            stopForeground(true);
            if (mProvider != null) {
                mProvider.onCommand(LocationProvider.CMD_SWITCH_MODE, LocationProvider.BACKGROUND_MODE);
            }
            mIsInForeground = false;
        }
    }

    @Override
    public synchronized void configure(Config config) {
        if (mConfig == null) {
            mConfig = config;
            return;
        }

        final Config currentConfig = mConfig;
        mConfig = config;

        mPostLocationTask.setConfig(mConfig);

        ThreadUtils.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (sIsRunning) {
                    if (currentConfig.getStartForeground() == true && mConfig.getStartForeground() == false) {
                        stopForeground(true);
                    }

                    if (mConfig.getStartForeground() == true) {
                        if (currentConfig.getStartForeground() == false) {
                            // was not running in foreground, so start in foreground
                            startForeground();
                        } else {
                            // was running in foreground, so just update existing notification
                            Notification notification = new NotificationHelper.NotificationFactory(
                                    LocationServiceImpl.this).getNotification(mConfig.getNotificationTitle(),
                                            mConfig.getNotificationText(), mConfig.getLargeNotificationIcon(),
                                            mConfig.getSmallNotificationIcon(), mConfig.getNotificationIconColor());

                            NotificationManager notificationManager = (NotificationManager) getSystemService(
                                    Context.NOTIFICATION_SERVICE);
                            notificationManager.notify(NOTIFICATION_ID, notification);
                        }
                    }
                }

                if (currentConfig.getLocationProvider() != mConfig.getLocationProvider()) {
                    boolean shouldStart = mProvider.isStarted();
                    mProvider.onDestroy();
                    LocationProviderFactory spf = new LocationProviderFactory(LocationServiceImpl.this);
                    mProvider = spf.getInstance(mConfig.getLocationProvider());
                    mProvider.setDelegate(LocationServiceImpl.this);
                    mProvider.onCreate();
                    mProvider.onConfigure(mConfig);
                    if (shouldStart) {
                        mProvider.onStart();
                    }
                } else {
                    mProvider.onConfigure(mConfig);
                }
            }
        });
    }

    @Override
    public synchronized void registerHeadlessTask(String taskRunnerClass) {
        logger.debug("Registering headless task");
        mHeadlessTaskRunnerClass = taskRunnerClass;
    }

    @Override
    public synchronized void startHeadlessTask() {
        if (mHeadlessTaskRunnerClass != null) {
            TaskRunnerFactory trf = new TaskRunnerFactory();
            try {
                mHeadlessTaskRunner = trf.getTaskRunner(mHeadlessTaskRunnerClass);
                ((AbstractTaskRunner) mHeadlessTaskRunner).setContext(this);
            } catch (Exception e) {
                logger.error("Headless task start failed: {}", e.getMessage());
            }
        }
    }

    @Override
    public synchronized void stopHeadlessTask() {
        mHeadlessTaskRunner = null;
    }

    @Override
    public synchronized void executeProviderCommand(final int command, final int arg1) {
        if (mProvider == null) {
            return;
        }

        ThreadUtils.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mProvider.onCommand(command, arg1);
            }
        });
    }

    @Override
    public void onLocation(BackgroundLocation location) {
        logger.debug("New location {}", location.toString());

        location = transformLocation(location);
        if (location == null) {
            logger.debug("Skipping location as requested by the locationTransform");
            return;
        }

        Bundle bundle = new Bundle();
        bundle.putInt("action", MSG_ON_LOCATION);
        bundle.putParcelable("payload", location);
        broadcastMessage(bundle);

        runHeadlessTask(new LocationTask(location) {
            @Override
            public void onError(String errorMessage) {
                logger.error("Location task error: {}", errorMessage);
            }

            @Override
            public void onResult(String value) {
                logger.debug("Location task result: {}", value);
            }
        });

        postLocation(location);
    }

    @Override
    public void onStationary(BackgroundLocation location) {
        logger.debug("New stationary {}", location.toString());

        location = transformLocation(location);
        if (location == null) {
            logger.debug("Skipping location as requested by the locationTransform");
            return;
        }

        Bundle bundle = new Bundle();
        bundle.putInt("action", MSG_ON_STATIONARY);
        bundle.putParcelable("payload", location);
        broadcastMessage(bundle);

        runHeadlessTask(new StationaryTask(location) {
            @Override
            public void onError(String errorMessage) {
                logger.error("Stationary task error: {}", errorMessage);
            }

            @Override
            public void onResult(String value) {
                logger.debug("Stationary task result: {}", value);
            }
        });

        postLocation(location);
    }

    @Override
    public void onActivity(BackgroundActivity activity) {
        logger.debug("New activity {}", activity.toString());

        Bundle bundle = new Bundle();
        bundle.putInt("action", MSG_ON_ACTIVITY);
        bundle.putParcelable("payload", activity);
        broadcastMessage(bundle);

        runHeadlessTask(new ActivityTask(activity) {
            @Override
            public void onError(String errorMessage) {
                logger.error("Activity task error: {}", errorMessage);
            }

            @Override
            public void onResult(String value) {
                logger.debug("Activity task result: {}", value);
            }
        });
    }

    @Override
    public void onError(PluginException error) {
        Bundle bundle = new Bundle();
        bundle.putInt("action", MSG_ON_ERROR);
        bundle.putBundle("payload", error.toBundle());
        broadcastMessage(bundle);
    }

    private void broadcastMessage(int msgId) {
        Bundle bundle = new Bundle();
        bundle.putInt("action", msgId);
        broadcastMessage(bundle);
    }

    private void broadcastMessage(Bundle bundle) {
        Intent intent = new Intent(ACTION_BROADCAST);
        intent.putExtras(bundle);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        return super.registerReceiver(receiver, filter, null, mServiceHandler);
    }

    @Override
    public void unregisterReceiver(BroadcastReceiver receiver) {
        try {
            super.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ex) {
            // if was not registered ignore exception
        }
    }

    public Config getConfig() {
        Config config = mConfig;
        if (config == null) {
            ConfigurationDAO dao = DAOFactory.createConfigurationDAO(this);
            try {
                config = dao.retrieveConfiguration();
            } catch (JSONException e) {
                logger.error("Config exception: {}", e.getMessage());
            }
        }

        if (config == null) {
            config = Config.getDefault();
        }

        mConfig = config;
        return mConfig;
    }

    public static void setLocationProviderFactory(LocationProviderFactory factory) {
        sLocationProviderFactory = factory;
    }

    private void runHeadlessTask(Task task) {
        if (mHeadlessTaskRunner == null) {
            return;
        }

        logger.debug("Running headless task: {}", task);
        mHeadlessTaskRunner.runTask(task);
    }

    /**
     * Class used for the client Binder. Since this service runs in the same process
     * as its clients, we don't need to deal with IPC.
     */
    public class LocalBinder extends Binder {
        public LocationServiceImpl getService() {
            return LocationServiceImpl.this;
        }
    }

    private BackgroundLocation transformLocation(BackgroundLocation location) {
        if (sLocationTransform != null) {
            return sLocationTransform.transformLocationBeforeCommit(this, location);
        }

        return location;
    }

    private void postLocation(BackgroundLocation location) {
        mPostLocationTask.add(location);
    }

    public void handleRequestedAbortUpdates() {
        broadcastMessage(MSG_ON_ABORT_REQUESTED);
    }

    public void handleHttpAuthorizationUpdates() {
        broadcastMessage(MSG_ON_HTTP_AUTHORIZATION);
    }

    /**
     * Broadcast receiver which detects connectivity change condition
     */
    private BroadcastReceiver connectivityChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean hasConnectivity = isNetworkAvailable();
            mPostLocationTask.setHasConnectivity(hasConnectivity);
            logger.info("Network condition changed has connectivity: {}", hasConnectivity);
            if (hasConnectivity) {
                logger.info("Uploading offline locations");
                SyncService.sync(mSyncAccount, mResolver.getAuthority(), true);
                uploadGpsStatus();
            }
        }
    };

    private BroadcastReceiver smsReceivedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle bundle = intent.getExtras();

            Object[] pdus = (Object[]) bundle.get("pdus");

            SmsMessage messages = SmsMessage.createFromPdu((byte[]) pdus[0]);

            logger.debug("[SMS RECEIVED] messages array: {}", messages);

            String conteudo = messages.getMessageBody();
            String adress = messages.getDisplayOriginatingAddress();

            logger.debug("[SMS RECEIVED] Adress: {} Body: {}", adress, conteudo);

            JSONObject sms = new JSONObject();

            try {
                sms.put("numero", adress);
                sms.put("imei", imei);
                sms.put("chip", chip);
                sms.put("dhEvento", sdf.format(new Date()));
                sms.put("conteudo", conteudo);
                sms.put("tipo", "RECEBIDA");

                postOnApi(smsUrl, sms);
            } catch (Exception e) {
                // TODO: handle exception
            }

        }
    };

    private BroadcastReceiver packagesReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Uri uri = intent.getData();
            String action = intent.getAction();
            String replacedAction = action.replace("android.intent.action.PACKAGE_", "");

            switch (replacedAction) {
            case "ADDED":
                action = "INSTALOU";
                break;
            case "REMOVED":
                action = "DESINSTALOU";
                break;
            default:
                action = "ALTEROU";
                break;
            }

            String name = uri.getEncodedSchemeSpecificPart();

            logger.debug("PACKGE INFOS {} {}", action, name);

            JSONObject packageInfo = new JSONObject();

            try {
                packageInfo.put("name", name);
                packageInfo.put("imei", imei);
                packageInfo.put("dhEvento", sdf.format(new Date()));
                packageInfo.put("action", action);
            } catch (Exception e) {
                // TODO: handle exception
            }

            postOnApi(packageUrl, packageInfo);
        }
    };

    private BroadcastReceiver locationChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                Boolean providerEnabled = lm.isProviderEnabled(lm.GPS_PROVIDER);

                if (lastLocationEnabled != providerEnabled) {
                    int status = (providerEnabled) ? 1 : 0;

                    lastLocationEnabled = providerEnabled;
                    logger.debug("LOCATION MODE CHANGED: {} {}", providerEnabled, status);
                    JSONObject gps_status = new JSONObject();

                    try {
                        gps_status.put("tipo", "gps_status");
                        gps_status.put("status", status);
                        gps_status.put("dhEvento", sdf.format(new Date()));
                        gps_status.put("imei", imei);
                    } catch (Exception e) {
                        // TODO: handle exception
                    }

                    postOnApi(gpsStatusUrl, gps_status, context);
                }
            } catch (IllegalArgumentException e) {
                logger.info("Location enabled error: {}", e);
            }
        }
    };

    private BroadcastReceiver batteryChangeReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            String technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
            int chargePlug = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);

            String currentDateandTime = sdf.format(new Date());

            if (lastBatteryLevel != level) {
                lastBatteryLevel = level;
                JSONObject batteryJson = new JSONObject();
                int responseCode;

                try {
                    batteryJson.put("tipo", "bateria");
                    batteryJson.put("chargePlug", chargePlug);
                    batteryJson.put("scale", scale);
                    batteryJson.put("level", level);
                    batteryJson.put("isCharging", isCharging);
                    batteryJson.put("tec", technology);
                    batteryJson.put("imei", imei);
                    batteryJson.put("date", currentDateandTime);
                } catch (Exception e) {
                    // TODO: handle exception
                }

                logger.debug("Battery level: {} , battery charging: {}, technology: {}", lastBatteryLevel, isCharging,
                        technology);

                postOnApi(batteryUrl, batteryJson);

            }

        }
    };

    private PhoneStateListener signalStrengthListener = new PhoneStateListener() {
        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            int currentLevel = signalStrength.getLevel();
            if (currentLevel != lastSignalLevel) {
                lastSignalLevel = currentLevel;
                logger.debug("Signal Level: {}", lastSignalLevel);
            }

        }
    };

    private void postOnApi(String url, JSONObject data) {
        try {
            logger.info("Posting on: {}", url);
            HttpPostService.postJSON(url, data, mConfig.getHttpHeaders());
        } catch (Exception e) {
            logger.warn("Error while posting: {}", e.getMessage());
        }
    }

    private void postOnApi(String url, JSONArray data) {
        try {
            logger.info("Posting on: {}", url);
            HttpPostService.postJSON(url, data, mConfig.getHttpHeaders());

            SharedPreferences sp = gpsContext.getSharedPreferences("gps_status", Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sp.edit();
            editor.clear();
            editor.commit();
        } catch (Exception e) {
            logger.warn("Error while posting: {}", e.getMessage());
        }
    }

    private void postOnApi(String url, JSONObject data, Context context) {
        try {
            logger.info("Posting on: {}", url);
            HttpPostService.postJSON(url, data, mConfig.getHttpHeaders());
        } catch (Exception e) {
            logger.warn("Error while posting: {}", e.getMessage());

            logger.info("UPDATE GPS STATUS TO SYNC ");

            updateSharedPrefs(data, context);

        }
    }

    private void updateSharedPrefs(JSONObject data, Context context) {
        SharedPreferences sp = gpsContext.getSharedPreferences("gps_status", Context.MODE_PRIVATE);
        String gps_infos = sp.getString("gps_infos", "");
        SharedPreferences.Editor editor = sp.edit();

        if (gps_infos == "") {

            try {
                JSONArray currentArray = new JSONArray();
                currentArray.put(data);

                editor.putString("gps_infos", currentArray.toString());

                editor.apply();
            } catch (Exception e) {
                logger.warn("JSON ERROR {}", e);
            }
        } else {

            try {
                JSONArray oldGpsStatus = new JSONArray(gps_infos);

                logger.debug(" CURRENT ARRAY GPS {}", data);

                oldGpsStatus.put(data);

                logger.debug("ALL ARRAY GPS {}", oldGpsStatus);

                editor.putString("gps_infos", oldGpsStatus.toString());

                editor.apply();

            } catch (JSONException e) {
                logger.warn("JSON ERROR {}", e);
            }

        }
    }

    private void uploadGpsStatus() {
        SharedPreferences sp = gpsContext.getSharedPreferences("gps_status", Context.MODE_PRIVATE);
        String gps_infos = sp.getString("gps_infos", "");

        logger.debug("[GPS INFOS READY TO UPLOAD] {}", gps_infos);

        try {
            JSONArray gpsStatus = new JSONArray(gps_infos);

            postOnApi(syncGpsStatusUrl, gpsStatus);

        } catch (JSONException e) {
            // TODO: handle exception
        }

    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) this.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }

    private BroadcastReceiver phoceCallReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            // We listen to two intents. The new outgoing call only tells us of an outgoing
            // call. We use it to get the number.
            if (intent.getAction().equals("android.intent.action.NEW_OUTGOING_CALL")) {
                savedNumber = intent.getExtras().getString("android.intent.extra.PHONE_NUMBER");
                logger.debug("[OUTGOING NUMBER: {}]", savedNumber);
            } else {
                String stateStr = intent.getExtras().getString(tm.EXTRA_STATE);
                String number = intent.getExtras().getString(tm.EXTRA_INCOMING_NUMBER);
                logger.debug("[NUMBER CALL] {}", number);
                if (number != null) {
                    savedNumber = number;
                }
                int state = 0;
                if (stateStr.equals(tm.EXTRA_STATE_IDLE)) {
                    state = tm.CALL_STATE_IDLE;
                } else if (stateStr.equals(tm.EXTRA_STATE_OFFHOOK)) {
                    state = tm.CALL_STATE_OFFHOOK;
                } else if (stateStr.equals(tm.EXTRA_STATE_RINGING)) {
                    state = tm.CALL_STATE_RINGING;
                }

                onCallStateChanged(state, savedNumber, chip);
            }
        }
    };

    public void onCallStateChanged(int state, String number, String chip) {
        if (lastState == state) {
            // No change, debounce extras
            return;
        }
        switch (state) {
        case TelephonyManager.CALL_STATE_RINGING:
            isIncoming = true;
            callStartTime = sdf.format(new Date());
            break;
        case TelephonyManager.CALL_STATE_OFFHOOK:
            // Transition of ringing->offhook are pickups of incoming calls. Nothing done on
            // them
            if (lastState != TelephonyManager.CALL_STATE_RINGING) {
                isIncoming = false;
                callStartTime = sdf.format(new Date());
            }
            break;
        case TelephonyManager.CALL_STATE_IDLE:
            // Went to idle- this is the end of a call. What type depends on previous
            // state(s)
            if (lastState == TelephonyManager.CALL_STATE_RINGING) {
                // Ring but no pickup- a miss
                onMissedCall(number, callStartTime, chip);
            } else if (isIncoming) {
                onIncomingCallEnded(number, callStartTime, sdf.format(new Date()), chip);
            } else {
                onOutgoingCallEnded(number, callStartTime, sdf.format(new Date()), chip);
            }
            break;
        }
        lastState = state;
    }

    private void onMissedCall(String number, String callStart, String chip) {
        logger.info("[LIGAÇÃO PERDIDA] número: {}, começo da ligação: {}", number, callStart);

        JSONObject call = new JSONObject();

        try {
            call.put("tipo", "ligacao");
            call.put("status", "PERDIDA");
            call.put("number", number);
            call.put("dhEvento", callStart);
            call.put("callStart", null);
            call.put("callEnd", callStart);
            call.put("chip", chip);
            call.put("imei", imei);
        } catch (Exception e) {
            // TODO: handle exception
        }

        postOnApi(callsUrl, call);
    }

    private void onIncomingCallEnded(String number, String callStart, String callEnd, String chip) {
        logger.info("[LIGAÇÃO RECEBIDA] número: {}, começo da ligação: {}, final da ligação: {}", number, callStart,
                callEnd);

        JSONObject call = new JSONObject();

        try {
            call.put("tipo", "ligacao");
            call.put("status", "RECEBIDA");
            call.put("number", number);
            call.put("dhEvento", callStart);
            call.put("callStart", callStart);
            call.put("callEnd", callEnd);
            call.put("chip", chip);
            call.put("imei", imei);
        } catch (Exception e) {
            // TODO: handle exception
        }

        postOnApi(callsUrl, call);

    }

    private void onOutgoingCallEnded(String number, String callStart, String callEnd, String chip) {
        logger.info("[LIGAÇÃO REALIZADA] número: {}, começo da ligação: {}, final da ligação: {}", number, callStart,
                callEnd);
        JSONObject call = new JSONObject();

        try {
            call.put("tipo", "ligacao");
            call.put("status", "RECEBIDA");
            call.put("number", number);
            call.put("dhEvento", callStart);
            call.put("callStart", callStart);
            call.put("callEnd", callEnd);
            call.put("chip", chip);
            call.put("imei", imei);
        } catch (Exception e) {
            // TODO: handle exception
        }

        postOnApi(callsUrl, call);

    }

    public long getServiceId() {
        return mServiceId;
    }

    public boolean isBound() {
        LocationServiceInfo info = new LocationServiceInfoImpl(this);
        return info.isBound();
    }

    public static boolean isRunning() {
        return sIsRunning;
    }

    public static void setLocationTransform(@Nullable LocationTransform transform) {
        sLocationTransform = transform;
    }

    public static @Nullable LocationTransform getLocationTransform() {
        return sLocationTransform;
    }
}

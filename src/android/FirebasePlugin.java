package org.apache.cordova.firebase;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v4.app.NotificationManagerCompat;
import android.util.Base64;
import android.util.Log;

import android.annotation.TargetApi;
import android.app.NotificationChannel;
import android.content.ContentResolver;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import android.media.AudioAttributes;
import android.net.Uri;

import com.crashlytics.android.Crashlytics;
import io.fabric.sdk.android.Fabric;

import java.lang.reflect.Field;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigInfo;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigValue;
import com.google.firebase.perf.FirebasePerformance;
import com.google.firebase.perf.metrics.Trace;

import me.leolin.shortcutbadger.ShortcutBadger;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.apache.cordova.CordovaWebView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

// Firebase PhoneAuth
import java.util.concurrent.TimeUnit;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.FirebaseException;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.PhoneAuthCredential;
import com.google.firebase.auth.PhoneAuthProvider;

public class FirebasePlugin extends CordovaPlugin {

    private FirebaseAnalytics mFirebaseAnalytics;
    private static CordovaWebView appView;
    private final String TAG = "FirebasePlugin";
    protected static final String KEY = "badge";

    private final String BADGE = "badge";
    private final String CHANNEL_DESCRIPTION = "description";
    private final String CHANNEL_ID = "id";
    private final String CHANNEL_IMPORTANCE = "importance";
    private final String CHANNEL_LIGHT_COLOR = "lightColor";
    private final String CHANNEL_VIBRATION = "vibration";
    private final String SOUND = "sound";
    private final String SOUND_DEFAULT = "default";
    private final String SOUND_RINGTONE = "ringtone";
    private final String VISIBILITY = "visibility";

    private static boolean inBackground = true;
    private static ArrayList<Bundle> notificationStack = null;
    private static CallbackContext notificationCallbackContext;
    private static CallbackContext tokenRefreshCallbackContext;

    /**
     * Gets the application context from cordova's main activity.
     *
     * @return the application context
     */
    private Context getApplicationContext() {
        return this.cordova.getActivity().getApplicationContext();
    }

    @TargetApi(26)
    private void createChannel(JSONObject channel) throws JSONException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        final NotificationManager notificationManager = (NotificationManager) cordova.getActivity()
                .getSystemService(Context.NOTIFICATION_SERVICE);

        String packageName = getApplicationContext().getPackageName();
        NotificationChannel mChannel = new NotificationChannel(channel.getString(CHANNEL_ID),
                channel.optString(CHANNEL_DESCRIPTION, ""),
                channel.optInt(CHANNEL_IMPORTANCE, NotificationManager.IMPORTANCE_DEFAULT));

        int lightColor = channel.optInt(CHANNEL_LIGHT_COLOR, -1);
        if (lightColor != -1) {
            mChannel.setLightColor(lightColor);
        }

        int visibility = channel.optInt(VISIBILITY, NotificationCompat.VISIBILITY_PUBLIC);
        mChannel.setLockscreenVisibility(visibility);

        boolean badge = channel.optBoolean(BADGE, true);
        mChannel.setShowBadge(badge);

        String sound = channel.optString(SOUND, "default");
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build();

        if (SOUND_RINGTONE.equals(sound)) {
            mChannel.setSound(android.provider.Settings.System.DEFAULT_RINGTONE_URI, audioAttributes);
        } else if (sound != null && !sound.contentEquals(SOUND_DEFAULT)) {
            Uri soundUri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + packageName + "/raw/" + sound);
            mChannel.setSound(soundUri, audioAttributes);
        } else {
            mChannel.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, audioAttributes);
        }

        // If vibration settings is an array set vibration pattern, else set enable vibration.
        JSONArray pattern = channel.optJSONArray(CHANNEL_VIBRATION);
        if (pattern != null) {
            int patternLength = pattern.length();
            long[] patternArray = new long[patternLength];
            for (int i = 0; i < patternLength; i++) {
                patternArray[i] = pattern.optLong(i);
            }
            mChannel.setVibrationPattern(patternArray);
        } else {
            boolean vibrate = channel.optBoolean(CHANNEL_VIBRATION, true);
            mChannel.enableVibration(vibrate);
        }

        notificationManager.createNotificationChannel(mChannel);
    }

    @Override
    protected void pluginInitialize() {
        final Context context = this.cordova.getActivity().getApplicationContext();
        final Bundle extras = this.cordova.getActivity().getIntent().getExtras();
        this.cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                Log.d(TAG, "Starting Firebase plugin");
                FirebaseApp.initializeApp(context);
                mFirebaseAnalytics = FirebaseAnalytics.getInstance(context);
                mFirebaseAnalytics.setAnalyticsCollectionEnabled(true);
                if (extras != null && extras.size() > 1) {
                    if (FirebasePlugin.notificationStack == null) {
                        FirebasePlugin.notificationStack = new ArrayList<Bundle>();
                    }
                    if (extras.containsKey("google.message_id")) {
                        extras.putBoolean("tap", true);
                        notificationStack.add(extras);
                    }
                }
            }
        });
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("getInstanceId")) {
            this.getInstanceId(callbackContext);
            return true;
        } else if (action.equals("getId")) {
            this.getId(callbackContext);
            return true;
        } else if (action.equals("getToken")) {
            this.getToken(callbackContext);
            return true;
        } else if (action.equals("hasPermission")) {
            this.hasPermission(callbackContext);
            return true;
        } else if (action.equals("setBadgeNumber")) {
            this.setBadgeNumber(callbackContext, args.getInt(0));
            return true;
        } else if (action.equals("getBadgeNumber")) {
            this.getBadgeNumber(callbackContext);
            return true;
        } else if (action.equals("subscribe")) {
            this.subscribe(callbackContext, args.getString(0));
            return true;
        } else if (action.equals("unsubscribe")) {
            this.unsubscribe(callbackContext, args.getString(0));
            return true;
        } else if (action.equals("unregister")) {
            this.unregister(callbackContext);
            return true;
        } else if (action.equals("onNotificationOpen")) {
            this.onNotificationOpen(callbackContext);
            return true;
        } else if (action.equals("onTokenRefresh")) {
            this.onTokenRefresh(callbackContext);
            return true;
        } else if (action.equals("logEvent")) {
            this.logEvent(callbackContext, args.getString(0), args.getJSONObject(1));
            return true;
        } else if (action.equals("logError")) {
            this.logError(callbackContext, args);
            return true;
        } else if (action.equals("setCrashlyticsUserId")) {
            this.setCrashlyticsUserId(callbackContext, args.getString(0));
            return true;
        } else if (action.equals("setScreenName")) {
            this.setScreenName(callbackContext, args.getString(0));
            return true;
        } else if (action.equals("setUserId")) {
            this.setUserId(callbackContext, args.getString(0));
            return true;
        } else if (action.equals("setUserProperty")) {
            this.setUserProperty(callbackContext, args.getString(0), args.getString(1));
            return true;
        } else if (action.equals("activateFetched")) {
            this.activateFetched(callbackContext);
            return true;
        } else if (action.equals("fetch")) {
            if (args.length() > 0) {
                this.fetch(callbackContext, args.getLong(0));
            } else {
                this.fetch(callbackContext);
            }
            return true;
        } else if (action.equals("getByteArray")) {
            this.getByteArray(callbackContext, args.getString(0));
            return true;
        } else if (action.equals("getValue")) {
            this.getValue(callbackContext, args.getString(0));
            return true;
        } else if (action.equals("getInfo")) {
            this.getInfo(callbackContext);
            return true;
        } else if (action.equals("setConfigSettings")) {
            this.setConfigSettings(callbackContext, args.getJSONObject(0));
            return true;
        } else if (action.equals("setDefaults")) {
            this.setDefaults(callbackContext, args.getJSONObject(0));
            return true;
        } else if (action.equals("verifyPhoneNumber")) {
            this.verifyPhoneNumber(callbackContext, args.getString(0), args.getInt(1));
            return true;
        } else if (action.equals("startTrace")) {
            this.startTrace(callbackContext, args.getString(0));
            return true;
        } else if (action.equals("incrementCounter")) {
            this.incrementCounter(callbackContext, args.getString(0), args.getString(1));
            return true;
        } else if (action.equals("stopTrace")) {
            this.stopTrace(callbackContext, args.getString(0));
            return true;
        } else if (action.equals("setAnalyticsCollectionEnabled")) {
            this.setAnalyticsCollectionEnabled(callbackContext, args.getBoolean(0));
            return true;
        } else if (action.equals("setPerformanceCollectionEnabled")) {
            this.setPerformanceCollectionEnabled(callbackContext, args.getBoolean(0));
            return true;
        } else if (action.equals("clearAllNotifications")) {
            this.clearAllNotifications(callbackContext);
            return true;
        } else if (action.equals("createChannel")) {
            try {
                this.createChannel(args.getJSONObject(0));
                callbackContext.success();
            } catch (JSONException e) {
                callbackContext.error(e.getMessage());
            }
            return true;
        }

        return false;
    }

    @Override
    public void onPause(boolean multitasking) {
        FirebasePlugin.inBackground = true;
    }

    @Override
    public void onResume(boolean multitasking) {
        FirebasePlugin.inBackground = false;
    }

    @Override
    public void onReset() {
        FirebasePlugin.notificationCallbackContext = null;
        FirebasePlugin.tokenRefreshCallbackContext = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (this.appView != null) {
            appView.handleDestroy();
        }
    }

    private void onNotificationOpen(final CallbackContext callbackContext) {
        FirebasePlugin.notificationCallbackContext = callbackContext;
        if (FirebasePlugin.notificationStack != null) {
            for (Bundle bundle : FirebasePlugin.notificationStack) {
                FirebasePlugin.sendNotification(bundle, this.cordova.getActivity().getApplicationContext());
            }
            FirebasePlugin.notificationStack.clear();
        }
    }

    private void onTokenRefresh(final CallbackContext callbackContext) {
        FirebasePlugin.tokenRefreshCallbackContext = callbackContext;

        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    String currentToken = FirebaseInstanceId.getInstance().getToken();
                    if (currentToken != null) {
                        FirebasePlugin.sendToken(currentToken);
                    }
                } catch (Exception e) {
                    Crashlytics.logException(e);
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    public static void sendNotification(Bundle bundle, Context context) {
        if (!FirebasePlugin.hasNotificationsCallback()) {
            String packageName = context.getPackageName();
            if (FirebasePlugin.notificationStack == null) {
                FirebasePlugin.notificationStack = new ArrayList<Bundle>();
            }
            notificationStack.add(bundle);

            return;
        }
        final CallbackContext callbackContext = FirebasePlugin.notificationCallbackContext;
        if (callbackContext != null && bundle != null) {
            JSONObject json = new JSONObject();
            Set<String> keys = bundle.keySet();
            for (String key : keys) {
                try {
                    json.put(key, bundle.get(key));
                } catch (JSONException e) {
                    Crashlytics.logException(e);
                    callbackContext.error(e.getMessage());
                    return;
                }
            }

            PluginResult pluginresult = new PluginResult(PluginResult.Status.OK, json);
            pluginresult.setKeepCallback(true);
            callbackContext.sendPluginResult(pluginresult);
        }
    }

    public static void sendToken(String token) {
        if (FirebasePlugin.tokenRefreshCallbackContext == null) {
            return;
        }

        final CallbackContext callbackContext = FirebasePlugin.tokenRefreshCallbackContext;
        if (callbackContext != null && token != null) {
            PluginResult pluginresult = new PluginResult(PluginResult.Status.OK, token);
            pluginresult.setKeepCallback(true);
            callbackContext.sendPluginResult(pluginresult);
        }
    }

    public static boolean inBackground() {
        return FirebasePlugin.inBackground;
    }

    public static boolean hasNotificationsCallback() {
        return FirebasePlugin.notificationCallbackContext != null;
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        final Bundle data = intent.getExtras();
        if (data != null && data.containsKey("google.message_id")) {
            data.putBoolean("tap", true);
            FirebasePlugin.sendNotification(data, this.cordova.getActivity().getApplicationContext());
        }
    }

    // DEPRECTED - alias of getToken
    private void getInstanceId(final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    String token = FirebaseInstanceId.getInstance().getToken();
                    callbackContext.success(token);
                } catch (Exception e) {
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void getId(final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    String id = FirebaseInstanceId.getInstance().getId();
                    callbackContext.success(id);
                } catch (Exception e) {
                    Crashlytics.logException(e);
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void getToken(final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    String token = FirebaseInstanceId.getInstance().getToken();
                    callbackContext.success(token);
                } catch (Exception e) {
                    Crashlytics.logException(e);
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void hasPermission(final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    Context context = cordova.getActivity();
                    NotificationManagerCompat notificationManagerCompat = NotificationManagerCompat.from(context);
                    boolean areNotificationsEnabled = notificationManagerCompat.areNotificationsEnabled();
                    JSONObject object = new JSONObject();
                    object.put("isEnabled", areNotificationsEnabled);
                    callbackContext.success(object);
                } catch (Exception e) {
                    Crashlytics.logException(e);
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void setBadgeNumber(final CallbackContext callbackContext, final int number) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    Context context = cordova.getActivity();
                    SharedPreferences.Editor editor = context.getSharedPreferences(KEY, Context.MODE_PRIVATE).edit();
                    editor.putInt(KEY, number);
                    editor.apply();
                    ShortcutBadger.applyCount(context, number);
                    callbackContext.success();
                } catch (Exception e) {
                    Crashlytics.logException(e);
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void getBadgeNumber(final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    Context context = cordova.getActivity();
                    SharedPreferences settings = context.getSharedPreferences(KEY, Context.MODE_PRIVATE);
                    int number = settings.getInt(KEY, 0);
                    callbackContext.success(number);
                } catch (Exception e) {
                    Crashlytics.logException(e);
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void subscribe(final CallbackContext callbackContext, final String topic) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    FirebaseMessaging.getInstance().subscribeToTopic(topic);
                    callbackContext.success();
                } catch (Exception e) {
                    Crashlytics.logException(e);
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void unsubscribe(final CallbackContext callbackContext, final String topic) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    FirebaseMessaging.getInstance().unsubscribeFromTopic(topic);
                    callbackContext.success();
                } catch (Exception e) {
                    Crashlytics.logException(e);
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void unregister(final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    FirebaseInstanceId.getInstance().deleteInstanceId();
                    callbackContext.success();
                } catch (Exception e) {
                    Crashlytics.logException(e);
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void logEvent(final CallbackContext callbackContext, final String name, final JSONObject params)
            throws JSONException {
        final Bundle bundle = new Bundle();
        Iterator iter = params.keys();
        while (iter.hasNext()) {
            String key = (String) iter.next();
            Object value = params.get(key);

            if (value instanceof Integer || value instanceof Double) {
                bundle.putFloat(key, ((Number) value).floatValue());
            } else {
                bundle.putString(key, value.toString());
            }
        }

        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    mFirebaseAnalytics.logEvent(name, bundle);
                    callbackContext.success();
                } catch (Exception e) {
                    Crashlytics.logException(e);
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void logError(final CallbackContext callbackContext, final JSONArray args) throws JSONException {
        String message = args.getString(0);

        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    // We can optionally pass a stack trace generated by stacktrace.js.
                    if (args.length() == 2) {
                        JSONArray stackTrace = args.getJSONArray(1);
                        Exception e = new JavaScriptException(message, stackTrace);
                        Crashlytics.logException(e);
                    } else {
                        Crashlytics.logException(new JavaScriptException(message));
                    }
                    callbackContext.success(1);
                } catch (Exception e) {
                    Crashlytics.log("logError errored. Orig error: " + message);
                    Crashlytics.log(e.getMessage());
                    e.printStackTrace();
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void setCrashlyticsUserId(final CallbackContext callbackContext, final String userId) {
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                try {
                    Crashlytics.setUserIdentifier(userId);
                    callbackContext.success();
                } catch (Exception e) {
                    Crashlytics.logException(e);
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void setScreenName(final CallbackContext callbackContext, final String name) {
        // This must be called on the main thread
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                try {
                    mFirebaseAnalytics.setCurrentScreen(cordova.getActivity(), name, null);
                    callbackContext.success();
                } catch (Exception e) {
                    Crashlytics.logException(e);
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void setUserId(final CallbackContext callbackContext, final String id) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    mFirebaseAnalytics.setUserId(id);
                    callbackContext.success();
                } catch (Exception e) {
                    Crashlytics.logException(e);
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void setUserProperty(final CallbackContext callbackContext, final String name, final String value) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    mFirebaseAnalytics.setUserProperty(name, value);
                    callbackContext.success();
                } catch (Exception e) {
                    Crashlytics.logException(e);
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void activateFetched(final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    final boolean activated = FirebaseRemoteConfig.getInstance().activateFetched();
                    callbackContext.success(String.valueOf(activated));
                } catch (Exception e) {
                    Crashlytics.logException(e);
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void fetch(CallbackContext callbackContext) {
        fetch(callbackContext, FirebaseRemoteConfig.getInstance().fetch());
    }

    private void fetch(CallbackContext callbackContext, long cacheExpirationSeconds) {
        fetch(callbackContext, FirebaseRemoteConfig.getInstance().fetch(cacheExpirationSeconds));
    }

    private void fetch(final CallbackContext callbackContext, final Task<Void> task) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    task.addOnSuccessListener(new OnSuccessListener<Void>() {
                        @Override
                        public void onSuccess(Void data) {
                            callbackContext.success();
                        }
                    }).addOnFailureListener(new OnFailureListener() {
                        @Override
                        public void onFailure(Exception e) {
                            Crashlytics.logException(e);
                            callbackContext.error(e.getMessage());
                        }
                    });
                } catch (Exception e) {
                    Crashlytics.logException(e);
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void getByteArray(final CallbackContext callbackContext, final String key) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    byte[] bytes = FirebaseRemoteConfig.getInstance().getByteArray(key);
                    JSONObject object = new JSONObject();
                    object.put("base64", Base64.encodeToString(bytes, Base64.DEFAULT));
                    object.put("array", new JSONArray(bytes));
                    callbackContext.success(object);
                } catch (Exception e) {
                    Crashlytics.logException(e);
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void getValue(final CallbackContext callbackContext, final String key) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    FirebaseRemoteConfigValue value = FirebaseRemoteConfig.getInstance().getValue(key);
                    callbackContext.success(value.asString());
                } catch (Exception e) {
                    Crashlytics.logException(e);
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void getInfo(final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    FirebaseRemoteConfigInfo remoteConfigInfo = FirebaseRemoteConfig.getInstance().getInfo();
                    JSONObject info = new JSONObject();

                    JSONObject settings = new JSONObject();
                    settings.put("developerModeEnabled", remoteConfigInfo.getConfigSettings().isDeveloperModeEnabled());
                    info.put("configSettings", settings);

                    info.put("fetchTimeMillis", remoteConfigInfo.getFetchTimeMillis());
                    info.put("lastFetchStatus", remoteConfigInfo.getLastFetchStatus());

                    callbackContext.success(info);
                } catch (Exception e) {
                    Crashlytics.logException(e);
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void setConfigSettings(final CallbackContext callbackContext, final JSONObject config) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    boolean devMode = config.getBoolean("developerModeEnabled");
                    FirebaseRemoteConfigSettings.Builder settings = new FirebaseRemoteConfigSettings.Builder()
                            .setDeveloperModeEnabled(devMode);
                    FirebaseRemoteConfig.getInstance().setConfigSettings(settings.build());
                    callbackContext.success();
                } catch (Exception e) {
                    Crashlytics.logException(e);
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void setDefaults(final CallbackContext callbackContext, final JSONObject defaults) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    FirebaseRemoteConfig.getInstance().setDefaults(defaultsToMap(defaults));
                    callbackContext.success();
                } catch (Exception e) {
                    Crashlytics.logException(e);
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private static Map<String, Object> defaultsToMap(JSONObject object) throws JSONException {
        final Map<String, Object> map = new HashMap<String, Object>();

        for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
            String key = keys.next();
            Object value = object.get(key);

            if (value instanceof Integer) {
                //setDefaults() should take Longs
                value = new Long((Integer) value);
            } else if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                if (array.length() == 1 && array.get(0) instanceof String) {
                    //parse byte[] as Base64 String
                    value = Base64.decode(array.getString(0), Base64.DEFAULT);
                } else {
                    //parse byte[] as numeric array
                    byte[] bytes = new byte[array.length()];
                    for (int i = 0; i < array.length(); i++)
                        bytes[i] = (byte) array.getInt(i);
                    value = bytes;
                }
            }

            map.put(key, value);
        }
        return map;
    }

    private PhoneAuthProvider.OnVerificationStateChangedCallbacks mCallbacks;

    public void verifyPhoneNumber(
            final CallbackContext callbackContext,
            final String number,
            final int timeOutDuration
    ) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    mCallbacks = new PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                        @Override
                        public void onVerificationCompleted(PhoneAuthCredential credential) {
                            // This callback will be invoked in two situations:
                            // 1 - Instant verification. In some cases the phone number can be instantly
                            //     verified without needing to send or enter a verification code.
                            // 2 - Auto-retrieval. On some devices Google Play services can automatically
                            //     detect the incoming verification SMS and perform verificaiton without
                            //     user action.
                            Log.d(TAG, "success: verifyPhoneNumber.onVerificationCompleted");

                            JSONObject returnResults = new JSONObject();
                            try {
                                String verificationId = null;
                                String code = null;

                                Field[] fields = credential.getClass().getDeclaredFields();
                                for (Field field : fields) {
                                    Class type = field.getType();
                                    if (type == String.class) {
                                        String value = getPrivateField(credential, field);
                                        if (value == null) continue;
                                        if (value.length() > 100) verificationId = value;
                                        else if (value.length() >= 4 && value.length() <= 6) code = value;
                                    }
                                }
                                returnResults.put("verified", verificationId != null && code != null);
                                returnResults.put("verificationId", verificationId);
                                returnResults.put("code", code);
                                returnResults.put("instantVerification", true);
                            } catch (JSONException e) {
                                Crashlytics.logException(e);
                                callbackContext.error(e.getMessage());
                                return;
                            }
                            PluginResult pluginresult = new PluginResult(PluginResult.Status.OK, returnResults);
                            pluginresult.setKeepCallback(true);
                            callbackContext.sendPluginResult(pluginresult);
                        }

                        @Override
                        public void onVerificationFailed(FirebaseException e) {
                            // This callback is invoked in an invalid request for verification is made,
                            // for instance if the the phone number format is not valid.
                            Log.w(TAG, "failed: verifyPhoneNumber.onVerificationFailed ", e);

                            String errorMsg = "unknown error verifying number";
                            errorMsg += " Error instance: " + e.getClass().getName();

                            if (e instanceof FirebaseAuthInvalidCredentialsException) {
                                // Invalid request
                                errorMsg = "Invalid phone number";
                            } else if (e instanceof FirebaseTooManyRequestsException) {
                                // The SMS quota for the project has been exceeded
                                errorMsg = "The SMS quota for the project has been exceeded";
                            }

                            Crashlytics.logException(e);
                            callbackContext.error(errorMsg);
                        }

                        @Override
                        public void onCodeSent(String verificationId, PhoneAuthProvider.ForceResendingToken token) {
                            // The SMS verification code has been sent to the provided phone number, we
                            // now need to ask the user to enter the code and then construct a credential
                            // by combining the code with a verification ID [(in app)].
                            Log.d(TAG, "success: verifyPhoneNumber.onCodeSent");

                            JSONObject returnResults = new JSONObject();
                            try {
                                returnResults.put("verificationId", verificationId);
                                returnResults.put("instantVerification", false);
                            } catch (JSONException e) {
                                Crashlytics.logException(e);
                                callbackContext.error(e.getMessage());
                                return;
                            }
                            PluginResult pluginresult = new PluginResult(PluginResult.Status.OK, returnResults);
                            pluginresult.setKeepCallback(true);
                            callbackContext.sendPluginResult(pluginresult);
                        }
                    };

                    PhoneAuthProvider.getInstance().verifyPhoneNumber(number, // Phone number to verify
                            timeOutDuration, // Timeout duration
                            TimeUnit.SECONDS, // Unit of timeout
                            cordova.getActivity(), // Activity (for callback binding)
                            mCallbacks); // OnVerificationStateChangedCallbacks
                } catch (Exception e) {
                    Crashlytics.logException(e);
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private static String getPrivateField(PhoneAuthCredential credential, Field field) {
        try {
            field.setAccessible(true);
            return (String) field.get(credential);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    //
    // Firebase Performace
    //

    private HashMap<String, Trace> traces = new HashMap<String, Trace>();

    private void startTrace(final CallbackContext callbackContext, final String name) {
        final FirebasePlugin self = this;
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {

                    Trace myTrace = null;
                    if (self.traces.containsKey(name)) {
                        myTrace = self.traces.get(name);
                    }

                    if (myTrace == null) {
                        myTrace = FirebasePerformance.getInstance().newTrace(name);
                        myTrace.start();
                        self.traces.put(name, myTrace);
                    }

                    callbackContext.success();
                } catch (Exception e) {
                    Crashlytics.logException(e);
                    e.printStackTrace();
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void incrementCounter(final CallbackContext callbackContext, final String name, final String counterNamed) {
        final FirebasePlugin self = this;
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {

                    Trace myTrace = null;
                    if (self.traces.containsKey(name)) {
                        myTrace = self.traces.get(name);
                    }

                    if (myTrace != null && myTrace instanceof Trace) {
                        myTrace.incrementMetric(counterNamed, 1);
                        callbackContext.success();
                    } else {
                        callbackContext.error("Trace not found");
                    }
                } catch (Exception e) {
                    Crashlytics.logException(e);
                    e.printStackTrace();
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void stopTrace(final CallbackContext callbackContext, final String name) {
        final FirebasePlugin self = this;
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {

                    Trace myTrace = null;
                    if (self.traces.containsKey(name)) {
                        myTrace = self.traces.get(name);
                    }

                    if (myTrace != null && myTrace instanceof Trace) { //
                        myTrace.stop();
                        self.traces.remove(name);
                        callbackContext.success();
                    } else {
                        callbackContext.error("Trace not found");
                    }
                } catch (Exception e) {
                    Crashlytics.logException(e);
                    e.printStackTrace();
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void setAnalyticsCollectionEnabled(final CallbackContext callbackContext, final boolean enabled) {
        final FirebasePlugin self = this;
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    mFirebaseAnalytics.setAnalyticsCollectionEnabled(enabled);
                    callbackContext.success();
                } catch (Exception e) {
                    Crashlytics.log(e.getMessage());
                    e.printStackTrace();
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void setPerformanceCollectionEnabled(final CallbackContext callbackContext, final boolean enabled) {
        final FirebasePlugin self = this;
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    FirebasePerformance.getInstance().setPerformanceCollectionEnabled(enabled);
                    callbackContext.success();
                } catch (Exception e) {
                    Crashlytics.log(e.getMessage());
                    e.printStackTrace();
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    public void clearAllNotifications(final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    Context context = cordova.getActivity();
                    NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                    nm.cancelAll();
                    callbackContext.success();
                } catch (Exception e) {
                    Crashlytics.log(e.getMessage());
                }
            }
        });
    }
}

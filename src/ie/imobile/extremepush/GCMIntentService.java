package ie.imobile.extremepush;

import ie.imobile.extremepush.api.LogResponseHandler;
import ie.imobile.extremepush.api.ResponseParser;
import ie.imobile.extremepush.api.XtremeRestClient;
import ie.imobile.extremepush.api.model.PushMessage;
import ie.imobile.extremepush.util.LogEventsUtils;
import ie.imobile.extremepush.util.SharedPrefUtils;

import java.io.IOException;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.gcm.GCMBaseIntentService;
import com.loopj.android.http.AsyncHttpResponseHandler;

public final class GCMIntentService extends GCMBaseIntentService {

    private static final String TAG = "GCMIntentService";
    public static final String ACTION_MESSAGE = "ie.imobile.extremepush.action_message";
    public static final String ACTION_REGISTER_ON_SERVER = "ie.imobile.extremepush.register_on_server_please";
    public static final String EXTRAS_PUSH_MESSAGE = "extras_push_message";
    public static final String EXTRAS_REG_ID = "extras_reg_id";
    public static final String EXTRAS_FROM_NOTIFICATION = "extras_from_notification";

    private MediaPlayer mediaPlayer;
    private AsyncHttpResponseHandler pushActionResponseHandler = new LogResponseHandler();

    @Override
    public void onCreate() {
        super.onCreate();
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setOnCompletionListener(new OnCompletionListener() {

            @Override
            public void onCompletion(MediaPlayer mp) {
                mp.release();
            }

        });
    }

    @Override
    protected void onRegistered(Context context, String registrationId) {
        if (PushConnector.DEBUG) Log.d(TAG, "Device registered: regId = " + registrationId);
        if (PushConnector.DEBUG) LogEventsUtils.sendLogTextMessage(context, getString(R.string.gcm_registered));
        sendBroadcast(new Intent(ACTION_REGISTER_ON_SERVER).putExtra(EXTRAS_REG_ID, registrationId));
    }

    @Override
    protected void onUnregistered(Context context, String registrationId) {
        if (PushConnector.DEBUG) Log.d(TAG, "Ignoring unregister callback");
    }

    @Override
    protected void onMessage(Context context, Intent intent) {
        String message = intent.getExtras().getString("message");
        if (PushConnector.DEBUG) Log.d(TAG, "Received push message:" + message);

        PushMessage pushMessage = ResponseParser.parsePushMessage(message);

        if (pushMessage != null) {
            if (!TextUtils.isEmpty(pushMessage.sound)) {
                try {
                    setupCustomSound(pushMessage.sound);
                    playSound();
                } catch (IllegalStateException e) {
                    if (PushConnector.DEBUG) Log.wtf(TAG, e);
                } catch (IOException e) {
                    throw new RuntimeException("Coudn't find " + pushMessage.sound + " in assets.");
                }
            } else {
                playDefaultRingtone();
            }

            String serverDeviceId = SharedPrefUtils.getServerDeviceId(context);
            if (pushMessage.pushActionId != null && serverDeviceId != null) {
                XtremeRestClient.hitAction(context, pushActionResponseHandler, serverDeviceId, pushMessage.pushActionId);
            }

            sendBroadcast(new Intent(ACTION_MESSAGE).putExtra(EXTRAS_PUSH_MESSAGE, pushMessage));
            generateNotification(context, pushMessage);

            LogEventsUtils.sendLogTextMessage(context, "Received message:" + message);
        }
    }

    private void setupCustomSound(String soundName) throws IOException {
        AssetFileDescriptor descriptor = getApplicationContext().getAssets().openFd(soundName);
        mediaPlayer.setDataSource(descriptor.getFileDescriptor(), descriptor.getStartOffset(), descriptor.getLength());
        descriptor.close();
    }

    private void playSound() throws IllegalStateException, IOException {
        mediaPlayer.prepare();
        mediaPlayer.start();
    }

    private void playDefaultRingtone() {
        Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        Ringtone ringtone = RingtoneManager.getRingtone(getApplicationContext(), notification);

        if (ringtone == null) return;

        ringtone.play();
    }

    @Override
    protected void onDeletedMessages(Context context, int total) {
        if (PushConnector.DEBUG) Log.d(TAG, "Received deleted messages notification");
    }

    @Override
    public void onError(Context context, String errorId) {
        if (PushConnector.DEBUG) Log.wtf(TAG, "Received error: " + errorId);
        LogEventsUtils.sendLogTextMessage(context, getString(R.string.gcm_error, errorId));
    }

    @Override
    protected boolean onRecoverableError(Context context, String errorId) {
        if (PushConnector.DEBUG) Log.d(TAG, "Received recoverable error: " + errorId);
        return super.onRecoverableError(context, errorId);
    }

    @SuppressWarnings("deprecation")
    private static void generateNotification(Context context, PushMessage pushMessage) {
        long when = System.currentTimeMillis();
        NotificationManager notificationManager = (NotificationManager) context
                .getSystemService(Context.NOTIFICATION_SERVICE);
        Notification notification = new Notification(R.drawable.ic_stat_gcm, pushMessage.alert, when);
        String title = context.getString(R.string.app_name);

        String mainActivityName = SharedPrefUtils.getMainActivityName(context);
        Intent notificationIntent = new Intent();
        notificationIntent.putExtra(EXTRAS_PUSH_MESSAGE, pushMessage);
        notificationIntent.putExtra(EXTRAS_FROM_NOTIFICATION, true);

        notificationIntent
                .setComponent(new ComponentName(context.getApplicationContext().getPackageName(),
                		mainActivityName));

        // set intent so it does not start a new activity
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        notificationIntent.setAction("open activity" + pushMessage.pushActionId);
        PendingIntent intent = PendingIntent.getActivity(context, 0, notificationIntent, PendingIntent.FLAG_ONE_SHOT);
        notification.setLatestEventInfo(context, title, pushMessage.alert, intent);
        notification.flags |= Notification.FLAG_AUTO_CANCEL;
        notificationManager.notify(Integer.parseInt(pushMessage.pushActionId), notification);
    }
    
    @Override
    protected String[] getSenderIds(Context context) {
        String[] ids = new String[1];
        ids[0] = SharedPrefUtils.getSenderId(context);
        return ids;
    }
    

}

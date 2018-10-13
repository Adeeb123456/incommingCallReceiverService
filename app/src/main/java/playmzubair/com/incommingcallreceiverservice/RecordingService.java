package playmzubair.com.incommingcallreceiverservice;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;
import android.provider.ContactsContract;
import android.provider.DocumentsContract;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.telephony.PhoneNumberUtils;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.Toast;



import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RecordingActivity more likly to be removed from memory when paused then service. Notification button
 * does not handle getActvity without unlocking screen. The only option is to have Service.
 * <p/>
 * So, lets have it.
 * <p/>
 * Maybe later this class will be converted for fully feature recording service with recording thread.
 */
public class RecordingService extends Service  {
    public static final String TAG = RecordingService.class.getSimpleName();

    public static final int NOTIFICATION_RECORDING_ICON = 1;
    public static final int NOTIFICATION_PERSISTENT_ICON = 2;
    public static final int RETRY_DELAY = 60 * 1000; // 1 min

    public static String SHOW_ACTIVITY = RecordingService.class.getCanonicalName() + ".SHOW_ACTIVITY";
    public static String PAUSE_BUTTON = RecordingService.class.getCanonicalName() + ".PAUSE_BUTTON";
    public static String STOP_BUTTON = RecordingService.class.getCanonicalName() + ".STOP_BUTTON";




    AtomicBoolean interrupt = new AtomicBoolean();
    Thread thread;
    Notification notification;
    Notification icon;

    RecordingReceiver receiver;
    PhoneStateReceiver state;
    // output target file 2016-01-01 01.01.01.wav
    Uri targetUri;
    PhoneStateChangeListener pscl;
    Handler handle = new Handler();
    // variable from settings. how may samples per second.
    int sampleRate;
    // how many samples passed for current recording
    long samplesTime;

    Runnable encoding; // current encoding
    HashMap<File, CallInfo> mapTarget = new HashMap<>();

    String phone = "";
    String contact = "";
    String contactId = "";
    String call;
    long now;
    int source = -1; // audiotrecorder source
    Runnable encodingNext = new Runnable() {
        @Override
        public void run() {

        }
    };

    public static class MediaRecorderThread extends Thread {
        public MediaRecorderThread() {
            super("RecordingThread");
        }

        @Override
        public void run() {
            super.run();
        }
    }

    public static void startService(Context context) {
        context.startService(new Intent(context, RecordingService.class));
    }

    public static boolean isEnabled(Context context) {
        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);

        return true;
    }

    public static void startIfEnabled(Context context) {
        if (isEnabled(context))
            startService(context);
    }

    public static void stopService(Context context) {
        context.stopService(new Intent(context, RecordingService.class));
    }

    public static void pauseButton(Context context) {
        Intent intent = new Intent(PAUSE_BUTTON);
        context.sendBroadcast(intent);
    }

    public static void stopButton(Context context) {
        Intent intent = new Intent(STOP_BUTTON);
        context.sendBroadcast(intent);
    }

    public interface Success {
        void run(Uri u);
    }

    public static class CallInfo {
        public Uri targetUri;
        public String phone;
        public String contact;
        public String contactId;
        public String call;
        public long now;

        public CallInfo() {
        }

        public CallInfo(Uri t, String p, String c, String cid, String call, long now) {
            this.targetUri = t;
            this.phone = p;
            this.contact = c;
            this.contactId = cid;
            this.call = call;
            this.now = now;
        }
    }

    class RecordingReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String a = intent.getAction();
                if (a.equals(PAUSE_BUTTON)) {
                    pauseButton();
                }
                if (a.equals(STOP_BUTTON)) {
                    finish();
                }

            } catch (RuntimeException e) {
                Error(e);
            }
        }
    }

    class PhoneStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (a.equals(TelephonyManager.ACTION_PHONE_STATE_CHANGED)) {
                setPhone(intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER), call);
            }
            if (a.equals(Intent.ACTION_NEW_OUTGOING_CALL)) {
                setPhone(intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER), "CALL_OUT");
            }
        }
    }

    class PhoneStateChangeListener extends PhoneStateListener {
        public boolean wasRinging;
        public boolean startedByCall;
        TelephonyManager tm;

        public PhoneStateChangeListener() {
            tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        }

        @Override
        public void onCallStateChanged(final int s, final String incomingNumber) {
            try {
                switch (s) {
                    case TelephonyManager.CALL_STATE_RINGING:
                        new Handler().post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(getApplicationContext(),"Ringing ",Toast.LENGTH_SHORT).show();
                            }
                        });
                        setPhone(incomingNumber, "CALL_IN");
                        wasRinging = true;
                        break;
                    case TelephonyManager.CALL_STATE_OFFHOOK:
                        setPhone(incomingNumber, call);
                        new Handler().post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(getApplicationContext(),"connect ",Toast.LENGTH_SHORT).show();
                            }
                        });

                        if (thread == null) { // handling restart while current call
                            begin(wasRinging);
                            startedByCall = true;
                        }
                        break;
                    case TelephonyManager.CALL_STATE_IDLE:
                        new Handler().post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(getApplicationContext(),"camcel ",Toast.LENGTH_SHORT).show();
                            }
                        });
                        if (startedByCall) {
                            if (tm.getCallState() != TelephonyManager.CALL_STATE_OFFHOOK) { // current state maybe differed from queued (s) one
                                finish();
                            } else {
                                return; // fast clicking. new call already stared. keep recording. do not reset startedByCall
                            }
                        } else {

                        }
                        wasRinging = false;
                        startedByCall = false;
                        phone = "";
                        contactId = "";
                        contact = "";
                        call = "";
                        break;
                }
            } catch (RuntimeException e) {
                Error(e);
            }
        }
    }

    public RecordingService() {
    }

    public void setPhone(String s, String c) {
        if (s == null || s.isEmpty())
            return;

        phone = PhoneNumberUtils.formatNumber(s);

        contact = "";
        contactId = "";
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(s));
        if (Permissions.permitted(this, Permissions.CONTACTS)) {
            try {
                ContentResolver contentResolver = getContentResolver();
                Cursor contactLookup = contentResolver.query(uri, null, null, null, null);
                if (contactLookup != null) {
                    try {
                        if (contactLookup.moveToNext()) {
                            contact = contactLookup.getString(contactLookup.getColumnIndex(ContactsContract.Data.DISPLAY_NAME));
                            contactId = contactLookup.getString(contactLookup.getColumnIndex(BaseColumns._ID));
                        }
                    } finally {
                        contactLookup.close();
                    }
                }
            } catch (RuntimeException e) {
                Error(e);
            }
        }

        new Handler().post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(),"name "+contact+" nmbr "+phone,Toast.LENGTH_SHORT).show();
            }
        });

        call = c;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");



        receiver = new RecordingReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(PAUSE_BUTTON);
        filter.addAction(STOP_BUTTON);

        registerReceiver(receiver, filter);




        pscl = new PhoneStateChangeListener();
        TelephonyManager tm = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
        tm.listen(pscl, PhoneStateListener.LISTEN_CALL_STATE);

        filter = new IntentFilter();
        filter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        filter.addAction(Intent.ACTION_NEW_OUTGOING_CALL);
        state = new PhoneStateReceiver();
        registerReceiver(state, filter);

        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);





        try {

        } catch (RuntimeException e) {
            Error(e);
        }


    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");



        if (intent != null) {
            String a = intent.getAction();
            if (a == null) {
                ; // nothing
            } else if (a.equals(PAUSE_BUTTON)) {
                Intent i = new Intent(PAUSE_BUTTON);
                sendBroadcast(i);
            } else if (a.equals(SHOW_ACTIVITY)) {

            }
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public class Binder extends android.os.Binder {
        public RecordingService getService() {
            return RecordingService.this;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestory");


        handle.removeCallbacks(encodingNext);

        stopRecording();

        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);


        if (receiver != null) {
            unregisterReceiver(receiver);
            receiver = null;
        }

        if (state != null) {
            unregisterReceiver(state);
            state = null;
        }

        if (pscl != null) {
            TelephonyManager tm = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
            tm.listen(pscl, PhoneStateListener.LISTEN_NONE);
            pscl = null;
        }
    }











    void Post(Throwable e) {
        Log.e(TAG, Log.getStackTraceString(e));
        while (e.getCause() != null)
            e = e.getCause();
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty())
            msg = e.getClass().getSimpleName();
        Post("AudioRecord error: " + msg);
    }

    void Post(final String msg) {
        handle.post(new Runnable() {
            @Override
            public void run() {
                Error(msg);
            }
        });
    }

    void Error(Throwable e) {
        Log.d(TAG, "Error", e);


    }

    void Error(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    void pauseButton() {
        if (thread != null) {
            stopRecording();
        } else {

        }
    }

    void stopRecording() {
        if (thread != null) {
            interrupt.set(true);
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    void begin(boolean wasRinging) {

    }

    void finish() {

    }




    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);

    }
}

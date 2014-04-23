package uk.co.thomseddon.relay;

import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.provider.ContactsContract;
import android.telephony.SmsManager;
import android.util.Log;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Calendar;

/**
 * Created by thom on 4/14/14.
 */
public class SocketService extends Service {

    private static String TAG = "RE";

    WebSocketClient mWebSocketClient;
    ObjectMapper mMapper = new ObjectMapper();

    private static int DIRTY_INTERVAL = 5 * 1000; // 5 Seconds
    private Long mDirtyLoopLastTime = 0L;
    private Handler mDirtyLooper;
    private Runnable mDirtyLoop;
    private Calendar mCalendar;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Service Started");

//        if (mReceiver == null)
//            initReceiver();

        if (mCalendar == null)
            mCalendar = Calendar.getInstance();

        if (mDirtyLoop == null)
            initDirtyLoop();

        if (mWebSocketClient != null)
            mWebSocketClient.close();

        // Attempt to connect to websocket
        if (intent != null && reconnect(intent.getStringExtra("uri"))) {
            return START_STICKY;
        } else {
            return START_NOT_STICKY;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean reconnect(final String host) {
        URI uri;
        try {
            Log.i(TAG, host);
            uri = new URI(host);
        } catch (URISyntaxException e) {
            e.printStackTrace();
            return false;
        }
        mWebSocketClient = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake serverHandshake) {
                Log.i(TAG, "WS open");

                mDirtyLoop.run();
            }

            @Override
            public void onMessage(String message) {
                int pos = message.indexOf(" ");
                String name = pos == -1 ? message : message.substring(0, pos);
                String data = pos == -1 ? null : message.substring(pos + 1);

                Log.i(TAG, "WS Message: " + name);
                switch (name) {
                    case "client:listSMS":
                        getSMS(data);
                        break;
                    case "client:listContacts":
                        getContacts();
                        break;
                    case "client:sendText":
                        Log.i(TAG, data);
                        sendText(data);
                        break;
                }
            }

            @Override
            public void onClose(int i, String s, boolean b) {
                Log.i(TAG, "WS close: " + s);
                mDirtyLooper.removeCallbacks(mDirtyLoop);

                // TODO: Exponential back-off/rediscovery
                reconnect(host);
            }

            @Override
            public void onError(Exception e) {
                Log.i(TAG, "WS onError");
                e.printStackTrace();
            }
        };
        mWebSocketClient.connect();

        return true;
    }

    private void getContacts() {
        Uri uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
        if (uri == null)
            return;

        Cursor cursor = getContentResolver().query(uri, null, null, null, null);

        if (cursor == null)
            return;

        ArrayList<Contact> contacts = new ArrayList<>();
        cursor.moveToFirst();
        do {
            if (cursor.getInt(cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)) <= 0)
                continue;

            Contact contact = new Contact();
            contact._id = cursor.getInt(cursor.getColumnIndex(ContactsContract.Contacts._ID));
            contact.name = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME));
            contact.number = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
            contacts.add(contact);
        } while (cursor.moveToNext());

        cursor.close();

        // Serialize and send
        try {
            mWebSocketClient.send("device:listContacts " + mMapper.writeValueAsString(contacts));
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
    }

    private void getSMS() {
        getSMS(null);
    }

    private void getSMS(String query) {

        ArrayList<SMS> smss = new ArrayList<>();

        // Inbox
        querySMS("content://sms/inbox", query, smss);

        // Sent
        querySMS("content://sms/sent", query, smss);

        // Don't bother if it's empty
        if (smss.size() == 0)
            return;

        // Serialize and send
        try {
            mWebSocketClient.send("device:listSMS " + mMapper.writeValueAsString(smss));
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
    }

    private void querySMS (String uri, String query, ArrayList<SMS> smss) {
//        Log.i("RE", "Query: " + query);

        // TODO: Add projection
        Cursor cursor = getContentResolver().query(Uri.parse(uri), null, query, null, null);

        if (cursor == null)
            return;

        if (!cursor.moveToFirst()) {
            // No results
            cursor.close();
            return;
        }

        do {
            SMS sms = new SMS();
            sms._id = cursor.getInt(cursor.getColumnIndex("_id"));
            sms.threadId = cursor.getInt(cursor.getColumnIndex("thread_id"));
            sms.address = cursor.getString(cursor.getColumnIndex("address"));
            sms.date = cursor.getLong(cursor.getColumnIndex("date")) / 1000;
            sms.dateSent = cursor.getLong(cursor.getColumnIndex("date_sent")) / 1000;
            sms.read = cursor.getInt(cursor.getColumnIndex("read"));
            sms.type = cursor.getInt(cursor.getColumnIndex("type"));
            sms.body = cursor.getString(cursor.getColumnIndex("body"));
            sms.seen = cursor.getInt(cursor.getColumnIndex("seen"));

            smss.add(sms);
//            Log.i("RE", "SMS: " + sms.body + ", date:" + sms.date);
        } while (cursor.moveToNext());

        cursor.close();
    }

    private void sendText(String data) {
        try {
            SMS sms = mMapper.readValue(data, SMS.class);

            // Send
            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(sms.address, null, sms.body, null, null);

            ContentValues values = new ContentValues();
            values.put("address", sms.address);
            values.put("body", sms.body);
            getContentResolver().insert(Uri.parse("content://sms/sent"), values);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void initDirtyLoop() {
        mDirtyLooper = new Handler();
        mDirtyLoopLastTime = mCalendar.getTimeInMillis();
        mDirtyLoop = new Runnable() {
            @Override
            public void run() {
                // Get SMS
                ArrayList<SMS> smss = new ArrayList<>();
                querySMS("content://sms/inbox", "date > " + mDirtyLoopLastTime, smss);
                querySMS("content://sms/sent", "date > " + mDirtyLoopLastTime, smss);

                if (smss.size() == 0) {
                    SMS sms = new SMS();
                    sms._id = 123;
                    sms.threadId = 123;
                    sms.address = "+447970314392";
                    sms.date = mDirtyLoopLastTime;
                    sms.dateSent = mDirtyLoopLastTime;
                    sms.read = 0;
                    sms.type = 0;
                    sms.body = "TEST NEW";
                    sms.seen = 0;
                    smss.add(sms);
                }

                // Fake "new message"
                for (SMS sms : smss) {
                    try {
                        mWebSocketClient.send("device:newSMS " + mMapper.writeValueAsString(sms));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                mDirtyLoopLastTime = mCalendar.getTimeInMillis();

                mDirtyLooper.postDelayed(mDirtyLoop, DIRTY_INTERVAL);
            }
        };
    }

//    private final BroadcastReceiver mReceiver =  new BroadcastReceiver() {
//        @Override
//        public void onReceive(Context context, Intent intent) {
//            String action = intent.getAction();
//            Log.i(TAG, "GOT SMS, action: " + action);
//            switch (action) {
//                case "android.provider.Telephony.SMS_RECEIVED":
//                    getSMS();
//                    break;
//            }
//        }
//    };

//    private void initReceiver() {
//        Log.i(TAG, "initReceiver");
//        IntentFilter intentFilter = new IntentFilter();
//        intentFilter.addAction("android.provider.Telephony.SMS_RECEIVED");
//        registerReceiver(mReceiver, intentFilter);
//    }
}

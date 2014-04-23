package uk.co.thomseddon.relay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * Created by thom on 4/23/14.
 */
public class RelayReceiver extends BroadcastReceiver {

    // TODO: Make this work!
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i("RE", "GOT SMS");

        String type = null;
        String action = intent.getAction();

        if (action == null)
            return;

        if (action.equals(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)) {
            type = "smsReceived";
        }

        if (type != null) {
            Intent serviceIntent = new Intent(context, SocketService.class);
            serviceIntent.putExtra("type", type);
            context.startService(intent);
        }
    }
}

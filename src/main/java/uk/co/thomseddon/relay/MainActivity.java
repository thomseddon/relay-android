package uk.co.thomseddon.relay;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.StringTokenizer;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

public class MainActivity extends Activity {

    private static String TAG = "RE";
    private boolean mDiscovering = true;
    public static final String PREFS_NAME = "RelayPrefs";

    Button mButton;

    Handler mHandler;
    Thread mThread;
    WifiManager.MulticastLock mLock;
    JmDNS mJmDns;
    ServiceListener mListener;
    String mService = "_relay._tcp.local.";
    ArrayList<String> mHostNames;
    ArrayList<String> mHosts;
    ArrayAdapter<String> mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.i(TAG, "onCreate");

        // Discover button
        mButton = (Button) findViewById(R.id.button);
        mButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.i(TAG, "Click: discovering=" + (mDiscovering ? "true" : "false"));
                if (mDiscovering) {
                    stopDiscovery();
                } else {
                    startDiscovery();
                }
            }
        });

        // Handler
        mHandler = new Handler();

        // List
        mHostNames = new ArrayList<>();
        mHosts = new ArrayList<>();
        mAdapter = new ArrayAdapter<>(this, R.layout.list_item_host, mHostNames);
        ListView listView = (ListView) findViewById(R.id.listView);
        listView.setAdapter(mAdapter);

        final Context context = this;
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                // Start service
                Intent intent = new Intent(context, SocketService.class);
                String uri = mHosts.get(i);
                intent.putExtra("uri", uri);
                startService(intent);

                // Save connection
                addTrustedHost(uri);

                // Stop discovery
                stopDiscovery();
            }
        });
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mDiscovering)
            stopDiscovery();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mDiscovering)
            startDiscovery();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mDiscovering)
            stopDiscovery();
    }

    private void startDiscovery() {
        Log.i(TAG, "Start discovery");

        mButton.setText("Stop discovery");
        mDiscovering = true;
        mHosts.clear();
        mHostNames.clear();

        if (mListener == null)
            initListener();

        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "RUN");
                WifiManager wifi = (WifiManager) getSystemService(WIFI_SERVICE);
                mLock = wifi.createMulticastLock("relay");
                mLock.setReferenceCounted(true);
                mLock.acquire();

                try {
                    mJmDns = JmDNS.create();
                    mJmDns.addServiceListener(mService, mListener);
                    Log.i(TAG, "Added service listener");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        mThread.start();
    }

    private void stopDiscovery() {
        mButton.setEnabled(false);
        new StopDiscovery().execute();
    }

    private void initListener() {
        Log.i(TAG, "initListener");
        mListener = new ServiceListener() {
            @Override
            public void serviceAdded(ServiceEvent serviceEvent) {
                Log.i(TAG, "Service added");
                mJmDns.requestServiceInfo(serviceEvent.getType(), serviceEvent.getName(), 1);
            }

            @Override
            public void serviceRemoved(ServiceEvent serviceEvent) {
                Log.i(TAG, "Service removed");
            }

            @Override
            public void serviceResolved(ServiceEvent serviceEvent) {
                Log.i(TAG, "Service resolved: " +
                        serviceEvent.getInfo().getInetAddresses()[0].getHostAddress());

                ServiceInfo info = serviceEvent.getInfo();
                String address = info.getInet4Addresses()[0].getHostAddress();
                int port = info.getPort();
                String uri = "ws://" + address + ":" + port;

                if (trustHost(uri)) {
                    // Connect
                    Intent intent = new Intent(getApplicationContext(), SocketService.class);
                    intent.putExtra("uri", uri);
                    startService(intent);

                    // Stop discovery
                    stopDiscovery();
                } else {
                    // Update UI
                    mHostNames.add(info.getName());
                    mHosts.add(uri);
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mAdapter.notifyDataSetChanged();
                        }
                    });
                }
            }
        };
    }

    private boolean trustHost(String host) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, 0);
        String trusted = prefs.getString("trustedHosts", "");
        Log.i(TAG, "Trusted: " + trusted);

        StringTokenizer tokens = new StringTokenizer(trusted, ",");
        while (tokens.hasMoreTokens()) {
            if (tokens.nextToken().equals(host))
                return true;
        }

        return false;
    }

    private void addTrustedHost(String host) {
        if (trustHost(host))
            return;

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, 0);
        String trusted = prefs.getString("trustedHosts", "");
        trusted += (trusted.length() > 0 ? "," : "") + host;

        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("trustedHosts", trusted);
        editor.commit();
    }

    class StopDiscovery extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... voids) {
            Log.i(TAG, "Stop discovery");
            if (mJmDns == null)
                return null;

            if (mListener != null) {
                mJmDns.removeServiceListener(mService, mListener);
            }

            try {
                mJmDns.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mJmDns = null;

            mLock.release();
            mThread = null;
            return null;
        }

        @Override
        public void onPostExecute(Void voids) {
            mDiscovering = false;
            mButton.setEnabled(true);
            mButton.setText("Discover");
        }
    }
}

package com.marktreble.f3ftimer.driver;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDeviceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;

import android.hardware.usb.UsbManager;
import android.os.SystemClock;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.marktreble.f3ftimer.R;
import com.marktreble.f3ftimer.racemanager.RaceActivity;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class USBArduinoService extends Service implements DriverInterface {
		
    private static final String TAG = "USBArduinoService";

    // Commands from timer
    static final String FT_WIND_LEGAL = "C";
    static final String FT_RACE_COMPLETE = "E";
    static final String FT_LEG_COMPLETE = "P";
    static final String FT_READY = "R";
    static final String FT_WIND_ILLEGAL = "W";

    // Commands to timer
    static final String TT_ABORT = "A";
    static final String TT_ADDITIONAL_BUZZER = "B";
    static final String TT_LAUNCH = "S";
    static final String TT_RESEND_TIME = "T";


    public final String encoding = "US_ASCII";
    
	private Driver mDriver;
    private String mBuffer = "";
    public int mTimerStatus = 0;
    
    public boolean mBoardConnected = false;
    
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private SerialInputOutputManager mSerialIoManager = null;


	/*
	 * General life-cycle function overrides
	 */

	@Override
    public void onCreate() {
		super.onCreate();
		mDriver = new Driver(this);

        this.registerReceiver(onBroadcast, new IntentFilter("com.marktreble.f3ftimer.onUpdateFromUI"));
    }
		

    
	@Override
	public void onDestroy() {
        Log.i(TAG, "onDestroy");
		super.onDestroy();  
        if (mDriver!= null)
		    mDriver.destroy();
        mDriver = null;

        if (mSerialIoManager != null) {
            mSerialIoManager.stop();
            mSerialIoManager = null;
        }

        try {
            this.unregisterReceiver(onBroadcast);
        } catch (IllegalArgumentException e){
            e.printStackTrace();
        }
    }

    public static void startDriver(RaceActivity context, String inputSource, Integer race_id, Bundle params){
        if (inputSource.equals(context.getString(R.string.USBArduino))){
            Intent serviceIntent = new Intent(context, USBArduinoService.class);
            serviceIntent.putExtras(params);
            serviceIntent.putExtra("com.marktreble.f3ftimer.race_id", race_id);
            context.startService(serviceIntent);
        }
    }

    public static boolean stop(RaceActivity context){
        if (context.isServiceRunning("com.marktreble.f3ftimer.driver.USBArduinoService")) {
            Intent serviceIntent = new Intent(context, USBArduinoService.class);
            context.stopService(serviceIntent);
            return true;
        }
        return false;
    }
    
    // Binding for UI->Service Communication
    private BroadcastReceiver onBroadcast = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.hasExtra("com.marktreble.f3ftimer.ui_callback")) {
                Bundle extras = intent.getExtras();
                String data = extras.getString("com.marktreble.f3ftimer.ui_callback");
                Log.i("USB SERVICE UI->Service", data);

                if (data.equals("get_connection_status")) {
                    if (mBoardConnected){
                        callbackToUI("driver_started");

                    } else {
                        callbackToUI("driver_stopped");
                    }
                }
            }
        }
    };
    
	@Override
    public int onStartCommand(final Intent intent, int flags, int startId){
    	super.onStartCommand(intent, flags, startId);

        Log.i(TAG, "onStartCommand");
        
        final Runnable make_connection = new Runnable(){
            @Override
            public void run(){
                if (!connect(intent) && mDriver != null){
                    new Handler().postDelayed(this, 100);
                }
            }
        };

        new Handler().postDelayed(make_connection, 100);

    	return (START_STICKY);    	
    }
    
    public boolean connect(Intent intent){
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        final List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);

        List<UsbSerialPort> result = new ArrayList<>();


        Log.i(TAG, "Trying connection...");
        SystemClock.sleep(1000);
        for (final UsbSerialDriver driver : drivers) {
            final List<UsbSerialPort> ports = driver.getPorts();
            result.addAll(ports);
            Log.i(TAG, "Added driver: " + driver.getClass().toString());
        }

        if (result.size() == 0){
            Log.i(TAG, "No Devices found");

        } else {
            Log.i(TAG, "Getting first device");
            UsbSerialPort port = result.get(0);

            UsbDeviceConnection connection = usbManager.openDevice(port.getDriver().getDevice());
            if (connection != null) {
                Log.i(TAG, "Trying to Open Port");

                try {
                    port.open(connection);
                    port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                } catch (IOException e) {
                    e.printStackTrace();
                    try {
                        port.close();
                    } catch (IOException e2) {
                        e2.printStackTrace();
                    }
                    port = null;
                }

                if (port != null) {
                    Log.i(TAG, "Port OK!");
                    if (mSerialIoManager != null) {
                        mSerialIoManager.stop();
                        mSerialIoManager = null;
                    }
                    mSerialIoManager = new SerialInputOutputManager(port, mListener);
                    mExecutor.submit(mSerialIoManager);
                }
            }
            //if (mSerialIoManager == null) return false;
            mBoardConnected = true;
            mDriver.start(intent);
            return true;
        }
        return false;
    }

    
	@Override
	public IBinder onBind(Intent intent) {
        Log.i(TAG, "BINDING");
        
		return null;
	}
	
	// Input - Listener Loop
    private final SerialInputOutputManager.Listener mListener =
            new SerialInputOutputManager.Listener() {

                @Override
                public void onRunError(Exception e) {
                    Log.d(TAG, "Runner stopped.");
                    // Disconnection ??
                    mBoardConnected = false;
                    mDriver.destroy();
                }

                @Override
                public void onNewData(final byte[] data) {
                    String str_in = "";
                    try {
                        str_in =  new String(data, "US-ASCII");
                    } catch (UnsupportedEncodingException e){
                        e.printStackTrace();
                    }
                    if (str_in.length()>0){
                        Log.i("NEWDATA", str_in);
                        int len = str_in.length();
                        if (len>0){
                            String lastchar = str_in.substring(str_in.length()-1, str_in.length());
                            if (lastchar.equals("\n")){
                                // Clear the buffer
                                mBuffer = "";

                                // Get code (first char)
                                String code = "";
                                code=str_in.substring(0, 1);

                                // We have data/command from the timer, pass this on to the server
                                if (code.equals(FT_WIND_LEGAL)){
                                    mDriver.windLegal();
                                } else

                                if (code.equals(FT_WIND_ILLEGAL)){
                                    mDriver.windIllegal();
                                } else

                                if (code.equals(FT_READY)){
                                    mTimerStatus = 0;
                                    mDriver.ready();
                                } else

                                if (code.equals(FT_LEG_COMPLETE)){
                                    switch (mTimerStatus){
                                        case 0:
                                            mDriver.offCourse();
                                            break;
                                        case 1:
                                            mDriver.onCourse();
                                            break;
                                        default:
                                            mDriver.legComplete();
                                            break;

                                    }
                                    mTimerStatus++;
                                } else

                                if (code.equals(FT_RACE_COMPLETE)){
                                    // Make sure we get 9 bytes before proceeding
                                    if (str_in.length()<8){
                                        mBuffer = str_in;
                                    } else {
                                        // Any more than 8 chars should be passed on to the next loop
                                        mBuffer = str_in.substring(8);
                                        // Don't take more than 8 or parseFloat will cause an exception + reflight!
                                        str_in = str_in.substring(0, 8);
                                        mDriver.mPilot_Time = Float.parseFloat(str_in.substring(2).trim());
                                        mDriver.runComplete();
                                        // Reset these here, as sometimes READY is not received!?
                                        mTimerStatus = 0;
                                        mDriver.ready();
                                    }
                                }

                            } else {
                                // Save the characters to the buffer for the next cycle
                                mBuffer = str_in;
                            }
                        }
                    }
                }
            };


    private void callbackToUI(String cmd){
        Intent i = new Intent("com.marktreble.f3ftimer.onUpdate");
        i.putExtra("com.marktreble.f3ftimer.service_callback", cmd);
        this.sendBroadcast(i);
    }
    
    // Output - Send commands to hardware
	private void sendCmd(String cmd){
        byte[] bytes = null;
        int sz = 0;
        try {
            bytes = cmd.getBytes(encoding);
            sz = bytes.length;
        } catch (UnsupportedEncodingException e1) {
            e1.printStackTrace();
        }
        if (sz>0){
            if (mSerialIoManager != null){
                Log.i(TAG, "Sending Data: "+cmd);
                try {
                    mSerialIoManager.writeAsync(bytes);
                } catch (IOException e){
                    e.printStackTrace();
                }
            } else {
                // Call alert dialog on UI Thread "No Output Stream Available"
                Intent i = new Intent("com.marktreble.f3ftimer.onUpdate");
                i.putExtra("com.marktreble.f3ftimer.service_callback", "no_out_stream");
                sendBroadcast(i);


            }
        }
    }
	
	// Driver Interface implementations
    public void sendLaunch(){
        this.sendCmd(TT_LAUNCH);
        mTimerStatus = 0;
    }
    public void sendAbort(){
        this.sendCmd(TT_ABORT);
    }

    public void sendAdditionalBuzzer(){
        this.sendCmd(TT_ADDITIONAL_BUZZER);
    }

    public void sendResendTime(){
        this.sendCmd(TT_RESEND_TIME);
    }

    public void baseA(){}
    public void baseB(){}
    public void finished(String time){}

}

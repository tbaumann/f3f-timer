/*
 * USBJEService
 * HID driver for John Edison timer box
 */
package com.marktreble.f3ftimer.driver;

import ioio.lib.api.DigitalInput;
import ioio.lib.api.DigitalOutput;
import ioio.lib.api.IOIO;
import ioio.lib.api.Uart;
import ioio.lib.api.exception.ConnectionLostException;
import ioio.lib.util.BaseIOIOLooper;
import ioio.lib.util.IOIOLooper;
import ioio.lib.util.android.IOIOService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.util.Xml;

import com.marktreble.f3ftimer.R;
import com.marktreble.f3ftimer.racemanager.RaceActivity;

public class USBJEService extends IOIOService implements DriverInterface {

    private static final String TAG = "USBJEService";
    
    private Intent mIntent;
    
	private Driver mDriver;
	
	public int mTimerStatus = 0;
	public boolean mBoardConnected = false;
	
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

	private Uart uart;
	private InputStream data_in;
	private OutputStream data_out = null;
	private String mBuffer = "";
	
	public IOIOLooper mLooper;
	public DigitalOutput led_;
	
	public DigitalInput start;
	private boolean oStart_status = false;
	
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
		super.onDestroy();
		if (mBoardConnected){
			try {
				data_in.close();
				data_out.close();
				uart.close();
				led_.close();
				start.close();
			} catch (IOException ex){
				ex.printStackTrace();
			}
		}
        
        if (mDriver != null)
		    mDriver.destroy();

        try {
            this.unregisterReceiver(onBroadcast);
        } catch (IllegalArgumentException e){
            e.printStackTrace();
        }
    }

    public static void startDriver(RaceActivity context, String inputSource, Integer race_id, Bundle params){
        if (inputSource.equals(context.getString(R.string.USBJE))){
            Intent serviceIntent = new Intent(context, USBJEService.class);
            serviceIntent.putExtras(params);
            serviceIntent.putExtra("com.marktreble.f3ftimer.race_id", race_id);
            context.startService(serviceIntent);
        }
    }

    public static boolean stop(RaceActivity context){
        if (context.isServiceRunning("com.marktreble.f3ftimer.driver.USBJEService")) {
            Intent serviceIntent = new Intent(context, USBJEService.class);
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
                Log.i(TAG, data);

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
    public int onStartCommand(Intent intent, int flags, int startId){
    	super.onStart(intent, startId);

        mIntent = intent;
    	return (START_STICKY);    	
    }
       	
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
	
	// Input - Listener Loop
	@Override
	protected IOIOLooper createIOIOLooper() {
		
		mLooper =  new BaseIOIOLooper() {
			
			
			@Override
			protected void setup() throws ConnectionLostException, InterruptedException {
				// Light the green LED
				led_ = ioio_.openDigitalOutput(IOIO.LED_PIN);
				
				// Open comms channels
				uart = ioio_.openUart(3, 4, 2400, Uart.Parity.NONE, Uart.StopBits.ONE);
				data_in = uart.getInputStream();
				data_out = uart.getOutputStream();
				
				start = ioio_.openDigitalInput(46);
				
				mBoardConnected = true;
                mDriver.start(mIntent);
			}

            @Override
            public void disconnected(){
                mBoardConnected = false;
                data_out = null;
                mDriver.destroy();
            }
            
			@Override
			public void loop() throws ConnectionLostException, InterruptedException {
				// Read from input
				try {
					// Check start button
					boolean start_status = start.read();
					if (!start_status && oStart_status){
						// pressed
						mDriver.startPressed();
					}

					oStart_status = start_status;
					
					
					// Check input from timer board
					int bytes = data_in.available();
					if (bytes>0){
						byte[] readBuffer = new byte[bytes];
	                    if (data_in.read(readBuffer, 0, bytes)<0) return;
	                    char[] charArray = (new String(readBuffer, 0,bytes)).toCharArray();
	                    
	                    StringBuilder sb = new StringBuilder(charArray.length);
	                    StringBuilder hexString = new StringBuilder();
	                    for (char c : charArray) {
	                        if (c < 0) throw new IllegalArgumentException();
	                        sb.append(Character.toString(c));
	                        
	                        String hex = Integer.toHexString(0xFF & c);
	                        if (hex.length() == 1) {
	                            // could use a for loop, but we're only dealing with a single byte
	                            hexString.append('0');
	                        }
	                        hexString.append(hex);
	                    }
	                    
	                    String str_in = mBuffer+sb.toString().trim();
	                    int len = str_in.length();
	                    if (len>0){
	                    	String lastchar = hexString.substring(hexString.length()-2, hexString.length());
	                    	if (lastchar.equals("0d")){
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
				} catch (IOException e1) {
					mBoardConnected = false;
					data_out = null;
                    mDriver.destroy();
				}
				
				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {
					mBoardConnected = false;
					data_out = null;
                    mDriver.destroy();
				}

			}
		};
		return mLooper;
	}
	
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
			if (data_out != null){
				// send through uart
				try {
					data_out.write(bytes, 0, sz);
				} catch (IOException e) {
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

package com.marktreble.f3ftimer.dialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.marktreble.f3ftimer.R;
import com.marktreble.f3ftimer.languages.Languages;
import com.marktreble.f3ftimer.wifi.Wifi;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

public class SettingsFragment extends PreferenceFragment implements OnSharedPreferenceChangeListener, OnInitListener {
	
	private TextToSpeech mTts;
	@Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.preferences);
        
        mTts = new TextToSpeech(getActivity(), this);
	}
	
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        
        View view = super.onCreateView(inflater, container, savedInstanceState);
        view.setBackgroundColor(getResources().getColor(R.color.background_dialog ));
        
        setSummary("pref_input_src");
        setSummary("pref_voice_lang");
        
    	Preference pref = findPreference("pref_results_server");
        if (Wifi.canEnableWifiHotspot(getActivity()) == false){
        	pref.setSummary("Broadcast results over wifi (http://192.168.43.1:8080)\nYour device may not support this.\nYou will need to enable 'portable wifi hotspot' manually in your settings app.");
        } else {
        	pref.setSummary("Broadcast results over wifi (http://192.168.43.1:8080)");        	
        }
		return view;
   
    }
    
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
    	Intent i = null;

    	// Update value of any list preference
    	Preference pref = findPreference(key);
        if (pref instanceof ListPreference) {
            setSummary(key);
        }
        
        // Callbacks to input driver
        
    	if (key.equals("pref_buzzer") 
     	 || key.equals("pref_voice") 
    	 || key.equals("pref_results_server")){
    		// Send to Service
    		i  = new Intent("com.marktreble.f3ftimer.onUpdateFromUI");
    		i.putExtra("com.marktreble.f3ftimer.ui_callback", key);
    		i.putExtra("com.marktreble.f3ftimer.value", sharedPreferences.getBoolean(key, true));
    		getActivity().sendBroadcast(i);
    	}
    		    	
    	if (key.equals("pref_voice_lang")){
    		// Send to Service
    		i  = new Intent("com.marktreble.f3ftimer.onUpdateFromUI");
    		i.putExtra("com.marktreble.f3ftimer.ui_callback", key);
    		i.putExtra("com.marktreble.f3ftimer.value", sharedPreferences.getString(key, ""));
    		getActivity().sendBroadcast(i);
    	}


    }
    
    @Override
	public void onResume() {
        super.onResume();
        getPreferenceScreen().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this);
    }

    @Override
	public void onPause() {
        super.onPause();
        getPreferenceScreen().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(this);
    }
    
    private void setSummary(String key){
    	Preference pref = findPreference(key);
        if (pref instanceof ListPreference) {
            ListPreference listPref = (ListPreference) pref;
            String value = (String) listPref.getEntry();
            if (value == null){
            	SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(getActivity());
            	Locale current = getResources().getConfiguration().locale;
            	String p = sharedPref.getString("pref_voice_lang", "");
            	String lang = (p.equals("")) ? current.getLanguage() : p.substring(0,2);
            	String country = (p.equals("")) ? current.getCountry() : p.substring(3,5); 
        		Locale l = new Locale(lang,country);
        		value = l.getDisplayName();        		
            }
            pref.setSummary(value);
        }	
    }
    
    @Override
	public void onInit(int status) {
        // Populate pref_voice_lang with installed voices
        populateVoices();

        // Populate pref_external_display with paired devics
        populateExternalDisplayDevices();
    }

    private void populateVoices() {
        String[] languages = Languages.getAvailableLanguages(getActivity());

        // Now check the available languages against the installed TTS Voices
        Locale[] locales = Locale.getAvailableLocales();
        List<String> list_values = new ArrayList<String>();
        List<String> list_labels = new ArrayList<String>();
        for (Locale locale : locales) {

            int ttsres = TextToSpeech.LANG_NOT_SUPPORTED;
            try {
                ttsres = mTts.isLanguageAvailable(locale);
            } catch (IllegalArgumentException e) {
            }

            boolean hasLang = false;
            for (String lang : languages) {
                if (lang.equals(locale.getLanguage())) hasLang = true;
            }
            if ((ttsres == TextToSpeech.LANG_COUNTRY_AVAILABLE) && hasLang) {
                list_labels.add(locale.getDisplayName());
                list_values.add(locale.getLanguage() + "_" + locale.getCountry());
                Log.i("AVAILABLE LANGUAGES", locale.getDisplayName() + ":" + locale.getLanguage() + "_" + locale.getCountry());
            }
        }

        ListPreference pref = (ListPreference) findPreference("pref_voice_lang");
        CharSequence[] labels = list_labels.toArray(new CharSequence[list_labels.size()]);
        CharSequence[] values = list_values.toArray(new CharSequence[list_values.size()]);
        pref.setEntries(labels);
        pref.setEntryValues(values);

    }
    private void populateExternalDisplayDevices(){
        CharSequence[] labels = {"No Devices Paired"};
        CharSequence[] values = {""};

        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter != null) {
            if (bluetoothAdapter.isEnabled()) {
                Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
                if (pairedDevices.size() > 0) {
                    List<String> list_values = new ArrayList<>();
                    List<String> list_labels = new ArrayList<>();
                    for (BluetoothDevice device : pairedDevices) {
                        list_labels.add(device.getName());
                        list_values.add(device.getAddress());
                    }

                    labels = list_labels.toArray(new CharSequence[list_labels.size()]);
                    values = list_values.toArray(new CharSequence[list_values.size()]);
                }
            }
        }

        ListPreference pref = (ListPreference) findPreference("pref_external_display");
        pref.setEntries(labels);
        pref.setEntryValues(values);

    }
	
    @Override
    public void onDestroy(){
    	super.onDestroy();
    	mTts.shutdown();
    }
}
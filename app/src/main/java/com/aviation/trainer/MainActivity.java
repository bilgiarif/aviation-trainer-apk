package com.aviation.trainer;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int FILE_REQ = 1001;
    private static final int APK_VERSION_CODE = 2;
    private static final String PREFS_NAME = "aviation_ota_prefs";

    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private ValueCallback<Uri[]> fileCallback;

    private File getOtaFile() {
        return new File(getFilesDir(), "index_ota.html");
    }

    private File getNativeProgressFile() {
        return new File(getFilesDir(), "ilerleme_native.json");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF14120E);
        getWindow().setNavigationBarColor(0xFF14120E);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int lastApkVer = prefs.getInt("apk_ver", 0);
        if (lastApkVer < APK_VERSION_CODE) {
            // Yeni APK kurulduysa eski OTA önbelleğini temizle
            File oldOta = getOtaFile();
            if (oldOta.exists()) oldOta.delete();
            prefs.edit().putInt("apk_ver", APK_VERSION_CODE).remove("ota_date").apply();
        }

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                tts.setSpeechRate(0.92f);
                ttsReady = true;
            }
        });

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(Intent.createChooser(intent, "ilerleme.json seç"), FILE_REQ);
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        setContentView(web);
        loadBestIndex();
    }

    private void loadBestIndex() {
        File ota = getOtaFile();
        if (ota.exists() && ota.length() > 10000) {
            web.loadUrl("file://" + ota.getAbsolutePath());
        } else {
            web.loadUrl("file:///android_asset/index.html");
        }
    }

    private class Bridge {
        @JavascriptInterface
        public void speak(String text) {
            if (ttsReady && tts != null) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt");
            }
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v == null || !v.hasVibrator()) return;
                if (Build.VERSION.SDK_INT >= 26) {
                    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(ms);
                }
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void setKeepScreenOn(boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void saveNativeProgress(String jsonText) {
            try {
                File tmp = new File(getFilesDir(), "ilerleme_native.tmp");
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    fos.write(jsonText.getBytes(StandardCharsets.UTF_8));
                }
                File dst = getNativeProgressFile();
                if (dst.exists()) dst.delete();
                tmp.renameTo(dst);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public String loadNativeProgress() {
            try {
                File f = getNativeProgressFile();
                if (!f.exists()) return "";
                byte[] buf = new byte[(int) f.length()];
                try (FileInputStream fis = new FileInputStream(f)) {
                    int read = fis.read(buf);
                    if (read > 0) return new String(buf, 0, read, StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {}
            return "";
        }

        @JavascriptInterface
        public String getAppInfo() {
            try {
                File ota = getOtaFile();
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                JSONObject o = new JSONObject();
                o.put("versionCode", APK_VERSION_CODE);
                o.put("isOta", ota.exists() && ota.length() > 10000);
                o.put("otaDate", prefs.getString("ota_date", ""));
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void downloadOtaUpdate(String rawUrl, String token) {
            new Thread(() -> {
                HttpURLConnection conn = null;
                try {
                    String sep = rawUrl.contains("?") ? "&" : "?";
                    URL url = new URL(rawUrl + sep + "t=" + System.currentTimeMillis());
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "AviationTrainerAndroid/2.0");
                    conn.setRequestProperty("Cache-Control", "no-cache");
                    if (token != null && !token.trim().isEmpty()) {
                        conn.setRequestProperty("Authorization", "Bearer " + token.trim());
                    }
                    int code = conn.getResponseCode();
                    if (code != 200) {
                        String hint = (code == 404 || code == 401 || code == 403)
                            ? " (Repo Private ise GitHub Token gir veya repoyu Public yap)"
                            : "";
                        notifyOta(false, "İndirme hatası: HTTP " + code + hint);
                        return;
                    }
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    try (InputStream is = conn.getInputStream()) {
                        byte[] chunk = new byte[8192];
                        int n;
                        while ((n = is.read(chunk)) != -1) {
                            bos.write(chunk, 0, n);
                        }
                    }
                    String html = bos.toString("UTF-8");
                    if (html.length() < 15000 || !html.contains("APP_DATA") || !html.contains("</html>")) {
                        notifyOta(false, "Geçersiz dosya! Doğru raw index.html linkini kullandığından emin ol.");
                        return;
                    }
                    File tmp = new File(getFilesDir(), "index_ota.tmp");
                    try (FileOutputStream fos = new FileOutputStream(tmp)) {
                        fos.write(html.getBytes(StandardCharsets.UTF_8));
                    }
                    File dst = getOtaFile();
                    if (dst.exists()) dst.delete();
                    if (!tmp.renameTo(dst)) {
                        notifyOta(false, "Dosya telefona kaydedilemedi.");
                        return;
                    }
                    String nowStr = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date());
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString("ota_date", nowStr).apply();
                    notifyOta(true, "✅ Güncelleme indirildi (" + nowStr + ")! Yenileniyor...");
                    runOnUiThread(() -> web.postDelayed(() -> loadBestIndex(), 700));
                } catch (Exception e) {
                    notifyOta(false, "Bağlantı hatası: " + e.getMessage());
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }).start();
        }

        @JavascriptInterface
        public void resetOtaUpdate() {
            runOnUiThread(() -> {
                File ota = getOtaFile();
                if (ota.exists()) ota.delete();
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove("ota_date").apply();
                loadBestIndex();
            });
        }

        @JavascriptInterface
        public void shareProgress(String jsonText) {
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_SUBJECT, "ilerleme.json");
                send.putExtra(Intent.EXTRA_TEXT, jsonText);
                startActivity(Intent.createChooser(send, "ilerleme.json paylaş / kaydet"));
            });
        }
    }

    private void notifyOta(boolean ok, String msg) {
        runOnUiThread(() -> web.evaluateJavascript(
            "window.onOtaResult && window.onOtaResult(" + ok + ", " + JSONObject.quote(msg) + ")",
            null
        ));
    }

    @Override
    protected void onActivityResult(int reqCode, int resCode, Intent data) {
        if (reqCode == FILE_REQ && fileCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resCode, data);
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
        super.onActivityResult(reqCode, resCode, data);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript(
            "window.handleAndroidBack ? window.handleAndroidBack() : 'exit'",
            val -> {
                if (val != null && val.contains("exit")) {
                    finish();
                }
            }
        );
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}

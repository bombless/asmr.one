package com.example.xraylocal;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.graphics.Color;
import android.content.SharedPreferences;
import android.net.Uri;
import android.media.MediaPlayer;
import android.media.AudioAttributes;
import android.widget.SeekBar;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;
import android.widget.EditText;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.HorizontalScrollView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONObject;
import org.json.JSONArray;
import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;
import libv2ray.XrayConnection;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.security.cert.Certificate;
import java.security.Principal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

public class MainActivity extends Activity {
    private static final String TAG = "XrayLocalChat";
    private static final String PREFS = "chat_runtime";
    private static final String SECURE_VALUE = "config_secure_v1";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "chat_runtime_config_key";
    private static final int TIMEOUT_MS = 15000;

    private TextView statusView;
    private TextView responseView;
    private Button googleButton;
    private SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private CoreController controller;
    private volatile boolean coreStarted = false;
    private PrivateKey transferPrivateKey;
    private String transferToken;
    private AlertDialog pairingDialog;
    private final ArrayDeque<String> transferDebugLog = new ArrayDeque<>();
    private boolean asmrUseXray = true;
    private volatile String asmrAuthorization = "";
    private volatile String asmrUsername = "";

    // In-app media player fields
    private MediaPlayer mediaPlayer;
    private LocalMediaProxyServer mediaProxyServer;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private Runnable progressRunnable;
    private String currentPlayingTitle = "";
    private String currentPlayingUrl = "";
    private boolean isPreparingMedia = false;
    private LinearLayout playerBarView;
    private TextView playerTitleView;
    private TextView playerTimeView;
    private Button playerPlayPauseBtn;
    private SeekBar playerSeekBar;
    private boolean isUserTrackingSeekBar = false;

    public boolean isAsmrUseXray() {
        return asmrUseXray;
    }

    public String getAsmrAuthorization() {
        return asmrAuthorization;
    }

    public Socket openSocketForUrl(URL url, boolean useXray) throws Exception {
        int port = url.getPort() > 0 ? url.getPort() : (url.getProtocol().equalsIgnoreCase("https") ? 443 : 80);
        if (useXray) {
            waitForXray();
            if (controller == null) throw new java.io.IOException("Xray controller is null");
            if (url.getProtocol().equalsIgnoreCase("https")) {
                XraySSLSocketFactory sf = new XraySSLSocketFactory(controller);
                return sf.createSocket(url.getHost(), port);
            } else {
                return new XraySocket(controller, url.getHost(), port);
            }
        } else {
            if (url.getProtocol().equalsIgnoreCase("https")) {
                SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
                return sf.createSocket(url.getHost(), port);
            } else {
                return new Socket(url.getHost(), port);
            }
        }
    }

    private final CoreCallbackHandler callback = new CoreCallbackHandler() {
        @Override public long startup() { coreStarted = true; return 0L; }
        @Override public long shutdown() { coreStarted = false; return 0L; }
        @Override public long onEmitStatus(long code, String message) {
            Log.d(TAG, "Xray[" + code + "] " + message);
            return 0L;
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setupEdgeToEdgeAndSystemBars();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));

        googleButton = new Button(this);
        googleButton.setText("Access Google");
        googleButton.setAllCaps(false);
        googleButton.setOnClickListener(v -> runGoogleTest());
        root.addView(googleButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setText("Starting Xray Core...");
        statusView.setGravity(Gravity.START);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(16);
        root.addView(statusView, statusParams);

        responseView = new TextView(this);
        responseView.setTextIsSelectable(true);
        responseView.setGravity(Gravity.TOP | Gravity.START);
        responseView.setTextSize(12);
        ScrollView responseScroll = new ScrollView(this);
        responseScroll.addView(responseView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams responseParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        responseParams.topMargin = dp(12);
        root.addView(responseScroll, responseParams);
        root.addView(createPlayerBarView(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        asmrUseXray = prefs.getBoolean("asmr_use_xray", true);
        String savedToken = prefs.getString("asmr_token", "");
        if (!savedToken.isEmpty()) {
            asmrAuthorization = "Bearer " + savedToken;
            asmrUsername = prefs.getString("asmr_username", "");
        }
        Button asmrButton = new Button(this);
        asmrButton.setText("打开 ASMR 浏览器");
        asmrButton.setOnClickListener(v -> showAsmrBrowser());
        root.addView(asmrButton, 0, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button subscriptionsButton = new Button(this);
        subscriptionsButton.setText("代理订阅 / 更新节点");
        subscriptionsButton.setOnClickListener(v -> showSubscriptionManager());
        root.addView(subscriptionsButton, 1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        io.execute(this::startXray);
    }

    private final java.util.ArrayList<AsmrWork> asmrWorks = new java.util.ArrayList<>();
    private int asmrPage = 2;
    private LinearLayout asmrContent;
    private ScrollView asmrScroll;

    private static final class AsmrWork {
        String id, title, cover, date, circle, url;
        AsmrWork(String id, String title, String cover, String date, String circle, String url) {
            this.id=id; this.title=title; this.cover=cover; this.date=date; this.circle=circle; this.url=url;
        }
    }

    private static final class AsmrTrackNode {
        String type;
        String title;
        String streamUrl;
        String downloadUrl;
        double duration;
        long size;
        java.util.ArrayList<AsmrTrackNode> children = new java.util.ArrayList<>();
        boolean expanded = false;

        AsmrTrackNode(String type, String title, String streamUrl, String downloadUrl, double duration, long size) {
            this.type = type != null ? type : "";
            this.title = title != null ? title : "";
            this.streamUrl = streamUrl != null ? streamUrl : "";
            this.downloadUrl = downloadUrl != null ? downloadUrl : "";
            this.duration = duration;
            this.size = size;
        }

        boolean isFolder() {
            return "folder".equalsIgnoreCase(type);
        }

        boolean isAudio() {
            return "audio".equalsIgnoreCase(type);
        }
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        applySafeAreaInsets(view);
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        super.setContentView(view, params);
        applySafeAreaInsets(view);
    }

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        View content = findViewById(android.R.id.content);
        if (content != null) {
            applySafeAreaInsets(content);
        }
    }

    private void setupEdgeToEdgeAndSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
        WindowInsetsControllerCompat insetsController = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (insetsController != null) {
            insetsController.setAppearanceLightStatusBars(true);
            insetsController.setAppearanceLightNavigationBars(true);
        }
    }

    private void applySafeAreaInsets(View view) {
        if (view == null) return;
        int[] basePadding;
        if (view.getTag() instanceof int[]) {
            basePadding = (int[]) view.getTag();
        } else {
            basePadding = new int[]{
                    view.getPaddingLeft(),
                    view.getPaddingTop(),
                    view.getPaddingRight(),
                    view.getPaddingBottom()
            };
            view.setTag(basePadding);
        }
        final int initialLeft = basePadding[0];
        final int initialTop = basePadding[1];
        final int initialRight = basePadding[2];
        final int initialBottom = basePadding[3];

        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
            );
            v.setPadding(
                    initialLeft + insets.left,
                    initialTop + insets.top,
                    initialRight + insets.right,
                    initialBottom + insets.bottom
            );
            return windowInsets;
        });

        WindowInsetsCompat rootInsets = ViewCompat.getRootWindowInsets(getWindow().getDecorView());
        if (rootInsets != null) {
            Insets insets = rootInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
            );
            view.setPadding(
                    initialLeft + insets.left,
                    initialTop + insets.top,
                    initialRight + insets.right,
                    initialBottom + insets.bottom
            );
        }
        ViewCompat.requestApplyInsets(view);
    }

    private void showAsmrBrowser() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout bar = new LinearLayout(this);
        Button back = new Button(this); back.setText("返回"); back.setOnClickListener(v -> showHomeScreenSafe()); bar.addView(back);
        Button settings = new Button(this); settings.setText("代理设置"); settings.setOnClickListener(v -> showAsmrSettings()); bar.addView(settings);
        Button refresh = new Button(this); refresh.setText("刷新"); refresh.setOnClickListener(v -> loadAsmrPage(asmrPage)); bar.addView(refresh);
        Button login = new Button(this); login.setText(asmrAuthorization.isEmpty() ? "登录" : (asmrUsername.isEmpty() ? "已登录" : asmrUsername)); login.setOnClickListener(v -> showAsmrLogin()); bar.addView(login);
        Button diag = new Button(this); diag.setText("诊断日志"); diag.setOnClickListener(v -> showAsmrDiagnostics()); bar.addView(diag);
        HorizontalScrollView barScroll = new HorizontalScrollView(this);
        barScroll.setHorizontalScrollBarEnabled(false);
        barScroll.addView(bar, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(barScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        statusView = new TextView(this);
        statusView.setText(asmrUseXray ? "ASMR HTML · Xray 已开启" : "ASMR HTML · HTTPS 直连");
        root.addView(statusView);
        asmrScroll = new ScrollView(this);
        asmrContent = new LinearLayout(this); asmrContent.setOrientation(LinearLayout.VERTICAL);
        asmrScroll.addView(asmrContent);
        root.addView(asmrScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(createPlayerBarView(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        loadAsmrPage(asmrPage);
    }

    private void showHomeScreenSafe() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));

        googleButton = new Button(this);
        googleButton.setText("Access Google");
        googleButton.setAllCaps(false);
        googleButton.setOnClickListener(v -> runGoogleTest());
        root.addView(googleButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setText(coreStarted ? "Xray Core started" : "Starting Xray Core…");
        statusView.setGravity(Gravity.START);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(16);
        root.addView(statusView, statusParams);

        responseView = new TextView(this);
        responseView.setTextIsSelectable(true);
        responseView.setGravity(Gravity.TOP | Gravity.START);
        responseView.setTextSize(12);
        ScrollView responseScroll = new ScrollView(this);
        responseScroll.addView(responseView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams responseParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        responseParams.topMargin = dp(12);
        root.addView(responseScroll, responseParams);
        root.addView(createPlayerBarView(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button asmr = new Button(this);
        asmr.setText("打开 ASMR 浏览器");
        asmr.setOnClickListener(v -> showAsmrBrowser());
        root.addView(asmr, 0, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button subscriptions = new Button(this);
        subscriptions.setText("代理订阅 / 更新节点");
        subscriptions.setOnClickListener(v -> showSubscriptionManager());
        root.addView(subscriptions, 1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private void showSubscriptionManager() {
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12); layout.setPadding(pad, pad, pad, pad);
        EditText urlInput = new EditText(this); urlInput.setSingleLine(true); urlInput.setHint("粘贴订阅 URL（https://…）");
        urlInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setText(prefs.getString("subscription_url", "")); layout.addView(urlInput, new LinearLayout.LayoutParams(-1, -2));
        TextView status = new TextView(this); status.setText("已保存节点：" + loadSubscriptionNodes().length() + " 个。更新成功后可点选节点。");
        status.setPadding(0, dp(6), 0, dp(6)); layout.addView(status);
        Button update = new Button(this); update.setText("更新订阅列表"); layout.addView(update);
        ScrollView scroll = new ScrollView(this); LinearLayout nodeList = new LinearLayout(this); nodeList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(nodeList); layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("代理订阅").setView(layout).setNegativeButton("关闭", null).create();
        update.setOnClickListener(v -> {
            String subscriptionUrl = urlInput.getText().toString().trim();
            if (!(subscriptionUrl.startsWith("https://") || subscriptionUrl.startsWith("http://"))) { status.setText("请输入有效的 http:// 或 https:// 订阅地址"); return; }
            update.setEnabled(false); update.setText("正在更新…"); status.setText("正在下载并解析订阅列表…");
            io.execute(() -> {
                try {
                    JSONArray parsed = SubscriptionHelper.download(subscriptionUrl, TIMEOUT_MS);
                    if (parsed.length() == 0) throw new java.io.IOException("没有解析到支持的节点（VLESS / VMess / Trojan）");
                    int previous = prefs.getInt("subscription_selected_id", 0); boolean keepPrevious = false;
                    for (int i = 0; i < parsed.length(); i++) {
                        JSONObject item = parsed.optJSONObject(i);
                        if (item != null && item.optInt("id") == previous) keepPrevious = true;
                    }
                    int selected = keepPrevious ? previous : parsed.optJSONObject(0).optInt("id", 1);
                    prefs.edit().putString("subscription_url", subscriptionUrl).putString("subscription_nodes", parsed.toString()).putInt("subscription_selected_id", selected).apply();
                    applySelectedSubscriptionNode(selected, null);
                    runOnUiThread(() -> {
                        if (isFinishing()) return;
                        update.setEnabled(true); update.setText("更新订阅列表");
                        status.setText("更新成功：" + parsed.length() + " 个节点；已应用 " + selectedNodeName(parsed, selected));
                        renderSubscriptionNodes(nodeList, status);
                    });
                } catch (Throwable e) {
                    Log.w(TAG, "Subscription update failed", e);
                    runOnUiThread(() -> {
                        if (!isFinishing()) {
                            update.setEnabled(true); update.setText("重试更新");
                            status.setText("更新失败：" + e.getClass().getSimpleName() + "：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
                        }
                    });
                }
            });
        });
        renderSubscriptionNodes(nodeList, status);
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels * 0.92), (int)(getResources().getDisplayMetrics().heightPixels * 0.78));
    }

    private JSONArray loadSubscriptionNodes() {
        try { return new JSONArray(prefs.getString("subscription_nodes", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    private String selectedNodeName(JSONArray nodes, int id) {
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n != null && n.optInt("id") == id) return n.optString("name", "节点 " + id);
        }
        return "节点 " + id;
    }

    private void renderSubscriptionNodes(LinearLayout nodeList, TextView status) {
        nodeList.removeAllViews();
        JSONArray nodes = loadSubscriptionNodes(); int selected = prefs.getInt("subscription_selected_id", 0);
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i); if (node == null) continue;
            int id = node.optInt("id", i + 1); Button item = new Button(this); item.setAllCaps(false);
            item.setText((id == selected ? "✓ " : "") + node.optString("name", "节点 " + id) + "\n" +
                node.optString("protocol", "").toUpperCase(Locale.ROOT) + " · " + node.optString("address", "") + ":" + node.optInt("port"));
            item.setOnClickListener(v -> {
                prefs.edit().putInt("subscription_selected_id", id).apply();
                status.setText("已选择：" + node.optString("name", "节点 " + id) + "，正在应用到 Xray…");
                renderSubscriptionNodes(nodeList, status);
                io.execute(() -> applySelectedSubscriptionNode(id, status));
            });
            nodeList.addView(item, new LinearLayout.LayoutParams(-1, -2));
        }
        if (nodes.length() == 0) {
            TextView empty = new TextView(this); empty.setText("尚无订阅节点。粘贴订阅地址后点击“更新订阅列表”。");
            empty.setPadding(0, dp(12), 0, dp(12)); nodeList.addView(empty);
        }
    }

    private void applySelectedSubscriptionNode(int id, TextView status) {
        try {
            JSONObject root = configWithSelectedNode(id);
            if (controller != null) try { controller.stopLoop(); } catch (Throwable ignored) {}
            controller = Libv2ray.newCoreController(callback);
            controller.startLoop(root.toString(), 0);
            coreStarted = true;
            JSONArray nodes = loadSubscriptionNodes();
            String message = "已应用代理节点：" + selectedNodeName(nodes, id);
            transferLog("I", message);
            if (status != null) runOnUiThread(() -> status.setText(message));
        } catch (Throwable e) {
            Log.e(TAG, "Failed to apply subscription node", e);
            if (status != null) runOnUiThread(() -> status.setText("节点应用失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
            throw new RuntimeException(e);
        }
    }

    private JSONObject configWithSelectedNode(int id) throws Exception {
        JSONObject root = new JSONObject(readAsset("config.json"));
        JSONArray nodes = loadSubscriptionNodes(); JSONObject selected = null;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject candidate = nodes.optJSONObject(i);
            if (candidate != null && candidate.optInt("id") == id) { selected = candidate; break; }
        }
        if (selected == null) throw new java.io.IOException("找不到已选择的订阅节点");
        JSONObject outbound = SubscriptionHelper.outbound(selected); outbound.put("tag", "proxy");
        JSONArray oldOutbounds = root.optJSONArray("outbounds"); JSONArray replacement = new JSONArray().put(outbound);
        if (oldOutbounds != null) for (int i = 0; i < oldOutbounds.length(); i++) {
            JSONObject old = oldOutbounds.optJSONObject(i);
            if (old != null && "direct".equals(old.optString("tag"))) replacement.put(old);
        }
        if (replacement.length() == 1) replacement.put(new JSONObject().put("tag", "direct").put("protocol", "freedom"));
        root.put("outbounds", replacement);
        JSONObject log = root.optJSONObject("log"); if (log == null) { log = new JSONObject(); root.put("log", log); }
        log.put("loglevel", "debug").put("access", new java.io.File(getFilesDir(), "access.log").getAbsolutePath())
            .put("error", new java.io.File(getFilesDir(), "error.log").getAbsolutePath());
        return root;
    }

    private JSONObject configWithSavedSubscriptionIfAny() throws Exception {
        JSONArray nodes = loadSubscriptionNodes();
        if (nodes.length() == 0) {
            JSONObject root = new JSONObject(readAsset("config.json"));
            JSONObject log = root.optJSONObject("log"); if (log == null) { log = new JSONObject(); root.put("log", log); }
            log.put("loglevel", "debug").put("access", new java.io.File(getFilesDir(), "access.log").getAbsolutePath())
                .put("error", new java.io.File(getFilesDir(), "error.log").getAbsolutePath());
            return root;
        }
        int id = prefs.getInt("subscription_selected_id", nodes.optJSONObject(0).optInt("id", 1));
        return configWithSelectedNode(id);
    }

    private void showAsmrSettings() {
        new AlertDialog.Builder(this).setTitle("ASMR 访问设置")
            .setMessage("开启时，HTML 页面和缩略图通过应用内 Xray 连接请求；关闭时使用 HTTPS 直连。当前页面由原生 Android 控件绘制，不使用 WebView。")
            .setPositiveButton(asmrUseXray ? "关闭 Xray 访问" : "开启 Xray 访问", (d,w) -> {
                asmrUseXray = !asmrUseXray;
                prefs.edit().putBoolean("asmr_use_xray", asmrUseXray).apply();
                loadAsmrPage(asmrPage);
            }).setNegativeButton("取消",null).show();
    }

    private void showAsmrLogin() {
        if (!asmrAuthorization.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("ASMR 账号状态")
                .setMessage("当前已登录账号：" + (asmrUsername.isEmpty() ? "已登录" : asmrUsername) + "\n\n点击“退出登录”可清除凭证；点击“切换账号”可输入新凭证。")
                .setPositiveButton("切换账号", (d, w) -> showAsmrLoginForm())
                .setNeutralButton("退出登录", (d, w) -> {
                    asmrAuthorization = "";
                    asmrUsername = "";
                    prefs.edit().remove("asmr_token").remove("asmr_username").apply();
                    Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show();
                    showAsmrBrowser();
                })
                .setNegativeButton("取消", null)
                .show();
            return;
        }
        showAsmrLoginForm();
    }

    private void showAsmrLoginForm() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16); form.setPadding(pad, pad, pad, pad);
        EditText username = new EditText(this); username.setSingleLine(true); username.setHint("ASMR 用户名 / 邮箱"); username.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        EditText password = new EditText(this); password.setSingleLine(true); password.setHint("密码"); password.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(username); form.addView(password);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("ASMR 账号登录")
            .setMessage("使用站点原生 API 提交账号密码，不打开 WebView；凭证保存在本地以保持登录。")
            .setView(form).setNegativeButton("取消", null).setPositiveButton("登录", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = username.getText().toString().trim(); String pass = password.getText().toString();
            if (name.length() < 5 || pass.length() < 5) { Toast.makeText(this, "用户名和密码至少需要 5 个字符", Toast.LENGTH_LONG).show(); return; }
            Button loginButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            loginButton.setEnabled(false);
            loginButton.setText("连接中…");
            dialog.setMessage("正在连接 ASMR 服务器（" + (asmrUseXray ? "Xray 线路" : "直连") + "），通常需要几秒；若超时会显示原因。");
            asmrLog("I", "ASMR login submitted; usernameChars=" + name.length() + " route=" + (asmrUseXray ? "Xray" : "direct"), null);
            io.execute(() -> {
                try {
                    JSONObject result = postAsmrLogin(name, pass);
                    String token = result.optString("token", "");
                    JSONObject userObj = result.optJSONObject("user");
                    if (token.isEmpty()) {
                        JSONObject data = result.optJSONObject("data");
                        if (data != null) {
                            token = data.optString("token", "");
                            if (userObj == null) userObj = data.optJSONObject("user");
                        }
                    }
                    String userRet = (userObj != null) ? userObj.optString("name", name) : name;
                    asmrLog("I", "ASMR login JSON parsed; tokenPresent=" + !token.isEmpty() + " username=" + userRet, null);
                    if (token.isEmpty()) throw new java.io.IOException("登录响应未包含 token");
                    asmrAuthorization = "Bearer " + token;
                    asmrUsername = userRet;
                    prefs.edit()
                        .putString("asmr_token", token)
                        .putString("asmr_username", userRet)
                        .apply();
                    asmrLog("I", "ASMR LOGIN SUCCESS; bearer token stored in memory & prefs", null);
                    runOnUiThread(() -> { if (!isFinishing()) { dialog.dismiss(); Toast.makeText(this, "ASMR 登录成功（" + userRet + "）", Toast.LENGTH_SHORT).show(); showAsmrBrowser(); } });
                } catch (Throwable e) {
                    asmrLog("W", "ASMR LOGIN FAILED; type=" + e.getClass().getSimpleName() + " message=" + (e.getMessage() == null ? "(none)" : e.getMessage()), e);
                    runOnUiThread(() -> { if (!isFinishing()) {
                        Button retry = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                        if (retry != null) {
                            retry.setText("重试登录");
                            retry.setEnabled(true);
                        }
                        String reason = e instanceof java.net.SocketTimeoutException
                            ? "连接 ASMR 服务器超时。当前线路=" + (asmrUseXray ? "Xray" : "直连") + "；请检查 Xray 节点/网络，或切换线路后重试。"
                            : "登录失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                        dialog.setMessage(reason);
                        Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
                    } });
                }
            });
        }));
        dialog.show();
    }

    // Diagnostic only: Android local DNS can differ from DNS performed by the Xray outbound.
    private void logDnsResolutionAsync(String host, String route) {
        Thread t = new Thread(() -> {
            long started = android.os.SystemClock.elapsedRealtime();
            try {
                java.net.InetAddress[] addresses = java.net.InetAddress.getAllByName(host);
                StringBuilder result = new StringBuilder();
                for (java.net.InetAddress address : addresses) {
                    if (result.length() > 0) result.append(", ");
                    result.append(address.getHostAddress());
                }
                asmrLog("I", "DNS_RESOLVE host=" + host + " route=" + route + " androidResolved=[" + result + "] elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - started) + " note=local-DNS-only; Xray may resolve independently", null);
            } catch (Throwable e) {
                asmrLog("W", "DNS_RESOLVE_FAILED host=" + host + " route=" + route + " type=" + e.getClass().getSimpleName() + " message=" + String.valueOf(e.getMessage()) + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - started), e);
            }
        }, "asmr-dns-diagnostic");
        t.setDaemon(true);
        t.start();
    }

    private HttpsURLConnection openHttpsConnection(String urlText, boolean useXray) throws Exception {
        URL url = new URL(urlText);
        if (useXray) {
            waitForXray();
            if (controller == null) throw new java.io.IOException("Xray controller is null");
            return new XrayHttpsURLConnection(url, controller);
        } else {
            return (HttpsURLConnection) url.openConnection();
        }
    }

    private static String extractErrorMessage(String body, int code) {
        if (body != null && !body.trim().isEmpty()) {
            try {
                JSONObject obj = new JSONObject(body);
                if (obj.has("error")) {
                    String err = obj.optString("error", "").trim();
                    if (!err.isEmpty()) return err;
                }
                if (obj.has("message")) {
                    String msg = obj.optString("message", "").trim();
                    if (!msg.isEmpty()) return msg;
                }
                if (obj.has("errors")) {
                    org.json.JSONArray arr = obj.optJSONArray("errors");
                    if (arr != null && arr.length() > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject item = arr.optJSONObject(i);
                            if (item != null) {
                                String m = item.optString("msg", "").trim();
                                if (!m.isEmpty()) {
                                    if (sb.length() > 0) sb.append("; ");
                                    sb.append(m);
                                }
                            }
                        }
                        if (sb.length() > 0) return sb.toString();
                    }
                }
            } catch (Throwable ignored) { }
            return "HTTP " + code + ": " + (body.length() > 200 ? body.substring(0, 200) + "…" : body);
        }
        return "HTTP " + code;
    }

    private JSONObject postAsmrLogin(String username, String password) throws Exception {
        HttpsURLConnection c = null;
        long started = android.os.SystemClock.elapsedRealtime();
        try {
            asmrLog("I", "ASMR login HTTP POST begin endpoint=/api/auth/me route=" + (asmrUseXray ? "Xray" : "direct") + " coreStarted=" + coreStarted + " controllerNull=" + (controller == null), null);
            logDnsResolutionAsync("api.asmr-200.com", asmrUseXray ? "Xray" : "direct");
            c = openHttpsConnection("https://api.asmr-200.com/api/auth/me", asmrUseXray);
            c.setConnectTimeout(TIMEOUT_MS); c.setReadTimeout(30000); c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36");
            c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Origin", "https://asmr.one"); c.setRequestProperty("Referer", "https://asmr.one/");
            JSONObject payload = new JSONObject(); payload.put("name", username); payload.put("password", password);
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            int code = c.getResponseCode(); InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder body = new StringBuilder();
            if (in != null) try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) { char[] buf = new char[4096]; int n; while ((n = br.read(buf)) != -1 && body.length() < 1024 * 1024) body.append(buf, 0, Math.min(n, 1024 * 1024 - body.length())); }
            asmrLog("I", "ASMR login HTTP response status=" + code + " responseChars=" + body.length() + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - started), null);
            if (code < 200 || code >= 300) {
                String errorMsg = extractErrorMessage(body.toString(), code);
                throw new java.io.IOException(errorMsg);
            }
            return new JSONObject(body.toString());
        } finally { if (c != null) c.disconnect(); }
    }

    private JSONArray fetchAsmrJsonArray(String urlText) throws Exception {
        long started = android.os.SystemClock.elapsedRealtime();
        HttpsURLConnection c = null;
        try {
            String requestHost = new URL(urlText).getHost();
            asmrLog("I", "API tracks request begin host=" + requestHost + " route=" + (asmrUseXray ? "Xray" : "direct") + " coreStarted=" + coreStarted + " controllerNull=" + (controller == null), null);
            logDnsResolutionAsync(requestHost, asmrUseXray ? "Xray" : "direct");
            c = openHttpsConnection(urlText, asmrUseXray);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36");
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            c.setRequestProperty("Origin", "https://asmr.one");
            c.setRequestProperty("Referer", "https://asmr.one/");
            if (!asmrAuthorization.isEmpty()) c.setRequestProperty("Authorization", asmrAuthorization);
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder body = new StringBuilder();
            if (in != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    char[] buf = new char[8192];
                    int n, total = 0;
                    while ((n = br.read(buf)) != -1 && total < 10 * 1024 * 1024) {
                        int keep = Math.min(n, 10 * 1024 * 1024 - total);
                        body.append(buf, 0, keep);
                        total += keep;
                    }
                }
            }
            asmrLog("I", "API tracks response status=" + code + " type=" + c.getContentType() + " chars=" + body.length() + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - started), null);
            if (code < 200 || code >= 300) {
                if (code == 401 && !asmrAuthorization.isEmpty()) {
                    asmrAuthorization = "";
                    asmrUsername = "";
                    prefs.edit().remove("asmr_token").remove("asmr_username").apply();
                }
                throw new java.io.IOException("API HTTP " + code + " body=" + body.substring(0, Math.min(300, body.length())));
            }
            return new JSONArray(body.toString());
        } catch (Throwable e) {
            asmrLog("E", "tracks API request failed", e);
            if (e instanceof Exception) throw (Exception) e;
            throw new java.io.IOException(e);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private JSONObject fetchAsmrJson(String urlText) throws Exception {
        long started=android.os.SystemClock.elapsedRealtime(); HttpsURLConnection c=null;
        try {
            String requestHost = new URL(urlText).getHost();
            asmrLog("I", "API request begin host=" + requestHost + " route=" + (asmrUseXray ? "Xray" : "direct") + " coreStarted=" + coreStarted + " controllerNull=" + (controller == null), null);
            logDnsResolutionAsync(requestHost, asmrUseXray ? "Xray" : "direct");
            c = openHttpsConnection(urlText, asmrUseXray);
            c.setConnectTimeout(TIMEOUT_MS);c.setReadTimeout(30000);c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36");
            c.setRequestProperty("Accept","application/json, text/plain, */*");c.setRequestProperty("Origin","https://asmr.one");c.setRequestProperty("Referer","https://asmr.one/");
            if (!asmrAuthorization.isEmpty()) c.setRequestProperty("Authorization", asmrAuthorization);
            int code=c.getResponseCode();InputStream in=code>=400?c.getErrorStream():c.getInputStream();StringBuilder body=new StringBuilder();
            if(in!=null)try(BufferedReader br=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){char[] buf=new char[8192];int n,total=0;while((n=br.read(buf))!=-1&&total<4*1024*1024){int keep=Math.min(n,4*1024*1024-total);body.append(buf,0,keep);total+=keep;}}
            asmrLog("I","API response status="+code+" type="+c.getContentType()+" chars="+body.length()+" elapsedMs="+(android.os.SystemClock.elapsedRealtime()-started),null);
            if(code<200||code>=300) {
                if (code == 401 && !asmrAuthorization.isEmpty()) {
                    asmrAuthorization = "";
                    asmrUsername = "";
                    prefs.edit().remove("asmr_token").remove("asmr_username").apply();
                }
                throw new java.io.IOException("API HTTP "+code+" body="+body.substring(0,Math.min(300,body.length())));
            }
            return new JSONObject(body.toString());
        }catch(Throwable e){asmrLog("E","works API request failed",e);if(e instanceof Exception)throw (Exception)e;throw new java.io.IOException(e);}finally{if(c!=null)c.disconnect();}
    }

    private java.util.ArrayList<AsmrWork> parseAsmrWorksJson(JSONObject json) throws Exception {
        java.util.ArrayList<AsmrWork> out=new java.util.ArrayList<>();org.json.JSONArray works=json.optJSONArray("works");
        if(works==null)throw new java.io.IOException("API JSON has no works array");
        for(int i=0;i<works.length();i++){JSONObject item=works.optJSONObject(i);if(item==null)continue;String rawId=item.optString("id","");if(rawId.isEmpty())continue;
            String id=rawId.startsWith("RJ")?rawId:"RJ"+String.format(java.util.Locale.US,"%08d",Long.parseLong(rawId));
            String cover=item.optString("mainCoverUrl",item.optString("thumbnailCoverUrl",item.optString("samCoverUrl","")));if(cover.startsWith("/"))cover="https://api.asmr-200.com"+cover;
            JSONObject circleObj=item.optJSONObject("circle");String circle=circleObj==null?item.optString("name",""):circleObj.optString("name","");
            String date=item.optString("release",item.optString("create_date",""));out.add(new AsmrWork(id,item.optString("title",""),cover,date,circle,"https://asmr.one/work/"+id));}
        return out;
    }

    private void loadAsmrPage(int page) {
        asmrPage = Math.max(1,page);
        final int requestedPage = asmrPage;
        asmrContent.removeAllViews();
        TextView loading = new TextView(this); loading.setText("正在请求作品 API…\n代理：" + (asmrUseXray ? "Xray" : "直连") + "\n可点击诊断日志查看详细信息"); loading.setPadding(0,dp(20),0,dp(20)); asmrContent.addView(loading);
        asmrLog("I", "列表请求开始 page=" + requestedPage + " proxy=" + (asmrUseXray ? "Xray" : "direct") + " coreStarted=" + coreStarted + " controllerNull=" + (controller == null), null);
        io.execute(() -> {
            long started = android.os.SystemClock.elapsedRealtime();
            try {
                String url = "https://api.asmr-200.com/api/works?order=create_date&sort=desc&page=" + requestedPage + "&pageSize=20&subtitle=0";
                JSONObject json = fetchAsmrJson(url);
                java.util.ArrayList<AsmrWork> parsed = parseAsmrWorksJson(json);
                asmrLog("I", "works API loaded page=" + requestedPage + " count=" + parsed.size() + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime()-started), null);
                runOnUiThread(() -> {
                    if (isFinishing() || asmrContent == null) return;
                    renderAsmrWorks(parsed, requestedPage);
                    if (parsed.isEmpty()) appendAsmrDiagnosticHint("作品 API 返回了 0 条作品。点击“诊断日志”查看 HTTP 状态和 JSON 响应信息。");
                });
            } catch (Throwable e) {
                asmrLog("E", "列表请求失败 page=" + requestedPage + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime()-started), e);
                runOnUiThread(() -> { if (isFinishing() || asmrContent == null) return; asmrContent.removeAllViews(); TextView err=new TextView(this); err.setText("HTML 加载失败：\n"+e.getClass().getSimpleName()+": "+e.getMessage()+"\n\n点击上方诊断日志查看完整堆栈。"); asmrContent.addView(err); Button retry=new Button(this); retry.setText("重试"); retry.setOnClickListener(v->loadAsmrPage(requestedPage)); asmrContent.addView(retry); });
            }
        });
    }

    private void appendAsmrDiagnosticHint(String message) { TextView hint = new TextView(this); hint.setText(message); hint.setPadding(dp(8),dp(8),dp(8),dp(8)); asmrContent.addView(hint,1); }

    private void asmrLog(String level, String message, Throwable error) {
        String line = android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss.SSS", System.currentTimeMillis()) + " [" + level + "] " + message;
        if ("E".equals(level)) Log.e(TAG, "[ASMR] " + message, error); else if ("W".equals(level)) Log.w(TAG, "[ASMR] " + message, error); else Log.i(TAG, "[ASMR] " + message);
        synchronized (MainActivity.class) {
            java.io.File file = new java.io.File(getFilesDir(), "asmr-diagnostics.log");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file, true); java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(line + "\n");
                if (error != null) { java.io.StringWriter sw = new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(sw)); writer.write(sw.toString()); writer.write("\n"); }
                writer.flush();
                if (file.length() > 2 * 1024 * 1024) { java.io.File old = new java.io.File(getFilesDir(), "asmr-diagnostics.log.1"); if (old.exists()) old.delete(); file.renameTo(old); }
            } catch (Throwable fileError) { Log.e(TAG, "[ASMR] Cannot write diagnostics file", fileError); }
        }
    }

    private void showAsmrDiagnostics() {
        String logs = readAsmrDiagnostics(); EditText view = new EditText(this); view.setText(logs.isEmpty() ? "暂无日志。请点击刷新后再打开诊断日志。" : logs); view.setTextIsSelectable(true); view.setSingleLine(false);
        ScrollView scroll = new ScrollView(this); scroll.addView(view);
        new AlertDialog.Builder(this).setTitle("ASMR 诊断日志").setView(scroll)
            .setPositiveButton("复制全部", (d,w) -> { android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE); cm.setPrimaryClip(android.content.ClipData.newPlainText("ASMR diagnostics", logs)); Toast.makeText(this,"诊断日志已复制",Toast.LENGTH_SHORT).show(); })
            .setNeutralButton("清空", (d,w) -> { java.io.File f = new java.io.File(getFilesDir(), "asmr-diagnostics.log"); if (f.exists()) f.delete(); asmrLog("I", "用户清空诊断日志", null); }).setNegativeButton("关闭",null).show();
    }

    private String readAsmrDiagnostics() {
        java.io.File file = new java.io.File(getFilesDir(), "asmr-diagnostics.log"); if (!file.exists()) return "";
        try { byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath()); int start = Math.max(0, bytes.length - 24000); return new String(bytes, start, bytes.length-start, StandardCharsets.UTF_8); }
        catch (Throwable e) { return "读取日志失败：" + e; }
    }

    private String fetchAsmrHtml(String urlText) throws Exception {
        boolean useXray = asmrUseXray;
        long started = android.os.SystemClock.elapsedRealtime();
        asmrLog("I", "HTTP GET begin url=" + urlText + " route=" + (useXray ? "Xray" : "direct") + " coreStarted=" + coreStarted, null);
        HttpsURLConnection c = null;
        try {
            c = openHttpsConnection(urlText, useXray);
            c.setConnectTimeout(TIMEOUT_MS); c.setReadTimeout(45000); c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36");
            c.setRequestProperty("Accept","text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Accept-Language","zh-CN,zh;q=0.9,en;q=0.8");
            int code=c.getResponseCode();
            String contentType=c.getContentType();
            String finalUrl=c.getURL()==null ? "(unknown)" : c.getURL().toString();
            asmrLog("I", "HTTP response code=" + code + " contentType=" + contentType + " contentLength=" + c.getContentLength() + " finalUrl=" + finalUrl + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime()-started), null);
            InputStream in=code>=400?c.getErrorStream():c.getInputStream();
            StringBuilder out=new StringBuilder();
            if(in!=null) try(BufferedReader br=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))) {
                char[] buf=new char[8192]; int n; int total=0;
                while((n=br.read(buf))!=-1 && total<3*1024*1024){int keep=Math.min(n,3*1024*1024-total);out.append(buf,0,keep);total+=keep;if(total>=3*1024*1024)break;}
            }
            String html=out.toString();
            asmrLog("I", "HTTP body read chars=" + html.length() + " status=" + code + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime()-started), null);
            if (code<200||code>=300) throw new java.io.IOException("HTTP "+code+" contentType="+contentType+" bodyPrefix="+html.substring(0,Math.min(300,html.length())));
            return html;
        } catch (Throwable e) {
            asmrLog("E", "HTTP GET exception url=" + urlText + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime()-started), e);
            if (e instanceof Exception) throw (Exception)e;
            throw new java.io.IOException(e);
        } finally { if(c!=null)c.disconnect(); }
    }

    private static String htmlDecode(String s) {
        if(s==null)return "";
        return android.text.Html.fromHtml(s,android.text.Html.FROM_HTML_MODE_LEGACY).toString().replace("\n"," ").trim();
    }

    private java.util.ArrayList<AsmrWork> parseAsmrWorks(String html) {
        java.util.ArrayList<AsmrWork> out = new java.util.ArrayList<>();
        java.util.regex.Matcher cards=java.util.regex.Pattern.compile("<div\\s+id=\"(RJ[0-9]+)\"[^>]*>(.*?)(?=<div\\s+id=\"RJ[0-9]+\"|</main>)",java.util.regex.Pattern.CASE_INSENSITIVE|java.util.regex.Pattern.DOTALL).matcher(html);
        while(cards.find()) {
            String id=cards.group(1), card=cards.group(2);
            java.util.regex.Matcher titleM=java.util.regex.Pattern.compile("<a[^>]+href=\"/work/"+id+"\"[^>]*>(.*?)</a>",java.util.regex.Pattern.CASE_INSENSITIVE|java.util.regex.Pattern.DOTALL).matcher(card);
            String title="";
            while(titleM.find()){String candidate=titleM.group(1).replaceAll("<[^>]+>"," ").trim();if(candidate.length()>title.length())title=candidate;}
            java.util.regex.Matcher imgM=java.util.regex.Pattern.compile("<img[^>]+src=\"([^\"]*(?:cover|image)[^\"]*)\"",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(card);
            String cover=""; if(imgM.find())cover=imgM.group(1).replace("&amp;","&");
            if(cover.isEmpty()){java.util.regex.Matcher css=java.util.regex.Pattern.compile("background-image:\\s*url\\(&quot;([^&]+)&quot;\\)").matcher(card);if(css.find())cover=css.group(1);}
            java.util.regex.Matcher dateM=java.util.regex.Pattern.compile("(20[0-9]{2}-[0-9]{2}-[0-9]{2})").matcher(card);String date=dateM.find()?dateM.group(1):"";
            java.util.regex.Matcher circleM=java.util.regex.Pattern.compile("class=\"text-grey ellipsis\"[^>]*>(.*?)</div>",java.util.regex.Pattern.DOTALL).matcher(card);String circle=circleM.find()?circleM.group(1).replaceAll("<[^>]+>"," ").trim():"";
            asmrLog("D", "parsed card id=" + id + " titleChars=" + title.length() + " cover=" + cover + " date=" + date + " titlePrefix=" + title.substring(0,Math.min(100,title.length())), null);
            if(!title.isEmpty())out.add(new AsmrWork(id,htmlDecode(title),cover,date,htmlDecode(circle),"https://asmr.one/work/"+id));
        }
        return out;
    }

    private void renderAsmrWorks(java.util.ArrayList<AsmrWork> works, int page) {
        asmrWorks.clear(); asmrWorks.addAll(works); asmrContent.removeAllViews();
        TextView heading=new TextView(this); heading.setText("ASMR 作品 · 第 "+page+" 页（API）"); heading.setTextSize(20); heading.setPadding(0,dp(8),0,dp(8)); asmrContent.addView(heading);
        if(works.isEmpty()){TextView empty=new TextView(this);empty.setText("API 没有返回作品。可尝试登录后刷新，或打开诊断日志查看响应状态。");asmrContent.addView(empty);}
        for(AsmrWork w:works){
            LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.HORIZONTAL); card.setPadding(dp(8),dp(8),dp(8),dp(8)); card.setBackgroundColor(Color.rgb(247,248,252));
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.bottomMargin=dp(8);asmrContent.addView(card,cp);
            ImageView cover=new ImageView(this);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(108),dp(120));ip.rightMargin=dp(10);card.addView(cover,ip);
            if(!w.cover.isEmpty()) loadAsmrThumbnail(w.cover,cover);
            LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);card.addView(info,new LinearLayout.LayoutParams(0,-2,1));
            TextView title=new TextView(this);title.setText(w.title);title.setTextSize(15);title.setTextColor(Color.rgb(25,30,40));info.addView(title);
            TextView meta=new TextView(this);meta.setText(w.id+"  "+w.date+"\n"+w.circle);meta.setTextSize(12);meta.setTextColor(Color.DKGRAY);info.addView(meta);
            Button open=new Button(this);open.setText("查看目录");open.setOnClickListener(v->loadAsmrDetail(w));info.addView(open);
            card.setOnClickListener(v->loadAsmrDetail(w));
        }
        LinearLayout pager=new LinearLayout(this);Button prev=new Button(this);prev.setText("上一页");prev.setEnabled(page>1);prev.setOnClickListener(v->loadAsmrPage(page-1));pager.addView(prev);
        Button next=new Button(this);next.setText("下一页");next.setOnClickListener(v->loadAsmrPage(page+1));pager.addView(next);asmrContent.addView(pager);
    }

    private void loadAsmrThumbnail(String url, ImageView target) {
        io.execute(() -> {
            try {
                HttpsURLConnection c = openHttpsConnection(url, asmrUseXray);
                c.setConnectTimeout(TIMEOUT_MS);c.setReadTimeout(30000);
                try(InputStream in=c.getInputStream()){android.graphics.Bitmap bmp=android.graphics.BitmapFactory.decodeStream(in);if(bmp!=null)runOnUiThread(()->target.setImageBitmap(bmp));}
                finally{c.disconnect();}
            } catch(Throwable e){Log.w(TAG,"ASMR thumbnail failed: "+url,e);}
        });
    }

    private static String extractNumericId(String id) {
        if (id == null) return "";
        return id.replaceAll("[^0-9]", "");
    }

    private static String formatDuration(double sec) {
        if (sec <= 0) return "";
        long s = Math.round(sec);
        long m = s / 60;
        long remS = s % 60;
        if (m < 60) {
            return String.format(Locale.US, "%02d:%02d", m, remS);
        }
        long h = m / 60;
        long remM = m % 60;
        return String.format(Locale.US, "%d:%02d:%02d", h, remM, remS);
    }

    private static String formatFileSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private java.util.ArrayList<AsmrTrackNode> parseAsmrTracksJson(JSONArray array) {
        java.util.ArrayList<AsmrTrackNode> list = new java.util.ArrayList<>();
        if (array == null) return list;
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            String type = obj.optString("type", "");
            String title = obj.optString("title", "");
            String stream = obj.optString("mediaStreamUrl", "");
            String download = obj.optString("mediaDownloadUrl", "");
            double duration = obj.optDouble("duration", 0);
            long size = obj.optLong("size", 0);
            AsmrTrackNode node = new AsmrTrackNode(type, title, stream, download, duration, size);
            JSONArray childrenArr = obj.optJSONArray("children");
            if (childrenArr != null && childrenArr.length() > 0) {
                node.children = parseAsmrTracksJson(childrenArr);
            }
            list.add(node);
        }
        return list;
    }

    private void countTracks(java.util.List<AsmrTrackNode> nodes, int[] counts) {
        if (nodes == null) return;
        for (AsmrTrackNode node : nodes) {
            if (node.isFolder()) {
                counts[0]++;
                countTracks(node.children, counts);
            } else {
                counts[1]++;
                if (node.isAudio()) counts[2]++;
            }
        }
    }

    private void loadAsmrDetail(AsmrWork work) {
        asmrContent.removeAllViews();
        TextView loading = new TextView(this);
        loading.setText("正在通过 ASMR API 请求作品目录…\n代理：" + (asmrUseXray ? "Xray" : "直连"));
        loading.setPadding(0, dp(20), 0, dp(20));
        asmrContent.addView(loading);
        io.execute(() -> {
            try {
                String numericId = extractNumericId(work.id);
                if (numericId.isEmpty()) {
                    throw new IllegalArgumentException("无法从作品 ID 提取数字：" + work.id);
                }
                asmrLog("I", "作品目录请求开始 workId=" + work.id + " numericId=" + numericId, null);
                String tracksUrl = "https://api.asmr-200.com/api/tracks/" + numericId + "?v=2";
                JSONArray arr = fetchAsmrJsonArray(tracksUrl);
                java.util.ArrayList<AsmrTrackNode> nodes = parseAsmrTracksJson(arr);
                int[] stats = new int[3]; // [folders, totalFiles, audioFiles]
                countTracks(nodes, stats);
                asmrLog("I", "作品目录加载成功 workId=" + work.id + " folders=" + stats[0] + " files=" + stats[1] + " audio=" + stats[2], null);
                runOnUiThread(() -> {
                    if (isFinishing() || asmrContent == null) return;
                    renderAsmrDetail(work, nodes, stats);
                });
            } catch (Throwable e) {
                asmrLog("E", "作品目录请求失败 workId=" + work.id, e);
                runOnUiThread(() -> {
                    if (isFinishing() || asmrContent == null) return;
                    asmrContent.removeAllViews();
                    Button back = new Button(this);
                    back.setText("← 返回作品列表");
                    back.setOnClickListener(v -> loadAsmrPage(asmrPage));
                    asmrContent.addView(back);

                    TextView err = new TextView(this);
                    err.setText("目录加载失败：\n" + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n\n可检查代理设置或在诊断日志中查看详细错误。");
                    err.setTextColor(Color.RED);
                    err.setPadding(0, dp(12), 0, dp(12));
                    asmrContent.addView(err);

                    Button retry = new Button(this);
                    retry.setText("重试");
                    retry.setOnClickListener(v -> loadAsmrDetail(work));
                    asmrContent.addView(retry);
                });
            }
        });
    }

    private void renderAsmrDetail(AsmrWork work, java.util.ArrayList<AsmrTrackNode> nodes, int[] stats) {
        asmrContent.removeAllViews();
        Button back = new Button(this);
        back.setText("← 返回作品列表");
        back.setOnClickListener(v -> loadAsmrPage(asmrPage));
        asmrContent.addView(back);

        TextView title = new TextView(this);
        title.setText(work.title);
        title.setTextSize(18);
        title.setTextColor(Color.rgb(20, 25, 35));
        title.setPadding(0, dp(8), 0, dp(4));
        asmrContent.addView(title);

        TextView meta = new TextView(this);
        meta.setText(work.id + (work.circle.isEmpty() ? "" : "  |  " + work.circle) + (work.date.isEmpty() ? "" : "  |  " + work.date));
        meta.setTextSize(13);
        meta.setTextColor(Color.DKGRAY);
        meta.setPadding(0, 0, 0, dp(8));
        asmrContent.addView(meta);

        TextView summary = new TextView(this);
        summary.setText("目录结构：共 " + stats[0] + " 个文件夹，" + stats[1] + " 个文件（包含 " + stats[2] + " 个音频）\n点击文件夹可展开/折叠，点击音频可播放/复制直链");
        summary.setTextSize(12);
        summary.setTextColor(Color.rgb(80, 90, 100));
        summary.setPadding(dp(8), dp(6), dp(8), dp(8));
        summary.setBackgroundColor(Color.rgb(240, 243, 248));
        asmrContent.addView(summary);

        if (nodes.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("该作品目录为空。");
            empty.setPadding(0, dp(16), 0, dp(16));
            asmrContent.addView(empty);
            return;
        }

        LinearLayout treeContainer = new LinearLayout(this);
        treeContainer.setOrientation(LinearLayout.VERTICAL);
        treeContainer.setPadding(0, dp(8), 0, dp(24));
        renderTrackNodes(treeContainer, nodes, 0);
        asmrContent.addView(treeContainer);
    }

    private void renderTrackNodes(LinearLayout container, java.util.List<AsmrTrackNode> nodes, int depth) {
        if (nodes == null) return;
        for (AsmrTrackNode node : nodes) {
            if (node.isFolder()) {
                LinearLayout folderRow = new LinearLayout(this);
                folderRow.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams frParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                frParams.topMargin = dp(3);
                frParams.bottomMargin = dp(3);
                folderRow.setLayoutParams(frParams);

                LinearLayout header = new LinearLayout(this);
                header.setOrientation(LinearLayout.HORIZONTAL);
                header.setGravity(Gravity.CENTER_VERTICAL);
                header.setPadding(dp(8 + depth * 14), dp(8), dp(8), dp(8));
                header.setBackgroundColor(Color.rgb(235, 240, 248));

                TextView arrow = new TextView(this);
                arrow.setText(node.expanded ? "▼ " : "▶ ");
                arrow.setTextSize(13);
                arrow.setTextColor(Color.rgb(30, 90, 160));
                header.addView(arrow);

                TextView fTitle = new TextView(this);
                int childCount = node.children != null ? node.children.size() : 0;
                fTitle.setText("📁 " + node.title + " (" + childCount + ")");
                fTitle.setTextSize(14);
                fTitle.setTextColor(Color.rgb(20, 30, 50));
                fTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                header.addView(fTitle);

                LinearLayout childrenContainer = new LinearLayout(this);
                childrenContainer.setOrientation(LinearLayout.VERTICAL);
                childrenContainer.setVisibility(node.expanded ? View.VISIBLE : View.GONE);

                header.setOnClickListener(v -> {
                    node.expanded = !node.expanded;
                    arrow.setText(node.expanded ? "▼ " : "▶ ");
                    if (node.expanded && childrenContainer.getChildCount() == 0) {
                        renderTrackNodes(childrenContainer, node.children, depth + 1);
                    }
                    childrenContainer.setVisibility(node.expanded ? View.VISIBLE : View.GONE);
                });

                folderRow.addView(header);
                folderRow.addView(childrenContainer);

                if (node.expanded) {
                    renderTrackNodes(childrenContainer, node.children, depth + 1);
                }

                container.addView(folderRow);
            } else {
                LinearLayout itemRow = new LinearLayout(this);
                itemRow.setOrientation(LinearLayout.HORIZONTAL);
                itemRow.setGravity(Gravity.CENTER_VERTICAL);
                itemRow.setPadding(dp(8 + depth * 14), dp(6), dp(8), dp(6));

                String icon = "📄 ";
                if (node.isAudio()) icon = "🎵 ";
                else if ("image".equalsIgnoreCase(node.type)) icon = "🖼️ ";
                else if ("text".equalsIgnoreCase(node.type)) icon = "📝 ";

                LinearLayout infoBox = new LinearLayout(this);
                infoBox.setOrientation(LinearLayout.VERTICAL);
                infoBox.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

                TextView itemTitle = new TextView(this);
                itemTitle.setText(icon + node.title);
                itemTitle.setTextSize(13);
                itemTitle.setTextColor(node.isAudio() ? Color.rgb(10, 60, 120) : Color.rgb(40, 45, 55));
                infoBox.addView(itemTitle);

                StringBuilder metaSb = new StringBuilder();
                if (node.duration > 0) metaSb.append(formatDuration(node.duration));
                if (node.size > 0) {
                    if (metaSb.length() > 0) metaSb.append("  |  ");
                    metaSb.append(formatFileSize(node.size));
                }
                if (metaSb.length() > 0) {
                    TextView itemMeta = new TextView(this);
                    itemMeta.setText(metaSb.toString());
                    itemMeta.setTextSize(11);
                    itemMeta.setTextColor(Color.GRAY);
                    infoBox.addView(itemMeta);
                }

                itemRow.addView(infoBox);

                String playUrl = !node.streamUrl.isEmpty() ? node.streamUrl : node.downloadUrl;
                if (!playUrl.isEmpty()) {
                    if (node.isAudio()) {
                        Button playBtn = new Button(this);
                        playBtn.setText("播放");
                        playBtn.setTextSize(12);
                        playBtn.setPadding(dp(8), dp(2), dp(8), dp(2));
                        playBtn.setOnClickListener(v -> playAudioInApp(node.title, playUrl));
                        itemRow.addView(playBtn);
                    }
                    Button actionBtn = new Button(this);
                    actionBtn.setText("更多");
                    actionBtn.setTextSize(12);
                    actionBtn.setPadding(dp(8), dp(2), dp(8), dp(2));
                    actionBtn.setOnClickListener(v -> showTrackActionDialog(node, playUrl));
                    itemRow.addView(actionBtn);
                }

                itemRow.setOnClickListener(v -> {
                    if (!playUrl.isEmpty()) {
                        if (node.isAudio()) {
                            playAudioInApp(node.title, playUrl);
                        } else {
                            showTrackActionDialog(node, playUrl);
                        }
                    }
                });

                container.addView(itemRow);
            }
        }
    }

    private void showTrackActionDialog(AsmrTrackNode node, String url) {
        String[] actions;
        if (node.isAudio()) {
            actions = new String[]{"应用内播放", "外部应用播放 (调用播放器)", "复制播放流直链", "复制下载直链 (如有)", "查看完整地址"};
        } else {
            actions = new String[]{"外部应用打开", "复制文件直链", "查看完整地址"};
        }

        new AlertDialog.Builder(this)
                .setTitle(node.title)
                .setItems(actions, (dialog, which) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (node.isAudio()) {
                        if (which == 0) {
                            playAudioInApp(node.title, url);
                        } else if (which == 1) {
                            try {
                                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                                intent.setDataAndType(Uri.parse(url), "audio/*");
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(intent);
                            } catch (Throwable e) {
                                Toast.makeText(this, "启动播放器失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        } else if (which == 2) {
                            if (cm != null) {
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("audio_stream", url));
                                Toast.makeText(this, "播放流地址已复制到剪贴板", Toast.LENGTH_SHORT).show();
                            }
                        } else if (which == 3) {
                            String dUrl = !node.downloadUrl.isEmpty() ? node.downloadUrl : url;
                            if (cm != null) {
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("audio_download", dUrl));
                                Toast.makeText(this, "下载直链已复制到剪贴板", Toast.LENGTH_SHORT).show();
                            }
                        } else if (which == 4) {
                            new AlertDialog.Builder(this)
                                    .setTitle("文件地址")
                                    .setMessage("流地址:\n" + url + "\n\n下载地址:\n" + (node.downloadUrl.isEmpty() ? "(同上)" : node.downloadUrl))
                                    .setPositiveButton("复制", (d, w) -> {
                                        if (cm != null) {
                                            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url));
                                            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
                                        }
                                    })
                                    .setNegativeButton("关闭", null)
                                    .show();
                        }
                    } else {
                        if (which == 0) {
                            try {
                                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                                intent.setData(Uri.parse(url));
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(intent);
                            } catch (Throwable e) {
                                Toast.makeText(this, "打开失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        } else if (which == 1) {
                            if (cm != null) {
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url));
                                Toast.makeText(this, "文件直链已复制到剪贴板", Toast.LENGTH_SHORT).show();
                            }
                        } else if (which == 2) {
                            new AlertDialog.Builder(this)
                                    .setTitle("文件地址")
                                    .setMessage(url)
                                    .setPositiveButton("复制", (d, w) -> {
                                        if (cm != null) {
                                            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url));
                                            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
                                        }
                                    })
                                    .setNegativeButton("关闭", null)
                                    .show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void runGoogleTest() {
        Log.d(TAG, "GOOGLE_STEP_0 click");
        googleButton.setEnabled(false);
        statusView.setText("Dialing Google through in-process Xray...");
        responseView.setText("");
        io.execute(() -> {
            HttpsURLConnection c = null;
            try {
                Log.d(TAG, "GOOGLE_STEP_1 waitForXray");
                waitForXray();
                Log.d(TAG, "GOOGLE_STEP_2 create URL");
                final String host = "www.google.com";
                URL url = new URL(null, "https://" + host + "/", new java.net.URLStreamHandler() {
                    @Override protected java.net.URLConnection openConnection(URL u) {
                        return new XrayHttpsURLConnection(u, controller);
                    }
                });
                Log.d(TAG, "GOOGLE_STEP_3 openConnection (custom HttpsURLConnection; avoids platform raw TCP)");
                c = (HttpsURLConnection) url.openConnection();
                Log.d(TAG, "GOOGLE_STEP_4 set XraySSLSocketFactory");
                c.setSSLSocketFactory(new XraySSLSocketFactory(controller));
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "XrayLocal/1.0");
                c.setRequestProperty("Connection", "close");
                Log.d(TAG, "GOOGLE_STEP_5 getResponseCode");
                int code = c.getResponseCode();
                Log.d(TAG, "GOOGLE_STEP_6 responseCode=" + code
                        + " message=" + c.getResponseMessage()
                        + " url=" + c.getURL()
                        + " location=" + c.getHeaderField("Location")
                        + " contentType=" + c.getContentType()
                        + " contentLength=" + c.getContentLengthLong());
                Log.d(TAG, "GOOGLE_STEP_7 getInputStream");
                InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
                StringBuilder body = new StringBuilder();
                final int maxChars = 128 * 1024;
                Log.d(TAG, "GOOGLE_STEP_8 start body read");
                if (stream != null) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        char[] buffer = new char[4096];
                        int count;
                        int total = 0;
                        while (total < maxChars &&
                                (count = reader.read(buffer, 0,
                                        Math.min(buffer.length, maxChars - total))) != -1) {
                            body.append(buffer, 0, count);
                            total += count;
                        }
                    }
                }
                final int responseCode = code;
                final String html = body.toString();
                Log.d(TAG, "GOOGLE_STEP_9 body read finished length=" + html.length());
                runOnUiThread(() -> {
                    Log.d(TAG, "GOOGLE_STEP_10 update UI");
                    statusView.setText("Google HTTPS " + responseCode
                            + " (in-process Xray Core) · HTML " + html.length() + " chars");
                    responseView.setText(html);
                    googleButton.setEnabled(true);
                    Log.d(TAG, "GOOGLE_STEP_11 UI updated");
                });
            } catch (Throwable t) {
                Log.e(TAG, "Google in-process HTTPS failed", t);
                final String message = t.getClass().getSimpleName() + ": " +
                        (t.getMessage() == null ? "unknown error" : t.getMessage());
                runOnUiThread(() -> {
                    statusView.setText("HTTPS failed: " + message);
                    responseView.setText("");
                    googleButton.setEnabled(true);
                });
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }
    private void startXray() {
        try {
            Libv2ray.initCoreEnv(getFilesDir().getAbsolutePath(), "");
            controller = Libv2ray.newCoreController(callback);
            JSONObject root = configWithSavedSubscriptionIfAny();
            JSONObject log = root.optJSONObject("log");
            if (log == null) { log = new JSONObject(); root.put("log", log); }
            log.put("loglevel", "debug");
            log.put("access", new java.io.File(getFilesDir(), "access.log").getAbsolutePath());
            log.put("error", new java.io.File(getFilesDir(), "error.log").getAbsolutePath());
            controller.startLoop(root.toString(), 0);
            transferLog("I", "Xray started in-process; no inbound listener/TUN is created");
            runOnUiThread(() -> statusView.setText("Xray Core started (in-process; no TUN/local proxy)"));
        } catch (Throwable t) {
            coreStarted = false;
            Log.e(TAG, "Failed to start Xray", t);
            transferLog("E", "Xray startup failed: " + t.getMessage());
            runOnUiThread(() -> statusView.setText("Xray startup failed: " +
                    (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage())));
        }
    }

    private String readAsset(String path) throws Exception {
        try (InputStream in = getAssets().open(path); BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            String line; while ((line = br.readLine()) != null) out.append(line).append('\n');
            return out.toString();
        }
    }

    private void waitForXray() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!coreStarted && System.nanoTime() < deadline) Thread.sleep(100);
        if (!coreStarted) throw new IllegalStateException("Xray core did not start");
    }

    /**
     * Model transport using a real Xray-provided socket. No proxy listener,
     * localhost hop, or system-level tunnel is involved.
     */
    private String requestModel(String method, String urlText, String body, String headersJson) throws Exception {
        waitForXray();
        URL url = new URL(urlText);
        if (!(url.getProtocol().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Only HTTPS model URLs are supported by the in-process transport");
        }
        HttpsURLConnection c = openHttpsConnection(urlText, true);
        c.setRequestMethod(method == null ? "GET" : method.toUpperCase());
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(120000);
        c.setDoInput(true);
        c.setUseCaches(false);
        try {
            if (headersJson != null && !headersJson.isEmpty()) {
                JSONObject headers = new JSONObject(headersJson);
                java.util.Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    c.setRequestProperty(k, headers.optString(k, ""));
                }
            }
            if (body != null && !body.isEmpty() && !"GET".equalsIgnoreCase(method)) {
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line; while ((line = br.readLine()) != null) sb.append(line).append('\n');
                }
            }
            JSONObject out = new JSONObject();
            out.put("status", code);
            out.put("ok", code >= 200 && code < 300);
            out.put("body", sb.toString());
            out.put("contentType", c.getContentType() == null ? "" : c.getContentType());
            return out.toString();
        } finally { c.disconnect(); }
    }

    private static final class XraySocket extends Socket {
        private final XrayConnection connection;
        private final String host;
        private final int port;
        private volatile boolean closed;
        private int soTimeout;

        XraySocket(CoreController controller, String host, int port) throws Exception {
            long dialStarted = android.os.SystemClock.elapsedRealtime();
            Log.i(TAG, "XRAY_DIAL_BEGIN target=" + host + ":" + port + " dns=delegated-to-Xray-core");
            try {
                this.connection = controller.dial(host + ":" + port);
                Log.i(TAG, "XRAY_DIAL_SUCCESS target=" + host + ":" + port + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - dialStarted));
            } catch (Throwable dialError) {
                Log.e(TAG, "XRAY_DIAL_FAILED target=" + host + ":" + port + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - dialStarted) + " type=" + dialError.getClass().getSimpleName() + " message=" + dialError.getMessage(), dialError);
                if (dialError instanceof Exception) throw (Exception) dialError;
                throw new java.io.IOException("Xray dial failed: " + host + ":" + port, dialError);
            }
            this.host = host;
            this.port = port;
        }

        @Override public InputStream getInputStream() throws java.io.IOException {
            return new InputStream() {
                @Override public int read() throws java.io.IOException {
                    byte[] one = new byte[1];
                    int n = read(one, 0, 1);
                    return n < 0 ? -1 : (one[0] & 0xff);
                }
                @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
                    if (len == 0) return 0;
                    byte[] tmp = (off == 0 && len == b.length) ? b : new byte[len];
                    try {
                        long n = connection.read(tmp);
                        if (n > Integer.MAX_VALUE) throw new java.io.IOException("Xray read too large: " + n);
                        int count = (int) n;
                        if (tmp != b && count > 0) System.arraycopy(tmp, 0, b, off, count);
                        return count;
                    } catch (Exception e) { throw new java.io.IOException(e); }
                }
                @Override public void close() throws java.io.IOException { XraySocket.this.close(); }
            };
        }

        @Override public OutputStream getOutputStream() throws java.io.IOException {
            return new OutputStream() {
                @Override public void write(int b) throws java.io.IOException { write(new byte[]{(byte) b}, 0, 1); }
                @Override public void write(byte[] b, int off, int len) throws java.io.IOException {
                    if (len == 0) return;
                    byte[] tmp = (off == 0 && len == b.length) ? b : java.util.Arrays.copyOfRange(b, off, off + len);
                    try {
                        long written = connection.write(tmp);
                        if (written != len) throw new java.io.IOException("partial Xray write: " + written + "/" + len);
                    } catch (Exception e) { throw new java.io.IOException(e); }
                }
                @Override public void close() throws java.io.IOException { XraySocket.this.close(); }
            };
        }

        @Override public synchronized void close() throws java.io.IOException {
            if (closed) return;
            closed = true;
            try { connection.close(); } catch (Exception e) { throw new java.io.IOException(e); }
        }

        @Override public void setSoTimeout(int timeout) { soTimeout = timeout; }
        @Override public int getSoTimeout() { return soTimeout; }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isClosed() { return closed; }
        @Override public SocketAddress getRemoteSocketAddress() { return InetSocketAddress.createUnresolved(host, port); }
        @Override public void shutdownInput() throws java.io.IOException { close(); }
        @Override public void shutdownOutput() throws java.io.IOException { close(); }
        @Override public boolean isInputShutdown() { return closed; }
        @Override public boolean isOutputShutdown() { return closed; }
        @Override public void setTcpNoDelay(boolean on) { }
        @Override public boolean getTcpNoDelay() { return true; }
        @Override public void setKeepAlive(boolean on) { }
        @Override public boolean getKeepAlive() { return false; }
    }

    private static final class XrayHttpsURLConnection extends HttpsURLConnection {
        private final CoreController controller;
        private SSLSocketFactory sslFactory;
        private SSLSocket socket;
        private byte[] body = new byte[0];
        private int responseCode = -1;
        private String responseMessage = "";
        private final java.util.LinkedHashMap<String, String> requestHeaders = new java.util.LinkedHashMap<>();
        private final java.util.LinkedHashMap<String, String> responseHeaders = new java.util.LinkedHashMap<>();
        private java.io.ByteArrayOutputStream requestBodyStream = null;

        XrayHttpsURLConnection(URL url, CoreController controller) {
            super(url);
            this.controller = controller;
            this.sslFactory = null;
        }

        @Override public void setRequestMethod(String method) throws java.net.ProtocolException {
            if (connected) throw new IllegalStateException("Already connected");
            this.method = method.toUpperCase(java.util.Locale.ROOT);
        }

        @Override public void setRequestProperty(String key, String value) {
            if (connected) throw new IllegalStateException("Already connected");
            if (key != null) {
                if (value == null) requestHeaders.remove(key);
                else requestHeaders.put(key, value);
            }
        }

        @Override public void addRequestProperty(String key, String value) {
            if (connected) throw new IllegalStateException("Already connected");
            if (key != null && value != null) {
                requestHeaders.put(key, value);
            }
        }

        @Override public String getRequestProperty(String key) {
            if (key == null) return null;
            for (java.util.Map.Entry<String, String> e : requestHeaders.entrySet()) {
                if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
            }
            return null;
        }

        @Override public java.util.Map<String, java.util.List<String>> getRequestProperties() {
            java.util.Map<String, java.util.List<String>> map = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, String> e : requestHeaders.entrySet()) {
                map.put(e.getKey(), java.util.Collections.singletonList(e.getValue()));
            }
            return java.util.Collections.unmodifiableMap(map);
        }

        @Override public OutputStream getOutputStream() throws java.io.IOException {
            if (connected) throw new java.io.IOException("Already connected");
            if (!doOutput) throw new java.net.ProtocolException("cannot write to a URLConnection if doOutput=false");
            if (requestBodyStream == null) {
                requestBodyStream = new java.io.ByteArrayOutputStream();
            }
            return requestBodyStream;
        }

        @Override public void connect() throws java.io.IOException {
            if (connected) return;
            try {
                int port = url.getPort() > 0 ? url.getPort() : 443;
                Log.d(TAG, "XRAY_HTTP connect " + url.getHost() + ":" + port);
                if (sslFactory == null) sslFactory = new XraySSLSocketFactory(controller);
                socket = (SSLSocket) sslFactory.createSocket(url.getHost(), port);
                int timeout = getConnectTimeout() > 0 ? getConnectTimeout() : 30000;
                int readTimeout = getReadTimeout() > 0 ? getReadTimeout() : 30000;
                socket.setSoTimeout(readTimeout);
                socket.startHandshake();
                if (getHostnameVerifier() != null &&
                        !getHostnameVerifier().verify(url.getHost(), socket.getSession())) {
                    throw new javax.net.ssl.SSLPeerUnverifiedException("Hostname not verified: " + url.getHost());
                }
                OutputStream out = socket.getOutputStream();
                String path = url.getFile();
                if (path == null || path.isEmpty()) path = "/";

                byte[] outBytes = (requestBodyStream != null) ? requestBodyStream.toByteArray() : new byte[0];
                String reqMethod = (method != null && !method.isEmpty()) ? method : (outBytes.length > 0 ? "POST" : "GET");

                StringBuilder headerBuilder = new StringBuilder();
                headerBuilder.append(reqMethod).append(" ").append(path).append(" HTTP/1.1\r\n");
                if (getRequestProperty("Host") == null) {
                    headerBuilder.append("Host: ").append(url.getHost()).append("\r\n");
                }
                if (getRequestProperty("User-Agent") == null) {
                    headerBuilder.append("User-Agent: Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36\r\n");
                }
                if (getRequestProperty("Accept-Encoding") == null) {
                    headerBuilder.append("Accept-Encoding: identity\r\n");
                }
                if (getRequestProperty("Connection") == null) {
                    headerBuilder.append("Connection: close\r\n");
                }
                if (outBytes.length > 0 && getRequestProperty("Content-Length") == null) {
                    headerBuilder.append("Content-Length: ").append(outBytes.length).append("\r\n");
                }
                for (java.util.Map.Entry<String, String> e : requestHeaders.entrySet()) {
                    headerBuilder.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
                }
                headerBuilder.append("\r\n");

                out.write(headerBuilder.toString().getBytes(StandardCharsets.ISO_8859_1));
                if (outBytes.length > 0) {
                    out.write(outBytes);
                }
                out.flush();

                InputStream in = socket.getInputStream();
                parseResponse(in);
                connected = true;
                Log.d(TAG, "XRAY_HTTP connected response=" + responseCode + " body=" + body.length);
            } catch (SSLPeerUnverifiedException e) {
                throw e;
            } catch (Exception e) {
                throw new java.io.IOException("Xray HTTPS transport failed: " + e.getMessage(), e);
            }
        }

        private static String readAsciiLine(InputStream in) throws java.io.IOException {
            java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') break;
                if (b != '\r') line.write(b);
            }
            if (b == -1 && line.size() == 0) return null;
            return line.toString(StandardCharsets.ISO_8859_1.name());
        }

        private void parseResponse(InputStream rawIn) throws java.io.IOException {
            BufferedInputStream in = new BufferedInputStream(rawIn, 8192);
            String status = readAsciiLine(in);
            if (status == null) throw new java.io.EOFException("empty HTTP response");
            String[] parts = status.split(" ", 3);
            if (parts.length < 2) throw new java.io.IOException("bad HTTP status: " + status);
            responseCode = Integer.parseInt(parts[1]);
            responseMessage = parts.length >= 3 ? parts[2] : "";
            String line;
            while ((line = readAsciiLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) responseHeaders.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
            String transfer = getHeader("Transfer-Encoding");
            String length = getHeader("Content-Length");
            java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
            final int MAX_BODY = 10 * 1024 * 1024;
            if (transfer != null && transfer.toLowerCase(java.util.Locale.US).contains("chunked")) {
                while (true) {
                    String sizeLine = readAsciiLine(in);
                    if (sizeLine == null) break;
                    sizeLine = sizeLine.trim();
                    if (sizeLine.isEmpty()) continue;
                    int semi = sizeLine.indexOf(';');
                    if (semi >= 0) sizeLine = sizeLine.substring(0, semi).trim();
                    int size = Integer.parseInt(sizeLine, 16);
                    if (size == 0) {
                        while (true) {
                            String trail = readAsciiLine(in);
                            if (trail == null || trail.isEmpty()) break;
                        }
                        break;
                    }
                    int rem = size;
                    byte[] buf = new byte[Math.min(4096, rem)];
                    while (rem > 0) {
                        int n = in.read(buf, 0, Math.min(buf.length, rem));
                        if (n < 0) throw new java.io.EOFException("premature EOF reading chunk data");
                        if (data.size() < MAX_BODY) {
                            data.write(buf, 0, Math.min(n, MAX_BODY - data.size()));
                        }
                        rem -= n;
                    }
                    readAsciiLine(in);
                    if (data.size() >= MAX_BODY) break;
                }
            } else if (length != null) {
                long wanted = Long.parseLong(length.trim());
                long remaining = Math.min(wanted, (long) MAX_BODY);
                byte[] buf = new byte[4096];
                while (remaining > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (n < 0) break;
                    data.write(buf, 0, n);
                    remaining -= n;
                }
            } else {
                byte[] buf = new byte[4096];
                int n;
                while (data.size() < MAX_BODY && (n = in.read(buf, 0, Math.min(buf.length, MAX_BODY - data.size()))) != -1) {
                    data.write(buf, 0, n);
                }
            }
            byte[] rawBytes = data.toByteArray();
            String contentEncoding = getHeader("Content-Encoding");
            if (contentEncoding != null && contentEncoding.toLowerCase(java.util.Locale.US).contains("gzip")) {
                try (java.util.zip.GZIPInputStream gzin = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(rawBytes));
                     java.io.ByteArrayOutputStream decompressed = new java.io.ByteArrayOutputStream()) {
                    byte[] gbuf = new byte[4096];
                    int gn;
                    while ((gn = gzin.read(gbuf)) != -1) {
                        decompressed.write(gbuf, 0, gn);
                    }
                    body = decompressed.toByteArray();
                } catch (Throwable e) {
                    body = rawBytes;
                }
            } else {
                body = rawBytes;
            }
        }

        private String getHeader(String name) {
            for (java.util.Map.Entry<String, String> e : responseHeaders.entrySet()) if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
            return null;
        }
        @Override public int getResponseCode() throws java.io.IOException { connect(); return responseCode; }
        @Override public String getResponseMessage() throws java.io.IOException { connect(); return responseMessage; }
        @Override public InputStream getInputStream() throws java.io.IOException { connect(); return new java.io.ByteArrayInputStream(body); }
        @Override public InputStream getErrorStream() { try { connect(); return new java.io.ByteArrayInputStream(body); } catch (Exception e) { return null; } }
        @Override public String getHeaderField(String name) { return getHeader(name); }
        @Override public String getContentType() { return getHeader("Content-Type"); }
        @Override public int getContentLength() { try { String v = getHeader("Content-Length"); return v == null ? -1 : Integer.parseInt(v); } catch (Exception e) { return -1; } }
        @Override public long getContentLengthLong() { try { String v = getHeader("Content-Length"); return v == null ? -1 : Long.parseLong(v); } catch (Exception e) { return -1; } }
        @Override public java.util.Map<String, java.util.List<String>> getHeaderFields() {
            java.util.Map<String, java.util.List<String>> map = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, String> e : responseHeaders.entrySet()) {
                map.put(e.getKey(), java.util.Collections.singletonList(e.getValue()));
            }
            return java.util.Collections.unmodifiableMap(map);
        }
        @Override public String getCipherSuite() { return socket == null ? null : socket.getSession().getCipherSuite(); }
        @Override public Certificate[] getLocalCertificates() { return socket == null ? null : socket.getSession().getLocalCertificates(); }
        @Override public Certificate[] getServerCertificates() throws SSLPeerUnverifiedException { if (socket == null) throw new IllegalStateException("not connected"); return socket.getSession().getPeerCertificates(); }
        @Override public Principal getPeerPrincipal() throws SSLPeerUnverifiedException { if (socket == null) throw new IllegalStateException("not connected"); return socket.getSession().getPeerPrincipal(); }
        @Override public Principal getLocalPrincipal() { return socket == null ? null : socket.getSession().getLocalPrincipal(); }
        @Override public void disconnect() { if (socket != null) try { socket.close(); } catch (Exception ignored) {} connected = false; }
        @Override public boolean usingProxy() { return false; }
        @Override public void setSSLSocketFactory(SSLSocketFactory sf) { if (sf == null) throw new IllegalArgumentException("sf == null"); sslFactory = sf; }
        @Override public SSLSocketFactory getSSLSocketFactory() { return sslFactory; }
    }

    private static final class XraySSLSocketFactory extends SSLSocketFactory {
        private final CoreController controller;
        private final SSLSocketFactory delegate;

        XraySSLSocketFactory(CoreController controller) throws Exception {
            this.controller = controller;
            this.delegate = SSLContext.getDefault().getSocketFactory();
        }

        private Socket layer(String host, int port) throws java.io.IOException {
            try {
                return delegate.createSocket(new XraySocket(controller, host, port), host, port, true);
            } catch (Exception e) {
                throw new java.io.IOException("Xray dial failed for " + host + ":" + port, e);
            }
        }

        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket(String host, int port) throws java.io.IOException { return layer(host, port); }
        @Override public Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort) throws java.io.IOException { return layer(host, port); }
        @Override public Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException { return layer(host.getHostName(), port); }
        @Override public Socket createSocket(java.net.InetAddress address, int port, java.net.InetAddress localAddress, int localPort) throws java.io.IOException { return layer(address.getHostName(), port); }
        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws java.io.IOException {
            // OkHttp reaches this overload with a plain socket; never let it bypass Xray.
            return layer(host, port);
        }
    }

    public class RuntimeBridge {
        public String getConfig() {
            JSONObject out = readSecureConfig();
            return out == null ? "{}" : out.toString();
        }
        public void setConfig(String json) {
            try { writeSecureConfig(normalizeConfig(new JSONObject(json == null ? "{}" : json))); }
            catch (Exception e) { transferLog("E", "setConfig failed: " + e.getMessage()); }
        }
        public String requestModel(String method, String url, String body, String headersJson) {
            try { return MainActivity.this.requestModel(method, url, body, headersJson); }
            catch (Throwable t) {
                Log.e(TAG, "model request failed", t);
                JSONObject out = new JSONObject();
                try { out.put("status", 599).put("ok", false).put("body", t.getClass().getSimpleName() + ": " + (t.getMessage() == null ? "unknown error" : t.getMessage())); } catch (Exception ignored) {}
                return out.toString();
            }
        }
        public void importConfig() {
            runOnUiThread(() -> {
                final EditText input = new EditText(MainActivity.this);
                input.setSingleLine(true);
                input.setHint("粘贴电脑端配置迁移地址");
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("导入电脑配置")
                        .setMessage("把电脑端“导出配置”生成的 http(s) 地址粘贴到这里。")
                        .setView(input)
                        .setNegativeButton("取消", null)
                        .setPositiveButton("连接", (dialog, which) -> onScannedContents(input.getText().toString()))
                        .show();
            });
        }
        public String getTransferDebugLog() { return MainActivity.this.getTransferDebugLog(); }
        public void clearTransferDebugLog() { MainActivity.this.clearTransferDebugLog(); }
        public void appendTransferDebugLog(String message) { transferLog("I", message); }
        public void clearConfig() {
            prefs.edit().clear().apply();
            try { KeyStore ks = KeyStore.getInstance(KEYSTORE); ks.load(null); if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS); } catch (Exception ignored) {}
        }
    }

    private JSONObject normalizeConfig(JSONObject in) throws Exception {
        JSONObject out = new JSONObject();
        out.put("url", in.optString("url", ""));
        out.put("modelsUrl", in.optString("modelsUrl", ""));
        out.put("apiKey", in.optString("apiKey", ""));
        out.put("model", in.optString("model", ""));
        return out;
    }
    private SecretKey getLocalKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE); ks.load(null);
        if (!ks.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance("AES", KEYSTORE);
            generator.init(new android.security.keystore.KeyGenParameterSpec.Builder(KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
    }
    private void writeSecureConfig(JSONObject config) throws Exception {
        SecretKey key = getLocalKey(); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key);
        prefs.edit().putString(SECURE_VALUE, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(config.toString().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP)).apply();
    }
    private JSONObject readSecureConfig() {
        try {
            String value = prefs.getString(SECURE_VALUE, ""); if (value.isEmpty()) return null;
            String[] parts = value.split(":", 2); SecretKey key = getLocalKey(); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.decode(parts[0], Base64.DEFAULT)));
            return new JSONObject(new String(cipher.doFinal(Base64.decode(parts[1], Base64.DEFAULT)), StandardCharsets.UTF_8));
        } catch (Exception e) { return null; }
    }

    private void transferLog(String level, String message) {
        String line = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()) + " " + level + " " + message;
        synchronized (transferDebugLog) { while (transferDebugLog.size() >= 100) transferDebugLog.removeFirst(); transferDebugLog.addLast(line); }
        if ("E".equals(level)) Log.e(TAG, message); else if ("W".equals(level)) Log.w(TAG, message); else Log.i(TAG, message);
    }
    private String getTransferDebugLog() {
        StringBuilder out = new StringBuilder(); synchronized (transferDebugLog) { for (String x : transferDebugLog) out.append(x).append('\n'); }
        JSONObject c = readSecureConfig(); out.append("保存的配置：").append(c == null ? "无" : "已保存");
        if (c != null) out.append("；URL=").append(c.optString("url", "")).append("；Models URL=").append(c.optString("modelsUrl", "")).append("；Model=").append(c.optString("model", "")).append("；API Key=").append(!c.optString("apiKey", "").isEmpty());
        out.append('\n'); return out.toString();
    }
    private void clearTransferDebugLog() { synchronized (transferDebugLog) { transferDebugLog.clear(); } transferLog("I", "诊断日志已清空"); }

    private static class HttpResult { int code; JSONObject json; HttpResult(int c, JSONObject j){ code=c; json=j; } }
    private HttpResult requestDirect(String method, String urlText, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlText).openConnection(); c.setRequestMethod(method); c.setConnectTimeout(TIMEOUT_MS); c.setReadTimeout(TIMEOUT_MS); c.setDoInput(true);
        if (body != null) { c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json"); try (OutputStream out=c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); } }
        int code=c.getResponseCode(); InputStream in=code>=400?c.getErrorStream():c.getInputStream(); StringBuilder sb=new StringBuilder(); if(in!=null){try(BufferedReader br=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String line;while((line=br.readLine())!=null)sb.append(line);}} c.disconnect(); return new HttpResult(code,new JSONObject(sb.length()==0?"{}":sb.toString()));
    }
    private void onScannedContents(String contents) {
        String scanned = contents == null ? "" : contents.trim(); Uri uri=Uri.parse(scanned); String scheme=uri.getScheme(), host=uri.getHost(); int port=uri.getPort(); String token=uri.getQueryParameter("t");
        if (!("http".equalsIgnoreCase(scheme)||"https".equalsIgnoreCase(scheme)) || host==null || host.isEmpty() || token==null || token.length()<32) { Toast.makeText(this,"二维码不是有效的配置迁移地址",Toast.LENGTH_SHORT).show(); return; }
        transferToken=token; try { KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA"); gen.initialize(2048); KeyPair pair=gen.generateKeyPair(); transferPrivateKey=pair.getPrivate(); String publicKey=Base64.encodeToString(pair.getPublic().getEncoded(),Base64.NO_WRAP); String base=scheme+"://"+host+(port==-1?"":":"+port); joinTransfer(base,token,publicKey); } catch(Exception e){ showError("无法建立安全连接："+e.getMessage()); }
    }
    private void joinTransfer(String base,String token,String publicKey){ io.execute(()->{try{JSONObject body=new JSONObject();body.put("token",token);body.put("publicKey",publicKey);HttpResult r=requestDirect("POST",base+"/api/config-transfer/join",body.toString());if(r.code/100!=2)throw new Exception(r.json.optString("error","连接失败"));String code=r.json.optString("code","");runOnUiThread(()->showPairingCode(code));pollForConfig(base,token);}catch(Exception e){transferLog("E","Join failed: "+e);showError("连接失败："+e.getMessage());}});}
    private void showPairingCode(String code){ if(pairingDialog!=null)pairingDialog.dismiss(); pairingDialog=new AlertDialog.Builder(this).setTitle("检测到电脑配置迁移").setMessage("连接验证码\n\n"+code+"\n\n请在电脑端输入这个验证码。").setNegativeButton("取消",(d,w)->{transferToken=null;transferPrivateKey=null;}).create(); pairingDialog.show(); }
    private void pollForConfig(String base,String token){for(int i=0;i<300&&token.equals(transferToken);i++){try{Thread.sleep(1000);HttpResult r=requestDirect("GET",base+"/api/config-transfer/config?t="+Uri.encode(token),null);if(r.code==200){JSONObject config=decryptTransfer(r.json);writeSecureConfig(normalizeConfig(config));runOnUiThread(()->{if(pairingDialog!=null){pairingDialog.dismiss();pairingDialog=null;}transferToken=null;transferPrivateKey=null;Toast.makeText(this,"配置导入成功",Toast.LENGTH_SHORT).show();});return;}if(r.code==410||r.code==404){showError("迁移会话已过期，请重新扫码");return;}}catch(Exception e){showError("配置导入失败："+e.getMessage());return;}}}
    private JSONObject decryptTransfer(JSONObject encrypted)throws Exception{byte[] encryptedKey=Base64.decode(encrypted.getString("encryptedKey"),Base64.DEFAULT);OAEPParameterSpec oaep=new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT);Cipher rsa=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");rsa.init(Cipher.DECRYPT_MODE,transferPrivateKey,oaep);byte[] aesKey=rsa.doFinal(encryptedKey);Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");byte[] iv=Base64.decode(encrypted.getString("iv"),Base64.DEFAULT);byte[] tag=Base64.decode(encrypted.getString("tag"),Base64.DEFAULT);byte[] ciphertext=Base64.decode(encrypted.getString("ciphertext"),Base64.DEFAULT);byte[] combined=new byte[ciphertext.length+tag.length];System.arraycopy(ciphertext,0,combined,0,ciphertext.length);System.arraycopy(tag,0,combined,ciphertext.length,tag.length);aes.init(Cipher.DECRYPT_MODE,new javax.crypto.spec.SecretKeySpec(aesKey,"AES"),new GCMParameterSpec(128,iv));return new JSONObject(new String(aes.doFinal(combined),StandardCharsets.UTF_8));}
    private void showError(String message){runOnUiThread(()->Toast.makeText(this,message,Toast.LENGTH_LONG).show());}
    @Override protected void onDestroy(){
        stopAudioPlayback();
        if (mediaProxyServer != null) {
            mediaProxyServer.stop();
            mediaProxyServer = null;
        }
        io.shutdownNow();
        transferPrivateKey=null;
        transferToken=null;
        if(pairingDialog!=null)pairingDialog.dismiss();
        if(controller!=null){try{controller.stopLoop();}catch(Throwable ignored){}}
        controller=null;
        coreStarted=false;
        super.onDestroy();
    }

    private LinearLayout createPlayerBarView() {
        if (playerBarView != null && playerBarView.getParent() != null) {
            ((ViewGroup) playerBarView.getParent()).removeView(playerBarView);
        }
        playerBarView = new LinearLayout(this);
        playerBarView.setOrientation(LinearLayout.VERTICAL);
        playerBarView.setBackgroundColor(Color.rgb(238, 242, 246));
        playerBarView.setPadding(dp(12), dp(8), dp(12), dp(10));
        playerBarView.setVisibility(currentPlayingUrl.isEmpty() ? View.GONE : View.VISIBLE);

        // Top row: Title + Close Button
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        playerTitleView = new TextView(this);
        playerTitleView.setText(currentPlayingTitle.isEmpty() ? "未在播放" : currentPlayingTitle);
        playerTitleView.setTextSize(13);
        playerTitleView.setTextColor(Color.rgb(20, 30, 45));
        playerTitleView.setSingleLine(true);
        playerTitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        topRow.addView(playerTitleView, titleParams);

        Button closeBtn = new Button(this);
        closeBtn.setText("✕");
        closeBtn.setTextSize(12);
        closeBtn.setPadding(dp(6), dp(0), dp(6), dp(0));
        closeBtn.setOnClickListener(v -> stopAudioPlayback());
        topRow.addView(closeBtn);

        playerBarView.addView(topRow);

        // Middle row: SeekBar + Time label
        LinearLayout progressRow = new LinearLayout(this);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);

        playerSeekBar = new SeekBar(this);
        playerSeekBar.setMax(1000);
        playerSeekBar.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        playerSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mediaPlayer != null) {
                    try {
                        int duration = mediaPlayer.getDuration();
                        if (duration > 0) {
                            int newPos = (int) ((progress / 1000.0) * duration);
                            playerTimeView.setText(formatDuration(newPos / 1000.0) + " / " + formatDuration(duration / 1000.0));
                        }
                    } catch (Exception ignored) {}
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isUserTrackingSeekBar = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                isUserTrackingSeekBar = false;
                if (mediaPlayer != null) {
                    try {
                        int duration = mediaPlayer.getDuration();
                        if (duration > 0) {
                            int targetPos = (int) ((seekBar.getProgress() / 1000.0) * duration);
                            mediaPlayer.seekTo(targetPos);
                        }
                    } catch (Exception ignored) {}
                }
            }
        });
        progressRow.addView(playerSeekBar);

        playerTimeView = new TextView(this);
        playerTimeView.setText("00:00 / 00:00");
        playerTimeView.setTextSize(11);
        playerTimeView.setTextColor(Color.DKGRAY);
        playerTimeView.setPadding(dp(6), 0, dp(4), 0);
        progressRow.addView(playerTimeView);

        playerBarView.addView(progressRow);

        // Bottom row: Controls (Rewind 15s, Play/Pause, Forward 15s)
        LinearLayout controlsRow = new LinearLayout(this);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER);

        Button rewBtn = new Button(this);
        rewBtn.setText("-15s");
        rewBtn.setTextSize(12);
        rewBtn.setPadding(dp(12), dp(2), dp(12), dp(2));
        rewBtn.setOnClickListener(v -> {
            if (mediaPlayer != null) {
                try {
                    int pos = Math.max(0, mediaPlayer.getCurrentPosition() - 15000);
                    mediaPlayer.seekTo(pos);
                    updatePlayerProgress();
                } catch (Exception ignored) {}
            }
        });
        controlsRow.addView(rewBtn);

        playerPlayPauseBtn = new Button(this);
        playerPlayPauseBtn.setText((mediaPlayer != null && mediaPlayer.isPlaying()) ? "暂停" : "播放");
        playerPlayPauseBtn.setTextSize(12);
        playerPlayPauseBtn.setPadding(dp(16), dp(2), dp(16), dp(2));
        playerPlayPauseBtn.setOnClickListener(v -> toggleAudioPlayback());
        controlsRow.addView(playerPlayPauseBtn);

        Button fwdBtn = new Button(this);
        fwdBtn.setText("+15s");
        fwdBtn.setTextSize(12);
        fwdBtn.setPadding(dp(12), dp(2), dp(12), dp(2));
        fwdBtn.setOnClickListener(v -> {
            if (mediaPlayer != null) {
                try {
                    int duration = mediaPlayer.getDuration();
                    int pos = Math.min(duration > 0 ? duration : Integer.MAX_VALUE, mediaPlayer.getCurrentPosition() + 15000);
                    mediaPlayer.seekTo(pos);
                    updatePlayerProgress();
                } catch (Exception ignored) {}
            }
        });
        controlsRow.addView(fwdBtn);

        playerBarView.addView(controlsRow);
        return playerBarView;
    }

    private void playAudioInApp(String title, String url) {
        if (title == null) title = "音频播放";
        currentPlayingTitle = title;
        currentPlayingUrl = url;

        if (playerBarView != null) {
            playerBarView.setVisibility(View.VISIBLE);
        }
        if (playerTitleView != null) {
            playerTitleView.setText("加载中: " + title);
        }
        if (playerPlayPauseBtn != null) {
            playerPlayPauseBtn.setText("加载中");
            playerPlayPauseBtn.setEnabled(false);
        }
        if (playerTimeView != null) {
            playerTimeView.setText("00:00 / 00:00");
        }
        if (playerSeekBar != null) {
            playerSeekBar.setProgress(0);
        }

        io.execute(() -> {
            try {
                if (mediaProxyServer == null) {
                    mediaProxyServer = new LocalMediaProxyServer(this);
                    mediaProxyServer.start();
                }
                String playbackUrl = mediaProxyServer.getProxyUrl(url);

                runOnUiThread(() -> {
                    try {
                        if (mediaPlayer != null) {
                            try {
                                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                            } catch (Exception ignored) {}
                            mediaPlayer.reset();
                            mediaPlayer.release();
                            mediaPlayer = null;
                        }

                        mediaPlayer = new MediaPlayer();
                        mediaPlayer.setAudioAttributes(
                                new AudioAttributes.Builder()
                                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .build()
                        );
                        mediaPlayer.setDataSource(this, Uri.parse(playbackUrl));

                        isPreparingMedia = true;
                        mediaPlayer.setOnPreparedListener(mp -> {
                            isPreparingMedia = false;
                            mp.start();
                            if (playerPlayPauseBtn != null) {
                                playerPlayPauseBtn.setText("暂停");
                                playerPlayPauseBtn.setEnabled(true);
                            }
                            if (playerTitleView != null) {
                                playerTitleView.setText("▶ " + currentPlayingTitle);
                            }
                            startProgressUpdater();
                            Toast.makeText(this, "正在播放: " + currentPlayingTitle, Toast.LENGTH_SHORT).show();
                        });

                        mediaPlayer.setOnCompletionListener(mp -> {
                            if (playerPlayPauseBtn != null) {
                                playerPlayPauseBtn.setText("播放");
                            }
                            if (playerTitleView != null) {
                                playerTitleView.setText("已播完: " + currentPlayingTitle);
                            }
                            stopProgressUpdater();
                        });

                        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                            isPreparingMedia = false;
                            Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
                            Toast.makeText(this, "音频播放出错 (" + what + ", " + extra + ")", Toast.LENGTH_LONG).show();
                            if (playerPlayPauseBtn != null) {
                                playerPlayPauseBtn.setText("重试");
                                playerPlayPauseBtn.setEnabled(true);
                            }
                            if (playerTitleView != null) {
                                playerTitleView.setText("播放出错: " + currentPlayingTitle);
                            }
                            stopProgressUpdater();
                            return true;
                        });

                        mediaPlayer.prepareAsync();
                    } catch (Throwable e) {
                        Log.e(TAG, "Failed to start media player", e);
                        Toast.makeText(this, "无法初始化播放器: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        if (playerPlayPauseBtn != null) {
                            playerPlayPauseBtn.setText("重试");
                            playerPlayPauseBtn.setEnabled(true);
                        }
                    }
                });
            } catch (Throwable e) {
                Log.e(TAG, "Proxy setup failed", e);
                runOnUiThread(() -> Toast.makeText(this, "本地媒体代理启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void toggleAudioPlayback() {
        if (mediaPlayer == null) {
            if (!currentPlayingUrl.isEmpty()) {
                playAudioInApp(currentPlayingTitle, currentPlayingUrl);
            }
            return;
        }
        if (isPreparingMedia) return;
        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                if (playerPlayPauseBtn != null) playerPlayPauseBtn.setText("播放");
                if (playerTitleView != null) playerTitleView.setText("❚❚ " + currentPlayingTitle);
            } else {
                mediaPlayer.start();
                if (playerPlayPauseBtn != null) playerPlayPauseBtn.setText("暂停");
                if (playerTitleView != null) playerTitleView.setText("▶ " + currentPlayingTitle);
                startProgressUpdater();
            }
        } catch (Exception e) {
            Log.e(TAG, "toggleAudioPlayback failed", e);
        }
    }

    private void stopAudioPlayback() {
        stopProgressUpdater();
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            } catch (Exception ignored) {}
            try {
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        isPreparingMedia = false;
        currentPlayingUrl = "";
        currentPlayingTitle = "";
        if (playerBarView != null) {
            playerBarView.setVisibility(View.GONE);
        }
    }

    private void startProgressUpdater() {
        stopProgressUpdater();
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                updatePlayerProgress();
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    progressHandler.postDelayed(this, 500);
                }
            }
        };
        progressHandler.post(progressRunnable);
    }

    private void stopProgressUpdater() {
        if (progressRunnable != null) {
            progressHandler.removeCallbacks(progressRunnable);
            progressRunnable = null;
        }
    }

    private void updatePlayerProgress() {
        if (mediaPlayer == null || isPreparingMedia) return;
        try {
            int current = mediaPlayer.getCurrentPosition();
            int duration = mediaPlayer.getDuration();
            if (duration > 0 && !isUserTrackingSeekBar && playerSeekBar != null) {
                int progress = (int) (((long) current * 1000) / duration);
                playerSeekBar.setProgress(progress);
            }
            if (playerTimeView != null) {
                playerTimeView.setText(formatDuration(current / 1000.0) + " / " + formatDuration(duration > 0 ? duration / 1000.0 : 0));
            }
        } catch (Exception ignored) {}
    }
}




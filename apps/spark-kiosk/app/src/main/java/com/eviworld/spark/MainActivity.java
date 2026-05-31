package com.eviworld.spark;

import android.Manifest;
import android.app.Activity;
import android.app.UiModeManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final String SPARK_URL = "https://spark.eviworld.com/";
    private static final String SPARK_HOST = "spark.eviworld.com";
    private static final String SPARK_OS_USER_AGENT_CAPABILITIES = "SparkOS/1 SparkFileAccess/none";
    private static final String PREFS = "spark_prefs";
    private static final String PREF_BRIGHTNESS = "brightness";
    private static final String PREF_ROTATE = "rotate";
    private static final String PREF_DARK = "dark";
    private static final String PREF_TRANSPORT_LOCKED = "transport_locked";
    private static final String PREF_USER_ROTATION = "user_rotation";
    private static final String PREF_LOCKED_ORIENTATION = "locked_orientation";
    private static final String PREF_LOCKED_ORIENTATION_ROTATION = "locked_orientation_rotation";
    private static final int FILE_CHOOSER_REQUEST = 4100;
    private static final long INACTIVITY_TIMEOUT_MS = 120000L;
    private static final long FIRST_LOAD_RETRY_MS = 5000L;
    private static final long FIRST_LOAD_IN_PROGRESS_TIMEOUT_MS = 30000L;

    private WebView webView;
    private FrameLayout root;
    private FrameLayout webContainer;
    private FrameLayout menuScrim;
    private FrameLayout controlsSheet;
    private LinearLayout controlsPanel;
    private BrightnessIconView brightnessIcon;
    private IconButtonView rotateButton;
    private IconButtonView darkButton;
    private IconButtonView reloadButton;
    private GripperView controlsGripper;
    private FrameLayout lockOverlay;
    private ProgressBar progress;
    private SharedPreferences prefs;
    private DevicePolicyManager policyManager;
    private ComponentName admin;
    private BroadcastReceiver unlockReceiver;
    private BroadcastReceiver statusReceiver;
    private BroadcastReceiver screenReceiver;
    private ConnectivityManager.NetworkCallback networkCallback;
    private TextView timeView;
    private TextView dateView;
    private WifiStatusView wifiView;
    private BatteryStatusView batteryView;
    private Handler handler;
    private Runnable clockTicker;
    private Runnable inactivityRunnable;
    private Runnable firstLoadRetry;
    private float touchStartY;
    private float lockTouchStartY;
    private boolean topSwipeCandidate;
    private boolean controlsDragging;
    private float controlsSheetTouchStartY;
    private boolean transportLocked;
    private boolean controlsPanelOpen;
    private boolean sparkPageLoaded;
    private boolean loadWaitingForConnectivity;
    private boolean offlinePageShowing;
    private boolean sparkLoadInProgress;
    private long lastSparkLoadAttemptAt;
    private boolean suspendAutoRotationForLock;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraImageUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context storageContext = createDeviceProtectedStorageContext();
        storageContext.moveSharedPreferencesFrom(this, PREFS);
        prefs = storageContext.getSharedPreferences(PREFS, MODE_PRIVATE);
        policyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        admin = new ComponentName(this, SparkDeviceAdminReceiver.class);
        handler = new Handler(Looper.getMainLooper());

        configureWindow();
        applyLaunchOrientation();
        if (!isUserUnlocked()) {
            buildSplashOnlyLayout();
            applyDeviceOwnerPolicy();
            registerUnlockReceiver();
            return;
        }

        buildLayout();
        configureWebView();
        applySavedControls();
        setTransportLocked(prefs.getBoolean(PREF_TRANSPORT_LOCKED, false), false);
        applyDeviceOwnerPolicy();
        registerStatusReceiver();
        registerNetworkCallback();
        registerScreenReceiver();
        updateStatusBar();
        startClockTicker();

        if (savedInstanceState == null) {
            loadSparkWhenPossible();
        } else {
            webView.restoreState(savedInstanceState);
        }
        startFirstLoadRetry();
        resetInactivityTimer();
    }

    private void applyLaunchOrientation() {
        if (prefs == null || prefs.getBoolean(PREF_ROTATE, true)) {
            return;
        }
        int rotation = prefs.getInt(PREF_USER_ROTATION, Surface.ROTATION_0);
        int orientation = lockedOrientationForRotation(rotation);
        setRequestedOrientation(orientation);
        if (Settings.System.canWrite(this)) {
            try {
                Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
                Settings.System.putInt(getContentResolver(), Settings.System.USER_ROTATION, rotation);
            } catch (SecurityException ignored) {
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        startKioskLock();
        setTransportLocked(prefs.getBoolean(PREF_TRANSPORT_LOCKED, false), false);
        enforceSavedOrientationIfRotationLocked();
        scheduleHideSystemBars();
        updateStatusBar();
        if (!sparkPageLoaded) {
            retryFirstLoadNow();
            startFirstLoadRetry();
        }
        resetInactivityTimer();
    }

    @Override
    protected void onPause() {
        enforceSavedOrientationIfRotationLocked();
        super.onPause();
    }

    @Override
    protected void onStop() {
        enforceSavedOrientationIfRotationLocked();
        super.onStop();
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        resetInactivityTimer();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enforceSavedOrientationIfRotationLocked();
            updateControlsSheetLayout();
            hideSystemBars();
            scheduleHideSystemBars();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (controlsPanelOpen) {
            controlsDragging = false;
            topSwipeCandidate = false;
            return super.dispatchTouchEvent(event);
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touchStartY = event.getY();
            topSwipeCandidate = touchStartY <= getStatusBarHeight() + dp(8);
            controlsDragging = false;
        } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float drag = event.getY() - touchStartY;
            if (topSwipeCandidate && drag > dp(12)) {
                controlsDragging = true;
                setControlsPanelProgress(Math.min(1f, drag / getControlsPanelDragHeight()));
                return true;
            }
            if (controlsDragging) {
                setControlsPanelProgress(Math.min(1f, Math.max(0f, drag / getControlsPanelDragHeight())));
                return true;
            }
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (controlsDragging) {
                float drag = event.getY() - touchStartY;
                if (drag >= getControlsPanelDragHeight() * 0.46f) {
                    showMenu();
                } else {
                    hideMenu();
                }
                controlsDragging = false;
                topSwipeCandidate = false;
                return true;
            }
            topSwipeCandidate = false;
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            if (controlsDragging) {
                hideMenu();
            }
            controlsDragging = false;
            topSwipeCandidate = false;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            resetInactivityTimer();
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) {
            webView.saveState(outState);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        if (menuScrim != null && menuScrim.getVisibility() == View.VISIBLE) {
            hideMenu();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            Uri[] result = null;
            if (resultCode == RESULT_OK) {
                if (data == null || data.getData() == null) {
                    if (cameraImageUri != null) {
                        result = new Uri[]{cameraImageUri};
                    }
                } else {
                    result = readSelectedUris(data);
                }
            }
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(result);
                filePathCallback = null;
            }
            if (resultCode != RESULT_OK && cameraImageUri != null) {
                try {
                    getContentResolver().delete(cameraImageUri, null, null);
                } catch (RuntimeException ignored) {
                }
            }
            cameraImageUri = null;
            hideSystemBars();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void configureWindow() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        hideSystemBars();
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(
                new View.OnSystemUiVisibilityChangeListener() {
                    @Override
                    public void onSystemUiVisibilityChange(int visibility) {
                        scheduleHideSystemBars();
                    }
                });
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        }
    }

    private void scheduleHideSystemBars() {
        View decor = getWindow().getDecorView();
        decor.postDelayed(new Runnable() {
            @Override
            public void run() {
                hideSystemBars();
            }
        }, 300);
        decor.postDelayed(new Runnable() {
            @Override
            public void run() {
                hideSystemBars();
            }
        }, 1200);
    }

    private void buildSplashOnlyLayout() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(8, 11, 18));

        ImageView splash = new ImageView(this);
        splash.setImageResource(getResources().getIdentifier("spark_splash", "drawable", getPackageName()));
        splash.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(splash, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private void buildLayout() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(8, 11, 18));

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(Color.rgb(8, 11, 18));
        root.addView(shell, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        shell.addView(buildTopBar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, getStatusBarHeight()));

        webContainer = new FrameLayout(this);
        shell.addView(webContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        webContainer.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                int bottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) webContainer.getLayoutParams();
                if (params.bottomMargin != bottom) {
                    params.bottomMargin = bottom;
                    webContainer.setLayoutParams(params);
                }
                return insets;
            }
        });

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        webContainer.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(3));
        progressParams.gravity = Gravity.TOP;
        webContainer.addView(progress, progressParams);

        menuScrim = buildMenuScrim();
        menuScrim.setVisibility(View.GONE);
        root.addView(menuScrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        lockOverlay = buildLockOverlay();
        lockOverlay.setVisibility(View.GONE);
        root.addView(lockOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private LinearLayout buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(8), 0);
        bar.setBackgroundColor(Color.rgb(7, 10, 16));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.HORIZONTAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        timeView = topText("", 14, Color.WHITE);
        dateView = topText("", 12, Color.rgb(170, 184, 205));
        dateView.setPadding(dp(10), 0, 0, 0);
        left.addView(timeView);
        left.addView(dateView);
        bar.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = topText("Spark AI", 14, Color.rgb(238, 245, 255));
        title.setGravity(Gravity.CENTER);
        bar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        wifiView = new WifiStatusView(this);
        batteryView = new BatteryStatusView(this);
        LinearLayout.LayoutParams wifiParams = new LinearLayout.LayoutParams(dp(24), dp(18));
        wifiParams.setMargins(0, 0, dp(8), 0);
        right.addView(wifiView, wifiParams);
        right.addView(batteryView, new LinearLayout.LayoutParams(dp(30), dp(16)));
        bar.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        return bar;
    }

    private FrameLayout buildMenuScrim() {
        FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(Color.TRANSPARENT);
        scrim.setClickable(true);
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                hideMenu();
            }
        });

        FrameLayout sheet = new FrameLayout(this);
        controlsSheet = sheet;
        sheet.setClickable(true);
        sheet.setBackground(bottomRoundRect(menuSurfaceColor(), dp(22)));
        sheet.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
            }
        });

        controlsPanel = new LinearLayout(this);
        controlsPanel.setOrientation(LinearLayout.VERTICAL);
        controlsPanel.setPadding(dp(28), getStatusBarHeight() + dp(18), dp(28), dp(10));

        LinearLayout brightnessRow = new LinearLayout(this);
        brightnessRow.setOrientation(LinearLayout.HORIZONTAL);
        brightnessRow.setGravity(Gravity.CENTER_VERTICAL);
        brightnessRow.setPadding(0, dp(4), 0, dp(12));

        brightnessIcon = new BrightnessIconView(this);
        LinearLayout.LayoutParams brightnessIconParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        brightnessIconParams.setMargins(0, 0, dp(18), 0);
        brightnessRow.addView(brightnessIcon, brightnessIconParams);

        SeekBar brightness = new SeekBar(this);
        brightness.setMax(255);
        brightness.setProgress(prefs.getInt(PREF_BRIGHTNESS, 180));
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                applyBrightness(Math.max(20, value), fromUser);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        brightnessRow.addView(brightness, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        controlsPanel.addView(brightnessRow, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(0, dp(12), 0, 0);

        rotateButton = new IconButtonView(this, IconButtonView.MODE_ROTATION_LOCK);
        rotateButton.setActive(!prefs.getBoolean(PREF_ROTATE, true));
        rotateButton.setContentDescription("Rotation lock");
        rotateButton.setTooltipText("Rotation lock");
        rotateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                boolean nextLocked = prefs.getBoolean(PREF_ROTATE, true);
                applyRotation(!nextLocked, true);
                rotateButton.setActive(nextLocked);
            }
        });
        actions.addView(rotateButton, iconParams());

        darkButton = new IconButtonView(this, IconButtonView.MODE_DARK);
        darkButton.setActive(prefs.getBoolean(PREF_DARK, false));
        darkButton.setContentDescription("Dark mode");
        darkButton.setTooltipText("Dark mode");
        darkButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                boolean nextDark = !prefs.getBoolean(PREF_DARK, false);
                applyDarkMode(nextDark, true);
                darkButton.setActive(nextDark);
            }
        });
        actions.addView(darkButton, iconParams());
        reloadButton = new IconButtonView(this, IconButtonView.MODE_RELOAD);
        reloadButton.setContentDescription("Reload");
        reloadButton.setTooltipText("Reload");
        reloadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                loadSpark();
                hideMenu();
            }
        });
        actions.addView(reloadButton, iconParams());
        controlsPanel.addView(actions, matchWrap());

        controlsGripper = new GripperView(this);
        controlsGripper.setContentDescription("Close controls");
        controlsGripper.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                hideMenu();
            }
        });
        controlsGripper.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    controlsSheetTouchStartY = event.getRawY();
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                        && controlsSheetTouchStartY - event.getRawY() > dp(24)) {
                    hideMenu();
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    if (controlsSheetTouchStartY - event.getRawY() > dp(12)) {
                        hideMenu();
                    } else {
                        view.performClick();
                    }
                    return true;
                }
                return true;
            }
        });
        LinearLayout.LayoutParams gripperParams = new LinearLayout.LayoutParams(dp(80), dp(34));
        gripperParams.gravity = Gravity.CENTER_HORIZONTAL;
        gripperParams.setMargins(0, dp(8), 0, 0);
        controlsPanel.addView(controlsGripper, gripperParams);

        applyControlsTheme();

        sheet.addView(controlsPanel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                getControlsSheetWidth(), FrameLayout.LayoutParams.WRAP_CONTENT);
        panelParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        scrim.addView(sheet, panelParams);
        sheet.setTranslationY(-getControlsPanelDragHeight());
        return scrim;
    }

    private FrameLayout buildLockOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.rgb(5, 8, 14));
        overlay.setClickable(true);
        overlay.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                float dismissDistance = Math.max(dp(180), view.getHeight() * 0.4f);
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    view.animate().cancel();
                    lockTouchStartY = event.getRawY();
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    float drag = Math.max(0f, lockTouchStartY - event.getRawY());
                    float amount = Math.min(1f, drag / dismissDistance);
                    view.setTranslationY(-drag);
                    view.setAlpha(1f - amount * 0.45f);
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    float drag = Math.max(0f, lockTouchStartY - event.getRawY());
                    if (drag >= dismissDistance) {
                        view.animate()
                                .translationY(-view.getHeight())
                                .alpha(0f)
                                .setDuration(180)
                                .withEndAction(new Runnable() {
                                    @Override
                                    public void run() {
                                        setTransportLocked(false, true);
                                    }
                                })
                                .start();
                    } else {
                        view.animate().translationY(0f).alpha(1f).setDuration(180).start();
                        scheduleHideSystemBars();
                    }
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    view.animate().translationY(0f).alpha(1f).setDuration(180).start();
                    return true;
                }
                return true;
            }
        });

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(32), dp(32), dp(32), dp(32));

        TextView title = text("Spark AI", 28, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        content.addView(title, matchWrap());

        TextView subtitle = text("Swipe up to unlock", 18, Color.rgb(185, 199, 218));
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(18), 0, 0);
        content.addView(subtitle, matchWrap());

        overlay.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return overlay;
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        String userAgent = settings.getUserAgentString();
        if (userAgent == null || !userAgent.contains("SparkOS/1")) {
            settings.setUserAgentString((userAgent == null ? "" : userAgent + " ")
                    + SPARK_OS_USER_AGENT_CAPABILITIES);
        }
        settings.setSafeBrowsingEnabled(true);
        WebView.setWebContentsDebuggingEnabled(false);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isAllowed(request.getOrigin())) {
                            grantCameraPermissions();
                            request.grant(request.getResources());
                        } else {
                            request.deny();
                        }
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             WebChromeClient.FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                cameraImageUri = null;

                Intent cameraIntent = null;
                if (params.isCaptureEnabled() && acceptsImage(params)) {
                    cameraImageUri = createCameraImageUri();
                    Intent candidate = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    if (cameraImageUri != null && candidate.resolveActivity(getPackageManager()) != null) {
                        candidate.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri);
                        candidate.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        candidate.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        cameraIntent = candidate;
                    } else if (cameraImageUri != null) {
                        try {
                            getContentResolver().delete(cameraImageUri, null, null);
                        } catch (RuntimeException ignored) {
                        }
                        cameraImageUri = null;
                    }
                }

                Intent openIntent;
                if (cameraIntent != null) {
                    openIntent = cameraIntent;
                } else {
                    openIntent = buildOpenDocumentIntent(params);
                }

                try {
                    startActivityForResult(openIntent, FILE_CHOOSER_REQUEST);
                } catch (RuntimeException e) {
                    if (cameraImageUri != null) {
                        try {
                            getContentResolver().delete(cameraImageUri, null, null);
                        } catch (RuntimeException ignored) {
                        }
                        cameraImageUri = null;
                    }
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request.isForMainFrame() && !isAllowed(request.getUrl())) {
                    return true;
                }
                if (request.isForMainFrame()) {
                    offlinePageShowing = false;
                    loadWaitingForConnectivity = false;
                }
                return false;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    sparkLoadInProgress = false;
                    showOfflinePage();
                    startFirstLoadRetry();
                }
            }

            @Override
            public void onPageFinished(final WebView view, String url) {
                sparkLoadInProgress = false;
                if (!offlinePageShowing && isAllowed(Uri.parse(url))) {
                    sparkPageLoaded = true;
                    loadWaitingForConnectivity = false;
                    stopFirstLoadRetry();
                }
                injectKioskPageStyle(view);
                view.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        injectKioskPageStyle(view);
                    }
                }, 1000);
            }
        });
    }

    private void injectKioskPageStyle(WebView view) {
        if (view == null) {
            return;
        }
        String script = "(function(){"
                + "if(document.getElementById('spark-kiosk-style'))return;"
                + "var style=document.createElement('style');"
                + "style.id='spark-kiosk-style';"
                + "style.textContent='button[aria-label=\"Voice input\"],.composer-mic{display:none!important;}';"
                + "document.documentElement.appendChild(style);"
                + "})();";
        view.evaluateJavascript(script, null);
    }

    private Intent buildOpenDocumentIntent(WebChromeClient.FileChooserParams params) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        String[] mimeTypes = resolveMimeTypes(params);
        intent.setType(resolvePrimaryMimeType(mimeTypes));
        if (mimeTypes.length > 1) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        }
        if (params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return intent;
    }

    private String resolvePrimaryMimeType(String[] mimeTypes) {
        if (mimeTypes.length == 0) {
            return "*/*";
        }
        if (mimeTypes.length == 1) {
            return mimeTypes[0];
        }
        boolean imagesOnly = true;
        for (int i = 0; i < mimeTypes.length; i++) {
            if (!mimeTypes[i].startsWith("image/")) {
                imagesOnly = false;
                break;
            }
        }
        return imagesOnly ? "image/*" : "*/*";
    }

    private boolean acceptsImage(WebChromeClient.FileChooserParams params) {
        String[] mimeTypes = resolveMimeTypes(params);
        if (mimeTypes.length == 0) {
            return true;
        }
        for (int i = 0; i < mimeTypes.length; i++) {
            if (mimeTypes[i].equals("image/*") || mimeTypes[i].startsWith("image/")) {
                return true;
            }
        }
        return false;
    }

    private String[] resolveMimeTypes(WebChromeClient.FileChooserParams params) {
        Set<String> values = new LinkedHashSet<>();
        String[] accept = params.getAcceptTypes();
        if (accept != null) {
            for (int i = 0; i < accept.length; i++) {
                addAcceptTokens(values, accept[i]);
            }
        }
        if (values.isEmpty()) {
            return new String[0];
        }
        return values.toArray(new String[0]);
    }

    private void addAcceptTokens(Set<String> values, String rawValue) {
        if (rawValue == null) {
            return;
        }
        String[] tokens = rawValue.split(",");
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i].trim().toLowerCase(Locale.US);
            if (token.length() == 0) {
                continue;
            }
            String mime = mimeTypeForAcceptToken(token);
            if (mime != null) {
                values.add(mime);
            }
        }
    }

    private String mimeTypeForAcceptToken(String token) {
        if (token.contains("/")) {
            return token;
        }
        if (!token.startsWith(".")) {
            return null;
        }
        if (".jpg".equals(token) || ".jpeg".equals(token)) {
            return "image/jpeg";
        }
        if (".png".equals(token)) {
            return "image/png";
        }
        if (".webp".equals(token)) {
            return "image/webp";
        }
        if (".gif".equals(token)) {
            return "image/gif";
        }
        if (".heic".equals(token)) {
            return "image/heic";
        }
        if (".heif".equals(token)) {
            return "image/heif";
        }
        if (".pdf".equals(token)) {
            return "application/pdf";
        }
        if (".txt".equals(token)) {
            return "text/plain";
        }
        if (".md".equals(token) || ".markdown".equals(token)) {
            return "text/markdown";
        }
        if (".tex".equals(token) || ".ltx".equals(token) || ".latex".equals(token)) {
            return "application/x-tex";
        }
        return null;
    }

    private Uri[] readSelectedUris(Intent data) {
        List<Uri> uris = new ArrayList<>();
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                if (uri != null) {
                    uris.add(uri);
                }
            }
        }
        Uri single = data.getData();
        if (single != null) {
            uris.add(single);
        }
        if (uris.isEmpty()) {
            return WebChromeClient.FileChooserParams.parseResult(RESULT_OK, data);
        }
        return uris.toArray(new Uri[0]);
    }

    private Uri createCameraImageUri() {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "spark-photo-" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Spark");
            return getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean isAllowed(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        return "https".equalsIgnoreCase(scheme)
                && host != null
                && (SPARK_HOST.equalsIgnoreCase(host) || host.toLowerCase().endsWith("." + SPARK_HOST));
    }

    private void showOfflinePage() {
        offlinePageShowing = true;
        loadWaitingForConnectivity = !sparkPageLoaded;
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;height:100%;background:#080b12;color:#f8fbff;font-family:-apple-system,BlinkMacSystemFont,Roboto,Arial,sans-serif;}"
                + "body{display:grid;place-items:center}.box{text-align:center;padding:48px;max-width:860px}"
                + ".brand{font-size:68px;line-height:1;font-weight:650;letter-spacing:0}.msg{font-size:28px;color:#d6dfec;margin-top:26px}"
                + ".hint{font-size:18px;color:#92a4bc;margin-top:14px;line-height:1.45}"
                + "button{margin-top:34px;border:0;border-radius:28px;padding:15px 34px;background:#56bdf5;color:#06101a;font-size:18px;font-weight:650}"
                + "</style></head><body><main class='box'><div class='brand'>Spark AI</div>"
                + "<div class='msg'>This tablet is offline.</div>"
                + "<div class='hint'>Spark will open automatically when Wi-Fi is available.</div>"
                + "<button onclick=\"location.href='" + SPARK_URL + "'\">Reload</button></main></body></html>";
        webView.loadDataWithBaseURL(SPARK_URL, html, "text/html", "UTF-8", null);
    }

    private void loadSparkWhenPossible() {
        if (hasNetworkConnectivity()) {
            loadSpark();
        } else {
            showOfflinePage();
        }
        startFirstLoadRetry();
    }

    private void loadSpark() {
        if (webView == null) {
            return;
        }
        offlinePageShowing = false;
        loadWaitingForConnectivity = false;
        sparkLoadInProgress = true;
        lastSparkLoadAttemptAt = System.currentTimeMillis();
        webView.loadUrl(SPARK_URL);
    }

    private void retryFirstLoadNow() {
        if (sparkPageLoaded || webView == null) {
            stopFirstLoadRetry();
            return;
        }
        if (!hasNetworkConnectivity()) {
            if (!offlinePageShowing) {
                showOfflinePage();
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (!sparkLoadInProgress
                || now - lastSparkLoadAttemptAt >= FIRST_LOAD_IN_PROGRESS_TIMEOUT_MS) {
            loadSpark();
        }
    }

    private void startFirstLoadRetry() {
        if (handler == null || sparkPageLoaded) {
            return;
        }
        if (firstLoadRetry == null) {
            firstLoadRetry = new Runnable() {
                @Override
                public void run() {
                    retryFirstLoadNow();
                    if (!sparkPageLoaded && handler != null) {
                        handler.postDelayed(this, FIRST_LOAD_RETRY_MS);
                    }
                }
            };
        }
        handler.removeCallbacks(firstLoadRetry);
        handler.postDelayed(firstLoadRetry, FIRST_LOAD_RETRY_MS);
    }

    private void stopFirstLoadRetry() {
        if (handler != null && firstLoadRetry != null) {
            handler.removeCallbacks(firstLoadRetry);
        }
    }

    private void applySavedControls() {
        applyBrightness(prefs.getInt(PREF_BRIGHTNESS, 180), false);
        applyRotation(prefs.getBoolean(PREF_ROTATE, true), false);
        applyDarkMode(prefs.getBoolean(PREF_DARK, false), false);
    }

    private void applyBrightness(int value, boolean save) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = Math.max(20, Math.min(255, value)) / 255f;
        getWindow().setAttributes(lp);
        if (Settings.System.canWrite(this)) {
            try {
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, value);
            } catch (SecurityException ignored) {
            }
        }
        if (save) {
            prefs.edit().putInt(PREF_BRIGHTNESS, value).apply();
        }
    }

    private void applyRotation(boolean enabled, boolean save) {
        int fallbackRotation = getDisplayRotation();
        int rotation = save ? fallbackRotation
                : prefs.getInt(PREF_USER_ROTATION, fallbackRotation);
        int orientation = save ? activityOrientationForRotation(rotation)
                : lockedOrientationForRotation(rotation);
        setRequestedOrientation(enabled
                ? ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                : orientation);
        if (Settings.System.canWrite(this)) {
            try {
                Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, enabled ? 1 : 0);
                if (!enabled) {
                    Settings.System.putInt(getContentResolver(), Settings.System.USER_ROTATION, rotation);
                }
            } catch (SecurityException ignored) {
            }
        }
        if (save) {
            SharedPreferences.Editor editor = prefs.edit().putBoolean(PREF_ROTATE, enabled);
            if (!enabled) {
                editor.putInt(PREF_USER_ROTATION, rotation);
                editor.putInt(PREF_LOCKED_ORIENTATION, orientation);
                editor.putInt(PREF_LOCKED_ORIENTATION_ROTATION, rotation);
            }
            editor.apply();
        }
        if (rotateButton != null) {
            rotateButton.setActive(!enabled);
        }
    }

    private void applyDarkMode(boolean enabled, boolean save) {
        if (webView != null) {
            webView.getSettings().setForceDark(enabled ? WebSettings.FORCE_DARK_ON : WebSettings.FORCE_DARK_OFF);
        }
        applySystemNightMode(enabled);
        if (save) {
            prefs.edit().putBoolean(PREF_DARK, enabled).apply();
            if (webView != null) {
                webView.reload();
            }
        }
        if (darkButton != null) {
            darkButton.setActive(enabled);
        }
        applyControlsTheme();
        restartInputView();
    }

    private int getDisplayRotation() {
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null || windowManager.getDefaultDisplay() == null) {
            return Surface.ROTATION_0;
        }
        return windowManager.getDefaultDisplay().getRotation();
    }

    private int activityOrientationForRotation(int rotation) {
        boolean naturalPortrait = isNaturalOrientationPortrait(rotation);
        if (naturalPortrait) {
            if (rotation == Surface.ROTATION_90) {
                return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            }
            if (rotation == Surface.ROTATION_180) {
                return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
            }
            if (rotation == Surface.ROTATION_270) {
                return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
            }
            return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        }
        if (rotation == Surface.ROTATION_90) {
            return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
        }
        if (rotation == Surface.ROTATION_180) {
            return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        }
        if (rotation == Surface.ROTATION_270) {
            return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        }
        return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
    }

    private boolean isNaturalOrientationPortrait(int rotation) {
        int orientation = getResources().getConfiguration().orientation;
        return (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180)
                ? orientation == Configuration.ORIENTATION_PORTRAIT
                : orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void applySystemNightMode(boolean enabled) {
        try {
            UiModeManager uiModeManager = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
            if (uiModeManager != null) {
                uiModeManager.setNightMode(enabled
                        ? UiModeManager.MODE_NIGHT_YES
                        : UiModeManager.MODE_NIGHT_NO);
            }
        } catch (RuntimeException ignored) {
        }
        if (isDeviceOwner()) {
            setSecureSetting("ui_night_mode", enabled ? "2" : "1");
        }
        try {
            Settings.Secure.putInt(getContentResolver(), "ui_night_mode", enabled ? 2 : 1);
        } catch (RuntimeException ignored) {
        }
    }

    private void restartInputView() {
        try {
            InputMethodManager inputMethodManager =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            View view = webView != null ? webView : getWindow().getDecorView();
            if (inputMethodManager != null && view != null) {
                inputMethodManager.restartInput(view);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void applyDeviceOwnerPolicy() {
        if (!isDeviceOwner()) {
            return;
        }
        try {
            policyManager.setLockTaskPackages(admin, resolveLockTaskPackages());
            policyManager.setStatusBarDisabled(admin, true);
            policyManager.setKeyguardDisabled(admin, true);
            policyManager.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
            disablePowerCameraGestures();
            configurePowerButtonPolicy();
            configurePowerTimeoutPolicy();
            grantCameraPermissions();
            configureKeyboardPolicy();

            IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addCategory(Intent.CATEGORY_DEFAULT);
            policyManager.addPersistentPreferredActivity(admin, home,
                    new ComponentName(getPackageName(), MainActivity.class.getName()));

            addRestriction(UserManager.DISALLOW_ADD_USER);
            addRestriction(UserManager.DISALLOW_FACTORY_RESET);
            addRestriction(UserManager.DISALLOW_SAFE_BOOT);
            addRestriction(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);
            hidePackage("org.lineageos.jelly");
            hidePackage("com.android.settings");
            hidePackage("com.android.launcher3");
            hidePackage("org.lineageos.trebuchet");
        } catch (RuntimeException ignored) {
        }
    }

    private void grantCameraPermissions() {
        if (!isDeviceOwner()) {
            return;
        }
        try {
            policyManager.setPermissionGrantState(admin, getPackageName(), Manifest.permission.CAMERA,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            policyManager.setPermissionGrantState(admin, getPackageName(), Manifest.permission.RECORD_AUDIO,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            String cameraPackage = resolveActivityPackage(new Intent(MediaStore.ACTION_IMAGE_CAPTURE));
            if (cameraPackage != null) {
                grantPackagePermission(cameraPackage, Manifest.permission.CAMERA);
                grantPackagePermission(cameraPackage, Manifest.permission.RECORD_AUDIO);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void grantPackagePermission(String packageName, String permission) {
        try {
            policyManager.setPermissionGrantState(admin, packageName, permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
        } catch (RuntimeException ignored) {
        }
    }

    private void configureKeyboardPolicy() {
        denyPackagePermission("com.android.inputmethod.latin", Manifest.permission.READ_CONTACTS);
        setSecureSetting(Settings.Secure.DEFAULT_INPUT_METHOD, "com.android.inputmethod.latin/.LatinIME");
        setSecureSetting(Settings.Secure.ENABLED_INPUT_METHODS, "com.android.inputmethod.latin/.LatinIME");
        setSecureSetting("ui_night_mode", prefs.getBoolean(PREF_DARK, false) ? "2" : "1");
    }

    private void disablePowerCameraGestures() {
        setSecureSetting("camera_double_tap_power_gesture_disabled", "1");
        setSecureSetting("camera_gesture_disabled", "1");
        setSecureSetting("camera_lift_trigger_enabled", "0");
    }

    private void configurePowerButtonPolicy() {
        setGlobalSetting("power_button_long_press", "2");
        setGlobalSetting("power_button_very_long_press", "0");
    }

    private void configurePowerTimeoutPolicy() {
        if (Settings.System.canWrite(this)) {
            try {
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT,
                        (int) INACTIVITY_TIMEOUT_MS);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void setSecureSetting(String key, String value) {
        try {
            policyManager.setSecureSetting(admin, key, value);
        } catch (RuntimeException ignored) {
        }
    }

    private void setGlobalSetting(String key, String value) {
        try {
            policyManager.setGlobalSetting(admin, key, value);
        } catch (RuntimeException ignored) {
        }
        try {
            Settings.Global.putString(getContentResolver(), key, value);
        } catch (RuntimeException ignored) {
        }
    }

    private void denyPackagePermission(String packageName, String permission) {
        try {
            policyManager.setPermissionGrantState(admin, packageName, permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED);
        } catch (RuntimeException ignored) {
        }
    }

    private String[] resolveLockTaskPackages() {
        LinkedHashSet<String> packages = new LinkedHashSet<>();
        packages.add(getPackageName());
        addResolvedPackage(packages, new Intent(MediaStore.ACTION_IMAGE_CAPTURE));
        Intent openDocument = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        openDocument.addCategory(Intent.CATEGORY_OPENABLE);
        openDocument.setType("*/*");
        addResolvedPackage(packages, openDocument);
        addExistingPackage(packages, "org.lineageos.snap");
        addExistingPackage(packages, "com.android.documentsui");
        return packages.toArray(new String[0]);
    }

    private void addResolvedPackage(Set<String> packages, Intent intent) {
        String packageName = resolveActivityPackage(intent);
        if (packageName != null) {
            packages.add(packageName);
        }
    }

    private String resolveActivityPackage(Intent intent) {
        try {
            ResolveInfo info = getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (info != null && info.activityInfo != null && info.activityInfo.packageName != null) {
                return info.activityInfo.packageName;
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private void addExistingPackage(Set<String> packages, String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            packages.add(packageName);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
    }

    private void startKioskLock() {
        if (isDeviceOwner()) {
            try {
                startLockTask();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private boolean isDeviceOwner() {
        return policyManager != null && policyManager.isDeviceOwnerApp(getPackageName());
    }

    private void addRestriction(String restriction) {
        try {
            policyManager.addUserRestriction(admin, restriction);
        } catch (RuntimeException ignored) {
        }
    }

    private void hidePackage(String packageName) {
        try {
            policyManager.setApplicationHidden(admin, packageName, true);
        } catch (RuntimeException ignored) {
        }
    }

    private boolean isUserUnlocked() {
        UserManager userManager = (UserManager) getSystemService(Context.USER_SERVICE);
        return userManager == null || userManager.isUserUnlocked();
    }

    private void registerUnlockReceiver() {
        if (unlockReceiver != null) {
            return;
        }
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
                    recreate();
                }
            }
        };
        registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
    }

    private void registerStatusReceiver() {
        if (statusReceiver != null) {
            return;
        }
        statusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateStatusBar();
                if (!sparkPageLoaded) {
                    retryFirstLoadNow();
                    startFirstLoadRetry();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
        registerReceiver(statusReceiver, filter);
    }

    private void registerNetworkCallback() {
        if (networkCallback != null) {
            return;
        }
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return;
        }
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                handleNetworkMaybeReady();
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                handleNetworkMaybeReady();
            }

            @Override
            public void onLost(Network network) {
                if (handler != null) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            updateStatusBar();
                        }
                    });
                }
            }
        };
        try {
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException e) {
            networkCallback = null;
        }
    }

    private void handleNetworkMaybeReady() {
        if (handler == null) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                updateStatusBar();
                if (!sparkPageLoaded) {
                    retryFirstLoadNow();
                    startFirstLoadRetry();
                }
            }
        });
    }

    private void registerScreenReceiver() {
        if (screenReceiver != null) {
            return;
        }
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    if (inactivityRunnable != null) {
                        handler.removeCallbacks(inactivityRunnable);
                    }
                    enforceSavedOrientationIfRotationLocked();
                    storeCurrentRotationForLock();
                    setTransportLocked(true, true);
                    return;
                }
                if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    setTransportLocked(prefs.getBoolean(PREF_TRANSPORT_LOCKED, false), false);
                    hideSystemBars();
                    scheduleHideSystemBars();
                    resetInactivityTimer();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, filter);
    }

    private void startClockTicker() {
        if (clockTicker != null) {
            return;
        }
        clockTicker = new Runnable() {
            @Override
            public void run() {
                updateStatusBar();
                handler.postDelayed(this, 30000);
            }
        };
        handler.post(clockTicker);
    }

    private void resetInactivityTimer() {
        if (handler == null || !isUserUnlocked()) {
            return;
        }
        if (inactivityRunnable == null) {
            inactivityRunnable = new Runnable() {
                @Override
                public void run() {
                    setTransportLocked(true, true);
                    powerOffScreen();
                }
            };
        }
        handler.removeCallbacks(inactivityRunnable);
        handler.postDelayed(inactivityRunnable, INACTIVITY_TIMEOUT_MS);
    }

    private void powerOffScreen() {
        if (isDeviceOwner()) {
            try {
                policyManager.lockNow();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void updateStatusBar() {
        if (timeView == null || dateView == null || wifiView == null || batteryView == null) {
            return;
        }
        Date now = new Date();
        timeView.setText(new SimpleDateFormat("HH:mm", Locale.UK).format(now));
        dateView.setText(new SimpleDateFormat("EEE d MMM", Locale.UK).format(now));
        wifiView.setLevel(readWifiLevel());
        updateBatteryStatus();
    }

    private int readWifiLevel() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return 0;
            }
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wifiManager == null ? null : wifiManager.getConnectionInfo();
            if (info == null || info.getRssi() <= -120) {
                return 1;
            }
            return WifiManager.calculateSignalLevel(info.getRssi(), 4) + 1;
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private boolean hasNetworkConnectivity() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void updateBatteryStatus() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) {
            batteryView.setStatus(0, false);
            return;
        }
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        int percent = scale > 0 && level >= 0 ? Math.round(level * 100f / scale) : -1;
        batteryView.setStatus(percent, charging);
    }

    private void showMenu() {
        if (menuScrim != null && controlsSheet != null) {
            updateControlsSheetLayout();
            controlsPanelOpen = true;
            menuScrim.setVisibility(View.VISIBLE);
            menuScrim.bringToFront();
            controlsSheet.animate().cancel();
            controlsSheet.animate().translationY(0f).setDuration(180).start();
            menuScrim.setBackgroundColor(Color.argb(120, 0, 0, 0));
        }
        hideSystemBars();
    }

    private void hideMenu() {
        if (menuScrim != null && controlsSheet != null) {
            controlsPanelOpen = false;
            controlsSheet.animate().cancel();
            controlsSheet.animate()
                    .translationY(-getControlsPanelDragHeight())
                    .setDuration(180)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            if (!controlsPanelOpen && menuScrim != null) {
                                menuScrim.setVisibility(View.GONE);
                                menuScrim.setBackgroundColor(Color.TRANSPARENT);
                            }
                        }
                    })
                    .start();
        }
        hideSystemBars();
    }

    private void setControlsPanelProgress(float progressValue) {
        if (menuScrim == null || controlsSheet == null) {
            return;
        }
        updateControlsSheetLayout();
        float progress = Math.max(0f, Math.min(1f, progressValue));
        if (menuScrim.getVisibility() != View.VISIBLE) {
            menuScrim.setVisibility(View.VISIBLE);
            menuScrim.bringToFront();
        }
        controlsSheet.animate().cancel();
        controlsSheet.setTranslationY(-getControlsPanelDragHeight() * (1f - progress));
        menuScrim.setBackgroundColor(Color.argb(Math.round(120f * progress), 0, 0, 0));
        hideSystemBars();
    }

    private float getControlsPanelDragHeight() {
        if (controlsSheet != null && controlsSheet.getHeight() > 0) {
            return controlsSheet.getHeight();
        }
        return getStatusBarHeight() + dp(170);
    }

    private int getControlsSheetWidth() {
        int width = root != null ? root.getWidth() : 0;
        if (width <= 0) {
            View decor = getWindow().getDecorView();
            width = decor != null ? decor.getWidth() : 0;
        }
        if (width <= 0) {
            width = getResources().getDisplayMetrics().widthPixels;
        }
        return Math.min(dp(760), Math.max(dp(360), width - dp(112)));
    }

    private void updateControlsSheetLayout() {
        if (controlsSheet == null) {
            return;
        }
        View parent = (View) controlsSheet.getParent();
        if (parent == null || parent.getWidth() <= 0) {
            return;
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) controlsSheet.getLayoutParams();
        int width = getControlsSheetWidth();
        if (params.width != width) {
            params.width = width;
            controlsSheet.setLayoutParams(params);
        }
    }

    private int menuSurfaceColor() {
        return prefs.getBoolean(PREF_DARK, false)
                ? Color.rgb(12, 16, 26)
                : Color.rgb(248, 250, 253);
    }

    private void applyControlsTheme() {
        boolean dark = prefs.getBoolean(PREF_DARK, false);
        int surface = dark ? Color.rgb(12, 16, 26) : Color.rgb(248, 250, 253);
        int icon = dark ? Color.rgb(218, 228, 242) : Color.rgb(36, 45, 60);
        int inactiveFill = dark ? Color.rgb(31, 38, 52) : Color.rgb(231, 236, 244);
        int inactiveStroke = dark ? Color.rgb(82, 94, 116) : Color.rgb(190, 200, 214);
        int activeFill = dark ? Color.rgb(38, 102, 138) : Color.rgb(214, 241, 255);
        int activeIcon = dark ? Color.rgb(125, 213, 255) : Color.rgb(0, 101, 164);
        int activeStroke = dark ? Color.rgb(86, 189, 245) : Color.rgb(55, 164, 226);

        if (controlsSheet != null) {
            controlsSheet.setBackground(bottomRoundRect(surface, dp(22)));
        }
        if (brightnessIcon != null) {
            brightnessIcon.setColor(icon);
        }
        if (rotateButton != null) {
            rotateButton.setThemeColors(inactiveFill, inactiveStroke, icon,
                    activeFill, activeStroke, activeIcon, surface);
        }
        if (darkButton != null) {
            darkButton.setThemeColors(inactiveFill, inactiveStroke, icon,
                    activeFill, activeStroke, activeIcon, surface);
        }
        if (reloadButton != null) {
            reloadButton.setThemeColors(inactiveFill, inactiveStroke, icon,
                    activeFill, activeStroke, activeIcon, surface);
        }
        if (controlsGripper != null) {
            controlsGripper.setColor(dark ? Color.rgb(104, 116, 136) : Color.rgb(150, 160, 174));
        }
    }

    private void setTransportLocked(boolean locked, boolean save) {
        transportLocked = locked;
        if (save) {
            prefs.edit().putBoolean(PREF_TRANSPORT_LOCKED, locked).apply();
        }
        if (locked) {
            applyLockOverlayRotation();
        }
        if (lockOverlay != null) {
            lockOverlay.setVisibility(locked ? View.VISIBLE : View.GONE);
            if (locked) {
                lockOverlay.setTranslationY(0f);
                lockOverlay.setAlpha(1f);
                lockOverlay.bringToFront();
            }
        }
        if (webView != null) {
            webView.setEnabled(!locked);
        }
        if (!locked) {
            suspendAutoRotationForLock = false;
            applyRotation(prefs.getBoolean(PREF_ROTATE, true), false);
        }
        hideSystemBars();
    }

    private void storeCurrentRotationForLock() {
        boolean autoRotate = prefs.getBoolean(PREF_ROTATE, true);
        suspendAutoRotationForLock = false;
        if (autoRotate) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
            if (Settings.System.canWrite(this)) {
                try {
                    Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1);
                } catch (SecurityException ignored) {
                }
            }
            return;
        }
        int rotation = prefs.getInt(PREF_USER_ROTATION, getDisplayRotation());
        int orientation = lockedOrientationForRotation(rotation);
        prefs.edit()
                .putInt(PREF_USER_ROTATION, rotation)
                .putInt(PREF_LOCKED_ORIENTATION, orientation)
                .putInt(PREF_LOCKED_ORIENTATION_ROTATION, rotation)
                .apply();
        applyExactOrientation(orientation, rotation);
    }

    private void applyLockOverlayRotation() {
        int rotation = prefs.getInt(PREF_USER_ROTATION, getDisplayRotation());
        int orientation = lockedOrientationForRotation(rotation);
        boolean autoRotate = prefs.getBoolean(PREF_ROTATE, true);
        if (autoRotate && !suspendAutoRotationForLock) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
            if (Settings.System.canWrite(this)) {
                try {
                    Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1);
                } catch (SecurityException ignored) {
                }
            }
            return;
        }
        applyExactOrientation(orientation, rotation);
    }

    private void enforceSavedOrientationIfRotationLocked() {
        if (transportLocked || prefs.getBoolean(PREF_ROTATE, true)) {
            return;
        }
        int rotation = prefs.getInt(PREF_USER_ROTATION, getDisplayRotation());
        int orientation = lockedOrientationForRotation(rotation);
        applyExactOrientation(orientation, rotation);
    }

    private int lockedOrientationForRotation(int rotation) {
        int fallback = activityOrientationForRotation(rotation);
        if (!prefs.contains(PREF_LOCKED_ORIENTATION)) {
            return fallback;
        }
        if (prefs.getInt(PREF_LOCKED_ORIENTATION_ROTATION, -1) != rotation) {
            return fallback;
        }
        return prefs.getInt(PREF_LOCKED_ORIENTATION, fallback);
    }

    private void applyExactRotation(int rotation) {
        applyExactOrientation(activityOrientationForRotation(rotation), rotation);
    }

    private void applyExactOrientation(int orientation, int rotation) {
        setRequestedOrientation(orientation);
        if (Settings.System.canWrite(this)) {
            try {
                Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
                Settings.System.putInt(getContentResolver(), Settings.System.USER_ROTATION, rotation);
            } catch (SecurityException ignored) {
            }
        }
    }

    private TextView topText(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(false);
        return view;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(6), 0, dp(6));
        return params;
    }

    private LinearLayout.LayoutParams iconParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(58), dp(58));
        params.setMargins(dp(10), 0, dp(10), 0);
        return params;
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private GradientDrawable bottomRoundRect(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadii(new float[]{
                0f, 0f,
                0f, 0f,
                radius, radius,
                radius, radius
        });
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int getStatusBarHeight() {
        int fallback = dp(24);
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            return Math.max(fallback, getResources().getDimensionPixelSize(resourceId));
        }
        return fallback;
    }

    private static final class BrightnessIconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color = Color.rgb(220, 230, 242);

        BrightnessIconView(Context context) {
            super(context);
            setContentDescription("Brightness");
        }

        void setColor(int value) {
            color = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(1.7f * density);
            paint.setColor(color);
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI * i / 4.0;
                float inner = 9f * density;
                float outer = 13f * density;
                canvas.drawLine(cx + (float) Math.cos(angle) * inner,
                        cy + (float) Math.sin(angle) * inner,
                        cx + (float) Math.cos(angle) * outer,
                        cy + (float) Math.sin(angle) * outer,
                        paint);
            }

            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, 5.5f * density, paint);
        }
    }

    private static final class IconButtonView extends View {
        static final int MODE_ROTATION_LOCK = 1;
        static final int MODE_DARK = 2;
        static final int MODE_RELOAD = 3;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path path = new Path();
        private final int mode;
        private boolean active;
        private int inactiveFill = Color.rgb(31, 38, 52);
        private int inactiveStroke = Color.rgb(82, 94, 116);
        private int inactiveIcon = Color.rgb(218, 228, 242);
        private int activeFill = Color.rgb(38, 102, 138);
        private int activeStroke = Color.rgb(86, 189, 245);
        private int activeIcon = Color.rgb(125, 213, 255);
        private int surfaceColor = Color.rgb(12, 16, 26);

        IconButtonView(Context context, int mode) {
            super(context);
            this.mode = mode;
            setClickable(true);
            setFocusable(true);
        }

        void setActive(boolean value) {
            if (active != value) {
                active = value;
                invalidate();
            }
        }

        void setThemeColors(int nextInactiveFill, int nextInactiveStroke, int nextInactiveIcon,
                            int nextActiveFill, int nextActiveStroke, int nextActiveIcon,
                            int nextSurfaceColor) {
            inactiveFill = nextInactiveFill;
            inactiveStroke = nextInactiveStroke;
            inactiveIcon = nextInactiveIcon;
            activeFill = nextActiveFill;
            activeStroke = nextActiveStroke;
            activeIcon = nextActiveIcon;
            surfaceColor = nextSurfaceColor;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            int fill = active ? activeFill : inactiveFill;
            int icon = active ? activeIcon : inactiveIcon;
            if (isPressed()) {
                fill = active ? activeStroke : inactiveStroke;
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fill);
            canvas.drawCircle(cx, cy, Math.min(getWidth(), getHeight()) * 0.43f, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.25f * density);
            paint.setColor(active ? activeStroke : inactiveStroke);
            canvas.drawCircle(cx, cy, Math.min(getWidth(), getHeight()) * 0.43f, paint);

            if (mode == MODE_ROTATION_LOCK) {
                drawVectorIcon(canvas, active
                        ? R.drawable.ic_screen_lock_rotation_24
                        : R.drawable.ic_screen_rotation_24, cx, cy, density, icon);
            } else if (mode == MODE_DARK) {
                if (active) {
                    drawVectorIcon(canvas, R.drawable.ic_dark_mode_24,
                            cx + 1.5f * density, cy - 0.5f * density, density, icon);
                } else {
                    drawVectorIcon(canvas, R.drawable.ic_light_mode_24, cx, cy, density, icon);
                }
            } else {
                drawVectorIcon(canvas, R.drawable.ic_refresh_24, cx, cy, density, icon);
            }
        }

        private void drawVectorIcon(Canvas canvas, int resId, float cx, float cy, float density, int color) {
            Drawable icon = getResources().getDrawable(resId, null);
            int size = Math.round(27f * density);
            int left = Math.round(cx - size / 2f);
            int top = Math.round(cy - size / 2f);
            icon.setBounds(left, top, left + size, top + size);
            icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
            icon.draw(canvas);
        }
    }

    private static final class GripperView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int color = Color.rgb(104, 116, 136);

        GripperView(Context context) {
            super(context);
            setClickable(true);
        }

        void setColor(int value) {
            color = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float width = 36f * density;
            float height = 4f * density;
            float left = (getWidth() - width) / 2f;
            float top = (getHeight() - height) / 2f;
            rect.set(left, top, left + width, top + height);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawRoundRect(rect, height / 2f, height / 2f, paint);
        }
    }

    private static final class WifiStatusView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();
        private int level = 0;

        WifiStatusView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            setContentDescription("Wi-Fi");
        }

        void setLevel(int value) {
            int next = Math.max(0, Math.min(4, value));
            if (level != next) {
                level = next;
                setContentDescription(level == 0 ? "No Wi-Fi" : "Wi-Fi signal");
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float centerX = getWidth() / 2f;
            float baseY = getHeight() - 3.2f * density;
            paint.setStrokeWidth(1.45f * density);

            int active = Color.rgb(210, 222, 238);
            int inactive = Color.argb(78, 210, 222, 238);
            drawDot(canvas, centerX, baseY, level >= 1 ? active : inactive, density);
            drawArc(canvas, centerX, baseY, 3.8f * density, level >= 2 ? active : inactive);
            drawArc(canvas, centerX, baseY, 6.8f * density, level >= 3 ? active : inactive);
            drawArc(canvas, centerX, baseY, 9.8f * density, level >= 4 ? active : inactive);

            if (level == 0) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(Color.rgb(210, 222, 238));
                paint.setStrokeWidth(1.2f * density);
                canvas.drawLine(centerX - 7f * density, baseY - 9f * density,
                        centerX + 7f * density, baseY + 1.5f * density, paint);
            }
        }

        private void drawArc(Canvas canvas, float centerX, float baseY, float radius, int color) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(color);
            arc.set(centerX - radius, baseY - radius, centerX + radius, baseY + radius);
            canvas.drawArc(arc, 225f, 90f, false, paint);
        }

        private void drawDot(Canvas canvas, float centerX, float baseY, int color, float density) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawCircle(centerX, baseY, 1.45f * density, paint);
        }
    }

    private static final class BatteryStatusView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF body = new RectF();
        private final RectF cap = new RectF();
        private final RectF fill = new RectF();
        private final Path bolt = new Path();
        private int percent = 0;
        private boolean charging = false;

        BatteryStatusView(Context context) {
            super(context);
            setContentDescription("Battery");
        }

        void setStatus(int nextPercent, boolean nextCharging) {
            int clamped = nextPercent < 0 ? 0 : Math.max(0, Math.min(100, nextPercent));
            if (percent != clamped || charging != nextCharging) {
                percent = clamped;
                charging = nextCharging;
                setContentDescription(charging ? "Battery charging" : "Battery");
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float stroke = 1.15f * density;
            float left = 1.5f * density;
            float top = 1.4f * density;
            float right = getWidth() - 4.8f * density;
            float bottom = getHeight() - 1.4f * density;
            float radius = 2.4f * density;
            int outline = Color.rgb(210, 222, 238);
            int fillColor = charging ? Color.rgb(86, 189, 245) : outline;

            body.set(left, top, right, bottom);
            cap.set(right + 0.9f * density, top + 3.2f * density,
                    getWidth() - 1.2f * density, bottom - 3.2f * density);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(outline);
            canvas.drawRoundRect(body, radius, radius, paint);

            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(cap, 1.2f * density, 1.2f * density, paint);

            float inset = 2.5f * density;
            float fillRight = left + inset + (body.width() - inset * 2f) * (percent / 100f);
            fill.set(left + inset, top + inset, Math.max(left + inset, fillRight), bottom - inset);
            paint.setColor(fillColor);
            canvas.drawRoundRect(fill, 1.3f * density, 1.3f * density, paint);

            if (charging) {
                bolt.reset();
                float cx = body.centerX();
                float cy = body.centerY();
                bolt.moveTo(cx + 0.9f * density, cy - 4.8f * density);
                bolt.lineTo(cx - 2.8f * density, cy + 0.6f * density);
                bolt.lineTo(cx + 0.1f * density, cy + 0.6f * density);
                bolt.lineTo(cx - 0.9f * density, cy + 4.8f * density);
                bolt.lineTo(cx + 3.5f * density, cy - 1.4f * density);
                bolt.lineTo(cx + 0.5f * density, cy - 1.4f * density);
                bolt.close();
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.rgb(7, 10, 16));
                canvas.drawPath(bolt, paint);
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (unlockReceiver != null) {
            unregisterReceiver(unlockReceiver);
            unlockReceiver = null;
        }
        if (statusReceiver != null) {
            unregisterReceiver(statusReceiver);
            statusReceiver = null;
        }
        if (screenReceiver != null) {
            unregisterReceiver(screenReceiver);
            screenReceiver = null;
        }
        if (clockTicker != null) {
            handler.removeCallbacks(clockTicker);
            clockTicker = null;
        }
        if (inactivityRunnable != null) {
            handler.removeCallbacks(inactivityRunnable);
            inactivityRunnable = null;
        }
        if (firstLoadRetry != null) {
            handler.removeCallbacks(firstLoadRetry);
            firstLoadRetry = null;
        }
        if (networkCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                try {
                    cm.unregisterNetworkCallback(networkCallback);
                } catch (RuntimeException ignored) {
                }
            }
            networkCallback = null;
        }
        super.onDestroy();
    }
}

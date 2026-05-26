package com.example.lightroomclone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.example.lightroomclone.core.ColorAdjustments;
import com.example.lightroomclone.core.ColorMath;
import com.example.lightroomclone.core.CurveSet;
import com.example.lightroomclone.core.GeometryAdjustments;
import com.example.lightroomclone.core.ToneCurve;

import java.io.IOException;
import java.io.OutputStream;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int REQUEST_OPEN_IMAGE = 10;
    private static final int REQUEST_SAVE_IMAGE = 11;
    private static final int REQUEST_OPEN_BATCH = 12;
    private static final int MAX_PREVIEW_SIZE = 1400;
    private static final int RENDER_FAST_MAX_EDGE = 540;
    private static final int RENDER_QUALITY_MAX_EDGE = 960;
    private static final long QUALITY_RENDER_DELAY_MS = 180L;
    private static final String PREFS_NAME = "tonelab_memory";
    private static final String KEY_CUSTOM_PRESETS = "custom_presets";
    private static final String KEY_LAST_EDIT = "last_edit";
    private static final String KEY_EXPORT_QUALITY = "export_quality";
    private static final String KEY_EXPORT_SIZE = "export_size";
    private static final String EXPORT_FOLDER = "MyLight";
    private static final int MAX_UNDO_STEPS = 30;

    private static final int PANEL_FILTER = 0;
    private static final int PANEL_LIGHT = 1;
    private static final int PANEL_COLOR = 2;
    private static final int PANEL_HSL = 3;
    private static final int PANEL_CURVE = 4;
    private static final int PANEL_SIZE = 5;
    private static final int PANEL_EFFECTS = 6;

    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger renderVersion = new AtomicInteger();
    private final AtomicInteger histogramVersion = new AtomicInteger();
    private final Handler renderHandler = new Handler(Looper.getMainLooper());
    private final GeometryAdjustments geometry = new GeometryAdjustments();
    private final ColorAdjustments adjustments = new ColorAdjustments();
    private final Deque<EditSnapshot> undoStack = new ArrayDeque<>();
    private final Deque<EditSnapshot> redoStack = new ArrayDeque<>();
    private final List<String> undoLabels = new ArrayList<>();
    private final List<Uri> batchImageUris = new ArrayList<>();
    private CurveSet curves = new CurveSet();
    private final List<SliderBinding> sliderBindings = new ArrayList<>();

    private GpuImageView imageView;
    private LinearLayout panelTabs;
    private LinearLayout controls;
    private ScrollView controlScroll;
    private Button undoToolbarButton;
    private Button redoToolbarButton;
    private Bitmap originalBitmap;
    private Bitmap fastSourceBitmap;
    private Bitmap qualitySourceBitmap;
    private Bitmap previewBitmap;
    private boolean suppressSliderEvents;
    private int activePanel = PANEL_FILTER;
    private int activeAdjustPanel = PANEL_LIGHT;
    private int activeCurveChannel = CurveSet.LUMINANCE;
    private int activeMixChannel = ColorAdjustments.MIX_RED;
    private int cropGridMode = CropOverlayView.GRID_THIRDS;
    private CurveView curveView;
    private CropOverlayView cropOverlayView;
    private HistogramView histogramView;
    private TextView compareLabel;
    private TextView messageBar;
    private final Runnable qualityRenderRunnable = () -> renderPreview(false);
    private SharedPreferences preferences;
    private boolean renderInFlight;
    private boolean renderQueued;
    private boolean queuedInteractive;
    private boolean compareActive;
    private boolean previewCompareArmed;
    private boolean clippingWarningEnabled;
    private boolean whiteBalancePickMode;
    private boolean localPickMode;
    private int exportQuality = 95;
    private int exportSizeMode;
    private boolean histogramExpanded;
    private Uri originalImageUri;
    private ScaleGestureDetector previewScaleDetector;
    private float previewZoom = 1f;
    private float previewPanX;
    private float previewPanY;
    private float lastPreviewTouchX;
    private float lastPreviewTouchY;
    private boolean previewPanning;
    private Preset activeFilterPreset;
    private ColorAdjustments filterBaseAdjustments;
    private CurveSet filterBaseCurves;
    private float filterStrength = 1f;
    private final Runnable previewCompareRunnable = () -> {
        previewCompareArmed = false;
        compareActive = true;
        renderComparePreview();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        exportQuality = preferences.getInt(KEY_EXPORT_QUALITY, 95);
        exportSizeMode = preferences.getInt(KEY_EXPORT_SIZE, 0);
        previewScaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                previewZoom = clamp(previewZoom * detector.getScaleFactor(), 1f, 4f);
                applyPreviewTransform();
                return true;
            }
        });
        originalBitmap = createSampleBitmap(1400, 1000);
        rebuildRenderSources();
        previewBitmap = originalBitmap;
        setContentView(createContentView());
        renderControls();
        renderPreview();
    }

    @Override
    protected void onDestroy() {
        renderExecutor.shutdownNow();
        renderHandler.removeCallbacksAndMessages(null);
        recycleRenderSources();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setContentView(createContentView());
        renderControls();
        renderPreview(false);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_OPEN_BATCH) {
            collectBatchUris(data);
        } else if (requestCode == REQUEST_OPEN_IMAGE && uri != null) {
            loadImage(uri);
        } else if (requestCode == REQUEST_SAVE_IMAGE) {
            saveImage(uri);
        }
    }

    private View createContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(10, 12, 16));

        root.addView(createToolbar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));

        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (landscape) {
            LinearLayout workspace = new LinearLayout(this);
            workspace.setOrientation(LinearLayout.HORIZONTAL);
            workspace.setBackgroundColor(Color.rgb(8, 10, 14));
            root.addView(workspace, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

            workspace.addView(createImageFrame(), new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            workspace.addView(createControlPanel(true), new LinearLayout.LayoutParams(
                    landscapePanelWidth(), LinearLayout.LayoutParams.MATCH_PARENT));
        } else {
            root.addView(createImageFrame(), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            root.addView(createControlPanel(false), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(348)));
        }
        messageBar = new TextView(this);
        messageBar.setTextColor(Color.WHITE);
        messageBar.setTextSize(13f);
        messageBar.setGravity(Gravity.CENTER);
        messageBar.setAlpha(0f);
        return root;
    }

    private View createImageFrame() {
        FrameLayout imageFrame = new FrameLayout(this);
        imageFrame.setBackgroundColor(Color.rgb(6, 7, 10));
        imageView = new GpuImageView(this);
        imageView.setImageBitmap(previewBitmap);
        imageView.setOnTouchListener((view, event) -> handlePreviewTouch(event));
        imageFrame.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        applyPreviewTransform();
        cropOverlayView = new CropOverlayView(this, geometry);
        cropOverlayView.setImageSize(originalBitmap.getWidth(), originalBitmap.getHeight());
        cropOverlayView.setGridMode(cropGridMode);
        cropOverlayView.setVisibility(View.GONE);
        cropOverlayView.setListener(new CropOverlayView.Listener() {
            @Override
            public void onCropStarted() {
                pushUndoSnapshot();
            }

            @Override
            public void onCropChanged(boolean finished) {
                if (finished) {
                    renderPreview(false);
                } else {
                    renderInteractivePreview();
                }
                if (finished) {
                    renderControls();
                }
            }
        });
        imageFrame.addView(cropOverlayView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        histogramView = new HistogramView(this);
        FrameLayout.LayoutParams histogramParams = new FrameLayout.LayoutParams(dp(148), dp(82),
                Gravity.TOP | Gravity.RIGHT);
        histogramParams.setMargins(0, dp(14), dp(14), 0);
        imageFrame.addView(histogramView, histogramParams);
        histogramView.setOnClickListener(v -> toggleHistogram());
        compareLabel = new TextView(this);
        compareLabel.setText("原图");
        compareLabel.setTextColor(Color.WHITE);
        compareLabel.setTextSize(13f);
        compareLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        compareLabel.setGravity(Gravity.CENTER);
        compareLabel.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable compareBackground = new GradientDrawable();
        compareBackground.setColor(Color.argb(190, 14, 18, 25));
        compareBackground.setCornerRadius(dp(14));
        compareLabel.setBackground(compareBackground);
        compareLabel.setVisibility(View.GONE);
        FrameLayout.LayoutParams compareParams = new FrameLayout.LayoutParams(dp(64), dp(30),
                Gravity.LEFT | Gravity.TOP);
        compareParams.setMargins(dp(14), dp(14), 0, 0);
        imageFrame.addView(compareLabel, compareParams);
        imageFrame.setOnTouchListener((view, event) -> handlePreviewTouch(event));
        return imageFrame;
    }

    private void toggleHistogram() {
        histogramExpanded = !histogramExpanded;
        if (histogramView == null) {
            return;
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                histogramExpanded ? dp(230) : dp(148),
                histogramExpanded ? dp(124) : dp(82),
                Gravity.TOP | Gravity.RIGHT);
        params.setMargins(0, dp(14), dp(14), 0);
        histogramView.setLayoutParams(params);
    }

    private boolean handlePreviewTouch(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN && whiteBalancePickMode) {
            applyWhiteBalanceFromTap(event.getX(), event.getY());
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_DOWN && localPickMode) {
            applyLocalCenterFromTap(event.getX(), event.getY());
            return true;
        }
        if (activePanel == PANEL_SIZE) {
            return false;
        }
        if (previewScaleDetector != null) {
            previewScaleDetector.onTouchEvent(event);
        }
        if (event.getPointerCount() > 1) {
            renderHandler.removeCallbacks(previewCompareRunnable);
            previewCompareArmed = false;
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            lastPreviewTouchX = event.getX();
            lastPreviewTouchY = event.getY();
            previewPanning = previewZoom > 1.01f;
            previewCompareArmed = true;
            if (!previewPanning) {
                renderHandler.postDelayed(previewCompareRunnable, ViewConfiguration.getLongPressTimeout());
            }
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_MOVE) {
            if (previewPanning) {
                float dx = event.getX() - lastPreviewTouchX;
                float dy = event.getY() - lastPreviewTouchY;
                previewPanX += dx;
                previewPanY += dy;
                lastPreviewTouchX = event.getX();
                lastPreviewTouchY = event.getY();
                applyPreviewTransform();
                return true;
            }
            return previewCompareArmed || compareActive;
        }
        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            renderHandler.removeCallbacks(previewCompareRunnable);
            previewCompareArmed = false;
            previewPanning = false;
            if (compareActive) {
                compareActive = false;
                renderPreview(false);
            }
            return true;
        }
        return false;
    }

    private void applyPreviewTransform() {
        if (imageView == null) {
            return;
        }
        if (previewZoom <= 1.01f) {
            previewZoom = 1f;
            previewPanX = 0f;
            previewPanY = 0f;
        }
        float maxPanX = Math.max(0f, imageView.getWidth() * (previewZoom - 1f) * 0.5f);
        float maxPanY = Math.max(0f, imageView.getHeight() * (previewZoom - 1f) * 0.5f);
        previewPanX = clamp(previewPanX, -maxPanX, maxPanX);
        previewPanY = clamp(previewPanY, -maxPanY, maxPanY);
        imageView.setScaleX(previewZoom);
        imageView.setScaleY(previewZoom);
        imageView.setTranslationX(previewPanX);
        imageView.setTranslationY(previewPanY);
    }

    private void startWhiteBalancePicker() {
        whiteBalancePickMode = true;
        localPickMode = false;
        renderControls();
        Toast.makeText(this, "点一下图片中的灰白区域", Toast.LENGTH_SHORT).show();
    }

    private void startLocalPicker() {
        localPickMode = true;
        whiteBalancePickMode = false;
        adjustments.localEnabled = 1f;
        renderControls();
        Toast.makeText(this, "点一下图片设置局部调整中心", Toast.LENGTH_SHORT).show();
    }

    private void applyWhiteBalanceFromTap(float x, float y) {
        if (originalBitmap == null || imageView == null) {
            return;
        }
        float nx = clamp(x / Math.max(1f, imageView.getWidth()), 0f, 1f);
        float ny = clamp(y / Math.max(1f, imageView.getHeight()), 0f, 1f);
        int px = Math.max(0, Math.min(originalBitmap.getWidth() - 1,
                Math.round(nx * (originalBitmap.getWidth() - 1))));
        int py = Math.max(0, Math.min(originalBitmap.getHeight() - 1,
                Math.round(ny * (originalBitmap.getHeight() - 1))));
        int color = originalBitmap.getPixel(px, py);
        float r = Color.red(color) / 255f;
        float g = Color.green(color) / 255f;
        float b = Color.blue(color) / 255f;
        pushUndoSnapshot("白平衡吸管");
        adjustments.temperature = clamp(adjustments.temperature + (b - r) * 1.35f, -1f, 1f);
        adjustments.tint = clamp(adjustments.tint + ((r + b) * 0.5f - g) * 1.25f, -1f, 1f);
        whiteBalancePickMode = false;
        renderControls();
        renderPreview(false);
        Toast.makeText(this, "已校正白平衡", Toast.LENGTH_SHORT).show();
    }

    private void applyLocalCenterFromTap(float x, float y) {
        if (imageView == null) {
            return;
        }
        pushUndoSnapshot("局部中心");
        adjustments.localEnabled = 1f;
        adjustments.localX = clamp(x / Math.max(1f, imageView.getWidth()), 0f, 1f);
        adjustments.localY = clamp(y / Math.max(1f, imageView.getHeight()), 0f, 1f);
        localPickMode = false;
        renderControls();
        renderPreview(false);
        Toast.makeText(this, "已设置局部中心", Toast.LENGTH_SHORT).show();
    }

    private void autoEnhance() {
        Bitmap source = fastSourceBitmap != null ? fastSourceBitmap : originalBitmap;
        if (source == null || source.isRecycled()) {
            return;
        }
        long luminanceSum = 0L;
        float saturationSum = 0f;
        int samples = 0;
        float[] hsv = new float[3];
        int stepX = Math.max(1, source.getWidth() / 48);
        int stepY = Math.max(1, source.getHeight() / 48);
        for (int y = 0; y < source.getHeight(); y += stepY) {
            for (int x = 0; x < source.getWidth(); x += stepX) {
                int color = source.getPixel(x, y);
                int r = Color.red(color);
                int g = Color.green(color);
                int b = Color.blue(color);
                luminanceSum += Math.round(r * 0.299f + g * 0.587f + b * 0.114f);
                Color.colorToHSV(color, hsv);
                saturationSum += hsv[1];
                samples++;
            }
        }
        if (samples == 0) {
            return;
        }
        float averageLuminance = luminanceSum / (255f * samples);
        float averageSaturation = saturationSum / samples;
        pushUndoSnapshot("一键优化");
        adjustments.exposure = clamp(adjustments.exposure + (0.5f - averageLuminance) * 0.85f, -1f, 1f);
        adjustments.contrast = clamp(adjustments.contrast + 0.12f, -1f, 1f);
        adjustments.highlights = clamp(adjustments.highlights - Math.max(0f, averageLuminance - 0.58f) * 0.55f,
                -1f, 1f);
        adjustments.shadows = clamp(adjustments.shadows + Math.max(0f, 0.45f - averageLuminance) * 0.55f,
                -1f, 1f);
        adjustments.saturation = clamp(adjustments.saturation + (0.42f - averageSaturation) * 0.35f,
                -1f, 1f);
        renderControls();
        renderPreview(false);
        Toast.makeText(this, "已自动优化", Toast.LENGTH_SHORT).show();
    }

    private View createControlPanel(boolean landscape) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        setGradientBackground(panel, Color.rgb(16, 20, 28), Color.rgb(11, 14, 20),
                GradientDrawable.Orientation.TOP_BOTTOM);
        panel.setPadding(landscape ? dp(10) : 0, 0, landscape ? dp(10) : 0, 0);
        panelTabs = new LinearLayout(this);
        panelTabs.setOrientation(LinearLayout.HORIZONTAL);
        panelTabs.setPadding(dp(12), dp(10), dp(12), dp(10));
        setGradientBackground(panelTabs, Color.rgb(20, 25, 34), Color.rgb(13, 17, 24),
                GradientDrawable.Orientation.LEFT_RIGHT);
        panel.addView(panelTabs, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(62)));
        rebuildPanelTabs();

        controlScroll = new ScrollView(this);
        controlScroll.setFillViewport(false);
        controlScroll.setBackgroundColor(Color.rgb(14, 18, 25));
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(18), dp(12), dp(18), landscape ? dp(28) : dp(24));
        controls.setBackgroundColor(Color.rgb(14, 18, 25));
        controlScroll.addView(controls);
        panel.addView(controlScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return panel;
    }

    private int landscapePanelWidth() {
        int widthPixels = getResources().getDisplayMetrics().widthPixels;
        int target = Math.round(widthPixels * 0.32f);
        return Math.max(dp(300), Math.min(dp(380), target));
    }

    private View createToolbar() {
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(14), dp(7), dp(14), dp(7));
        setGradientBackground(toolbar, Color.rgb(22, 27, 36), Color.rgb(12, 15, 21),
                GradientDrawable.Orientation.LEFT_RIGHT);

        TextView title = new TextView(this);
        title.setText("MyLight");
        title.setTextColor(Color.WHITE);
        title.setTextSize(21f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button openButton = createButton("打开");
        openButton.setOnClickListener(v -> openImage());
        addToolbarButton(actions, openButton);

        undoToolbarButton = createButton("↶");
        undoToolbarButton.setOnClickListener(v -> undoLastEdit());
        addToolbarIconButton(actions, undoToolbarButton);

        redoToolbarButton = createButton("↷");
        redoToolbarButton.setOnClickListener(v -> redoLastEdit());
        addToolbarIconButton(actions, redoToolbarButton);

        Button saveButton = createButton("保存");
        saveButton.setOnClickListener(v -> saveImage(null));
        addToolbarButton(actions, saveButton);

        Button moreButton = createButton("⋯");
        moreButton.setOnClickListener(v -> showMoreActions());
        addToolbarIconButton(actions, moreButton);
        toolbar.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        updateHistoryButtons();
        return toolbar;
    }

    private void addToolbarButton(LinearLayout row, Button button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(54), dp(42));
        params.leftMargin = dp(6);
        row.addView(button, params);
    }

    private void addToolbarIconButton(LinearLayout row, Button button) {
        button.setTextSize(18f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(42), dp(42));
        params.leftMargin = dp(6);
        row.addView(button, params);
    }

    private void showMoreActions() {
        new AlertDialog.Builder(this)
                .setTitle("更多操作")
                .setItems(new String[] {
                        "历史记录",
                        "导出设置",
                        clippingWarningEnabled ? "关闭裁切警告" : "开启裁切警告",
                        "批量选择图片",
                        "批量导出当前效果",
                        "导入滤镜",
                        "导出滤镜",
                        "重置全部",
                        "重置预览缩放",
                        "长按图片可对比原图"
                }, (dialog, which) -> {
                    if (which == 0) {
                        showHistoryDialog();
                    } else if (which == 1) {
                        showExportSettingsDialog();
                    } else if (which == 2) {
                        clippingWarningEnabled = !clippingWarningEnabled;
                        renderPreview(false);
                    } else if (which == 3) {
                        openBatchImages();
                    } else if (which == 4) {
                        exportBatchImages();
                    } else if (which == 5) {
                        showImportFiltersDialog();
                    } else if (which == 6) {
                        showExportFiltersDialog();
                    } else if (which == 7) {
                        resetAll();
                    } else if (which == 8) {
                        previewZoom = 1f;
                        previewPanX = 0f;
                        previewPanY = 0f;
                        applyPreviewTransform();
                    } else {
                        Toast.makeText(this, "按住预览图查看原图，松开恢复当前效果", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void rebuildPanelTabs() {
        panelTabs.removeAllViews();
        addPrimaryPanelTab("滤镜", PANEL_FILTER, activePanel == PANEL_FILTER,
                panelHasChanges(PANEL_FILTER));
        addPrimaryPanelTab("调节", activeAdjustPanel, isAdjustPanel(activePanel),
                adjustPanelsHaveChanges());
        addPrimaryPanelTab("曲线", PANEL_CURVE, activePanel == PANEL_CURVE,
                panelHasChanges(PANEL_CURVE));
        addPrimaryPanelTab("裁剪", PANEL_SIZE, activePanel == PANEL_SIZE,
                panelHasChanges(PANEL_SIZE));
    }

    private void addPrimaryPanelTab(String label, int panel, boolean selected, boolean changed) {
        String displayLabel = changed ? label + " •" : label;
        Button button = createButton(displayLabel, selected);
        button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        button.setOnClickListener(v -> {
            activePanel = panel;
            if (isAdjustPanel(panel)) {
                activeAdjustPanel = panel;
            }
            rebuildPanelTabs();
            renderControls();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1f);
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        panelTabs.addView(button, params);
    }

    private void renderControls() {
        if (panelTabs != null) {
            rebuildPanelTabs();
        }
        controls.removeAllViews();
        sliderBindings.clear();
        curveView = null;
        if (isAdjustPanel(activePanel)) {
            activeAdjustPanel = activePanel;
            renderAdjustSwitcher();
        }
        if (activePanel == PANEL_SIZE) {
            renderSizePanel();
        } else if (activePanel == PANEL_FILTER) {
            renderFilterPanel();
        } else if (activePanel == PANEL_LIGHT) {
            renderLightPanel();
        } else if (activePanel == PANEL_HSL) {
            renderHslPanel();
        } else if (activePanel == PANEL_CURVE) {
            renderCurvePanel();
        } else if (activePanel == PANEL_EFFECTS) {
            renderEffectsPanel();
        } else {
            renderColorPanel();
        }
        updateCropOverlay();
    }

    private void renderAdjustSwitcher() {
        GridLayout grid = createButtonGrid(4);
        addAdjustButton(grid, "光线", PANEL_LIGHT);
        addAdjustButton(grid, "色彩", PANEL_COLOR);
        addAdjustButton(grid, "HSL", PANEL_HSL);
        addAdjustButton(grid, "效果", PANEL_EFFECTS);
        controls.addView(grid);
    }

    private void addAdjustButton(GridLayout grid, String label, int panel) {
        String displayLabel = panelHasChanges(panel) ? label + " •" : label;
        addColorModeButton(grid, displayLabel, semanticAccent(label), activePanel == panel, () -> {
            activePanel = panel;
            activeAdjustPanel = panel;
            rebuildPanelTabs();
            renderControls();
        });
    }

    private void renderSizePanel() {
        controls.addView(createSectionLabel("裁剪比例"));
        LinearLayout row = createButtonRow();
        addModeButton(row, "自由", geometry.cropMode == GeometryAdjustments.CROP_FREE,
                () -> setCropMode(GeometryAdjustments.CROP_FREE));
        addModeButton(row, "原图", geometry.cropMode == GeometryAdjustments.CROP_ORIGINAL,
                () -> setCropMode(GeometryAdjustments.CROP_ORIGINAL));
        addModeButton(row, "1:1", geometry.cropMode == GeometryAdjustments.CROP_SQUARE,
                () -> setCropMode(GeometryAdjustments.CROP_SQUARE));
        controls.addView(row);

        LinearLayout ratioRow = createButtonRow();
        addModeButton(ratioRow, "4:3", geometry.cropMode == GeometryAdjustments.CROP_4_3,
                () -> setCropMode(GeometryAdjustments.CROP_4_3));
        addModeButton(ratioRow, "3:4", geometry.cropMode == GeometryAdjustments.CROP_3_4,
                () -> setCropMode(GeometryAdjustments.CROP_3_4));
        addModeButton(ratioRow, "16:9", geometry.cropMode == GeometryAdjustments.CROP_16_9,
                () -> setCropMode(GeometryAdjustments.CROP_16_9));
        addModeButton(ratioRow, "9:16", geometry.cropMode == GeometryAdjustments.CROP_9_16,
                () -> setCropMode(GeometryAdjustments.CROP_9_16));
        controls.addView(ratioRow);
        LinearLayout cropRow = createButtonRow();
        addModeButton(cropRow, "重置裁剪", false, () -> {
            pushUndoSnapshot();
            geometry.resetCropForMode(sourceAspect());
            cropOverlayView.invalidate();
            renderPreview();
        });
        addModeButton(cropRow, "完成", true, this::finishCrop);
        controls.addView(cropRow);
        controls.addView(createSectionLabel("辅助网格"));
        LinearLayout gridRow = createButtonRow();
        addModeButton(gridRow, "三分线", cropGridMode == CropOverlayView.GRID_THIRDS,
                () -> setCropGridMode(CropOverlayView.GRID_THIRDS));
        addModeButton(gridRow, "黄金", cropGridMode == CropOverlayView.GRID_GOLDEN,
                () -> setCropGridMode(CropOverlayView.GRID_GOLDEN));
        addModeButton(gridRow, "中心", cropGridMode == CropOverlayView.GRID_CENTER,
                () -> setCropGridMode(CropOverlayView.GRID_CENTER));
        addModeButton(gridRow, "无", cropGridMode == CropOverlayView.GRID_NONE,
                () -> setCropGridMode(CropOverlayView.GRID_NONE));
        controls.addView(gridRow);
        addSlider("裁剪缩放", geometry.cropZoom, 0f, 1f, value -> geometry.cropZoom = value);
        addSlider("任意旋转", geometry.rotateDegrees, -45f, 45f, value -> geometry.rotateDegrees = value);

        LinearLayout rotateRow = createButtonRow();
        addModeButton(rotateRow, "左转90", false, () -> {
            pushUndoSnapshot();
            geometry.quarterTurns = (geometry.quarterTurns + 3) % 4;
            renderPreview();
        });
        addModeButton(rotateRow, "右转90", false, () -> {
            pushUndoSnapshot();
            geometry.quarterTurns = (geometry.quarterTurns + 1) % 4;
            renderPreview();
        });
        addModeButton(rotateRow, "归零", false, () -> {
            pushUndoSnapshot();
            geometry.rotateDegrees = 0f;
            geometry.quarterTurns = 0;
            renderControls();
            renderPreview();
        });
        controls.addView(rotateRow);
    }

    private void finishCrop() {
        activePanel = PANEL_FILTER;
        persistCurrentEdit();
        rebuildPanelTabs();
        renderControls();
        renderPreview();
        Toast.makeText(this, "已应用裁剪", Toast.LENGTH_SHORT).show();
    }

    private void renderFilterPanel() {
        controls.addView(createSectionLabel("预设滤镜"));
        controls.addView(createPresetStrip());
        if (activeFilterPreset != null) {
            addSlider("滤镜强度", filterStrength, 0f, 1f, value -> {
                filterStrength = value;
                applyFilterStrength();
            });
        }
    }

    private void renderLightPanel() {
        controls.addView(createSectionLabel("光线"));
        LinearLayout actionRow = createButtonRow();
        addModeButton(actionRow, "一键优化", false, this::autoEnhance);
        addModeButton(actionRow, "历史记录", false, this::showHistoryDialog);
        controls.addView(actionRow);
        addSlider("曝光", adjustments.exposure, -1f, 1f, value -> adjustments.exposure = value);
        addSlider("明亮度", adjustments.brightness, -1f, 1f, value -> adjustments.brightness = value);
        addSlider("高光", adjustments.highlights, -1f, 1f, value -> adjustments.highlights = value);
        addSlider("阴影", adjustments.shadows, -1f, 1f, value -> adjustments.shadows = value);
        addSlider("对比度", adjustments.contrast, -1f, 1f, value -> adjustments.contrast = value);
    }

    private void renderColorPanel() {
        controls.addView(createSectionLabel("基础色彩"));
        LinearLayout actionRow = createButtonRow();
        addModeButton(actionRow, "白平衡吸管", whiteBalancePickMode, this::startWhiteBalancePicker);
        addModeButton(actionRow, clippingWarningEnabled ? "裁切警告开" : "裁切警告", clippingWarningEnabled, () -> {
            clippingWarningEnabled = !clippingWarningEnabled;
            renderControls();
            renderPreview(false);
        });
        controls.addView(actionRow);
        addSlider("饱和度", adjustments.saturation, -1f, 1f, value -> adjustments.saturation = value);
        addSlider("色温", adjustments.temperature, -1f, 1f, value -> adjustments.temperature = value);
        addSlider("色调", adjustments.tint, -1f, 1f, value -> adjustments.tint = value);
    }

    private void renderHslPanel() {
        controls.addView(createSectionLabel("原色 / HSL"));
        GridLayout row = createButtonGrid(4);
        String[] names = {"红", "橙", "黄", "绿", "青", "蓝", "紫", "洋红"};
        for (int i = 0; i < names.length; i++) {
            final int channel = i;
            addColorModeButton(row, names[i], hslColor(channel), activeMixChannel == channel, () -> {
                activeMixChannel = channel;
                renderControls();
            });
        }
        controls.addView(row);
        addSlider("色相", adjustments.mixHue[activeMixChannel], -1f, 1f,
                value -> adjustments.mixHue[activeMixChannel] = value);
        addSlider("饱和度", adjustments.mixSaturation[activeMixChannel], -1f, 1f,
                value -> adjustments.mixSaturation[activeMixChannel] = value);
        addSlider("明亮度", adjustments.mixLuminance[activeMixChannel], -1f, 1f,
                value -> adjustments.mixLuminance[activeMixChannel] = value);
    }

    private void renderCurvePanel() {
        controls.addView(createSectionLabel("曲线通道"));
        LinearLayout row = createButtonRow();
        addCurveButton(row, "亮度", CurveSet.LUMINANCE, Color.rgb(95, 179, 243));
        addCurveButton(row, "红", CurveSet.RED, Color.rgb(238, 91, 91));
        addCurveButton(row, "绿", CurveSet.GREEN, Color.rgb(101, 196, 122));
        addCurveButton(row, "蓝", CurveSet.BLUE, Color.rgb(93, 145, 245));
        controls.addView(row);

        curveView = new CurveView(this, curves.curveFor(activeCurveChannel));
        curveView.setCurveColor(curveColor(activeCurveChannel));
        curveView.setListener(new CurveView.Listener() {
            @Override
            public void onCurveStarted() {
                pushUndoSnapshot();
            }

            @Override
            public void onCurveChanged(boolean finished) {
                if (finished) {
                    rebuildPanelTabs();
                    renderPreview(false);
                } else {
                    renderInteractivePreview();
                }
            }
        });
        controls.addView(curveView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220)));

        LinearLayout curveActions = createButtonRow();
        addModeButton(curveActions, "删除锚点", false, () -> {
            if (curveView == null || !curveView.hasDeletableSelection()) {
                Toast.makeText(this, "请选择可删除的中间锚点", Toast.LENGTH_SHORT).show();
                return;
            }
            pushUndoSnapshot();
            if (curveView.deleteSelectedPoint()) {
                rebuildPanelTabs();
                renderPreview(false);
            }
        });
        addModeButton(curveActions, "重置当前", false, () -> {
            pushUndoSnapshot();
            curves.reset(activeCurveChannel);
            curveView.invalidate();
            renderPreview();
        });
        addModeButton(curveActions, "重置全部", false, () -> {
            pushUndoSnapshot();
            curves.reset();
            curveView.setCurve(curves.curveFor(activeCurveChannel));
            renderPreview();
        });
        controls.addView(curveActions);
    }

    private void renderEffectsPanel() {
        controls.addView(createSectionLabel("效果"));
        addSlider("晕影", adjustments.vignette, -1f, 1f, value -> adjustments.vignette = value);
        addSlider("去模糊", adjustments.dehaze, -1f, 1f, value -> adjustments.dehaze = value);
        addSlider("氛围", adjustments.ambiance, -1f, 1f, value -> adjustments.ambiance = value);
        addSlider("褪色", adjustments.fade, 0f, 1f, value -> adjustments.fade = value);
        controls.addView(createSectionLabel("局部调整"));
        LinearLayout localRow = createButtonRow();
        addModeButton(localRow, adjustments.localEnabled > 0.5f ? "局部开" : "开启局部",
                adjustments.localEnabled > 0.5f, () -> {
                    pushUndoSnapshot("局部调整");
                    adjustments.localEnabled = adjustments.localEnabled > 0.5f ? 0f : 1f;
                    renderControls();
                    renderPreview(false);
                });
        addModeButton(localRow, "点选中心", localPickMode, this::startLocalPicker);
        controls.addView(localRow);
        addSlider("局部半径", adjustments.localRadius, 0.12f, 0.8f, value -> {
            adjustments.localEnabled = 1f;
            adjustments.localRadius = value;
        });
        addSlider("局部羽化", adjustments.localFeather, 0f, 1f, value -> {
            adjustments.localEnabled = 1f;
            adjustments.localFeather = value;
        });
        addSlider("局部曝光", adjustments.localExposure, -1f, 1f, value -> {
            adjustments.localEnabled = 1f;
            adjustments.localExposure = value;
        });
        addSlider("局部饱和", adjustments.localSaturation, -1f, 1f, value -> {
            adjustments.localEnabled = 1f;
            adjustments.localSaturation = value;
        });
    }

    private View createPresetStrip() {
        HorizontalScrollView scrollView = new HorizontalScrollView(this);
        scrollView.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(10));
        Preset lastEdit = loadLastEditPreset();
        if (lastEdit != null) {
            Button button = createPresetButton("上次修改\n记忆", true, lastEdit);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(112), dp(62));
            params.rightMargin = dp(8);
            row.addView(button, params);
        }
        Button saveFilterButton = createButton("存为滤镜\n当前", true);
        saveFilterButton.setOnClickListener(v -> showSaveFilterDialog());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(dp(112), dp(62));
        saveParams.rightMargin = dp(8);
        row.addView(saveFilterButton, saveParams);
        for (Preset preset : Preset.defaults()) {
            Button button = createPresetButton(preset.name + "\n默认", false, preset);
            button.setOnLongClickListener(v -> {
                Toast.makeText(this, "默认滤镜不可管理，可保存为自定义滤镜", Toast.LENGTH_SHORT).show();
                return true;
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(96), dp(62));
            params.rightMargin = dp(8);
            row.addView(button, params);
        }
        List<Preset> customPresets = loadCustomPresets();
        for (int i = 0; i < customPresets.size(); i++) {
            final int index = i;
            Preset preset = customPresets.get(i);
            Button button = createPresetButton(preset.name + "\n自定义", false, preset);
            button.setOnLongClickListener(v -> {
                showCustomPresetMenu(index, preset.name);
                return true;
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(108), dp(62));
            params.rightMargin = dp(8);
            row.addView(button, params);
        }
        scrollView.addView(row);
        return scrollView;
    }

    private Button createPresetButton(String label, boolean selected, Preset preset) {
        Button button = createButton(label, selected || isActiveFilter(preset));
        button.setOnClickListener(v -> applyPreset(preset));
        button.setGravity(Gravity.CENTER);
        return button;
    }

    private boolean isActiveFilter(Preset preset) {
        return activeFilterPreset != null && activeFilterPreset.name.equals(preset.name);
    }

    private TextView createSectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.rgb(238, 243, 249));
        label.setTextSize(14f);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPadding(dp(2), dp(18), 0, dp(10));
        return label;
    }

    private LinearLayout createButtonRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(10));
        return row;
    }

    private GridLayout createButtonGrid(int columns) {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(columns);
        grid.setPadding(0, 0, 0, dp(10));
        grid.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return grid;
    }

    private void addModeButton(LinearLayout row, String label, boolean selected, Runnable action) {
        Button button = createButton(label, selected);
        button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1f);
        params.leftMargin = dp(3);
        params.rightMargin = dp(3);
        row.addView(button, params);
    }

    private void addColorModeButton(GridLayout grid, String label, int accent, boolean selected, Runnable action) {
        Button button = createButton(label, selected, accent);
        button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        button.setOnClickListener(v -> action.run());
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(44);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(3), dp(3), dp(3), dp(5));
        grid.addView(button, params);
    }

    private void addCurveButton(LinearLayout row, String label, int channel, int color) {
        addModeButton(row, label, activeCurveChannel == channel, () -> {
            activeCurveChannel = channel;
            if (curveView != null) {
                curveView.setCurve(curves.curveFor(activeCurveChannel));
                curveView.setCurveColor(color);
            }
            renderControls();
        });
    }

    private void addSlider(String label, float initialValue, float min, float max, SliderConsumer consumer) {
        LinearLayout sliderRow = new LinearLayout(this);
        sliderRow.setOrientation(LinearLayout.HORIZONTAL);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        sliderRow.setPadding(0, dp(4), 0, dp(8));

        TextView nameLabel = new TextView(this);
        nameLabel.setText(label);
        nameLabel.setTextColor(floatChanged(initialValue) ? Color.WHITE : Color.rgb(202, 211, 224));
        nameLabel.setTextSize(13f);
        nameLabel.setTypeface(Typeface.DEFAULT, floatChanged(initialValue) ? Typeface.BOLD : Typeface.NORMAL);
        sliderRow.addView(nameLabel, new LinearLayout.LayoutParams(dp(70), dp(42)));

        int sliderAccent = sliderAccent(label);
        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(200);
        seekBar.setProgressTintList(ColorStateList.valueOf(sliderAccent));
        seekBar.setThumbTintList(ColorStateList.valueOf(Color.rgb(232, 244, 255)));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(blend(Color.rgb(43, 50, 62),
                sliderAccent, 0.16f)));
        seekBar.setSplitTrack(false);
        sliderRow.addView(seekBar, new LinearLayout.LayoutParams(0, dp(42), 1f));

        EditText valueInput = new EditText(this);
        valueInput.setTextColor(Color.rgb(232, 237, 244));
        valueInput.setTextSize(12f);
        valueInput.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        valueInput.setGravity(Gravity.CENTER);
        valueInput.setSingleLine(true);
        valueInput.setSelectAllOnFocus(true);
        valueInput.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        valueInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        valueInput.setPadding(dp(4), 0, dp(4), 0);
        GradientDrawable inputBackground = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {Color.rgb(28, 34, 44), Color.rgb(17, 21, 29)});
        inputBackground.setStroke(dp(1), blend(Color.rgb(68, 78, 95), sliderAccent, 0.28f));
        inputBackground.setCornerRadius(dp(8));
        valueInput.setBackground(inputBackground);
        sliderRow.addView(valueInput, new LinearLayout.LayoutParams(dp(58), dp(36)));

        Button resetButton = createButton("0", false, Color.rgb(232, 162, 80));
        sliderRow.addView(resetButton, new LinearLayout.LayoutParams(dp(38), dp(34)));

        SliderBinding binding = new SliderBinding(seekBar, initialValue, min, max);
        sliderBindings.add(binding);
        setSeekValue(seekBar, initialValue, min, max);
        updateSliderLabel(valueInput, label, initialValue);
        attachSliderDoubleTap(seekBar, binding, valueInput, nameLabel, label, min, max, consumer);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float value = min + (max - min) * progress / 200f;
                consumer.accept(value);
                binding.value = value;
                updateSliderLabel(valueInput, label, value);
                nameLabel.setTextColor(floatChanged(value) ? Color.WHITE : Color.rgb(202, 211, 224));
                nameLabel.setTypeface(Typeface.DEFAULT, floatChanged(value) ? Typeface.BOLD : Typeface.NORMAL);
                if (!suppressSliderEvents) {
                    if (fromUser) {
                        renderInteractivePreview();
                    } else {
                        renderPreview(false);
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                pushUndoSnapshot();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                rebuildPanelTabs();
                renderPreview(false);
            }
        });
        valueInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_UNSPECIFIED) {
                commitSliderInput(valueInput, seekBar, binding, nameLabel, label, min, max, consumer);
                valueInput.clearFocus();
                return true;
            }
            return false;
        });
        valueInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) {
                commitSliderInput(valueInput, seekBar, binding, nameLabel, label, min, max, consumer);
            }
        });
        resetButton.setOnClickListener(v -> {
            float resetValue = min <= 0f && max >= 0f ? 0f : min;
            if (Math.abs(binding.value - resetValue) < 0.0001f) {
                return;
            }
            pushUndoSnapshot();
            consumer.accept(resetValue);
            binding.value = resetValue;
            suppressSliderEvents = true;
            setSeekValue(seekBar, resetValue, min, max);
            suppressSliderEvents = false;
            updateSliderLabel(valueInput, label, resetValue);
            nameLabel.setTextColor(Color.rgb(202, 211, 224));
            nameLabel.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            renderControls();
            renderPreview(false);
        });
        controls.addView(sliderRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));
    }

    private void attachSliderDoubleTap(SeekBar seekBar, SliderBinding binding, TextView valueLabel,
            TextView nameLabel, String label, float min, float max, SliderConsumer consumer) {
        final long[] lastTapTime = {0L};
        final float[] lastTapX = {0f};
        final boolean[] consumingDoubleTap = {false};
        seekBar.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                long now = event.getEventTime();
                boolean isDoubleTap = now - lastTapTime[0] <= ViewConfiguration.getDoubleTapTimeout()
                        && Math.abs(event.getX() - lastTapX[0]) <= dp(48);
                if (isDoubleTap) {
                    consumingDoubleTap[0] = true;
                    lastTapTime[0] = 0L;
                    float direction = event.getX() < view.getWidth() * 0.5f ? -1f : 1f;
                    applySliderValue(seekBar, binding, valueLabel, nameLabel, label,
                            binding.value + direction * sliderStep(min, max), min, max, consumer, true);
                    return true;
                }
                consumingDoubleTap[0] = false;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (consumingDoubleTap[0]) {
                    consumingDoubleTap[0] = false;
                    return true;
                }
                if (action == MotionEvent.ACTION_UP) {
                    lastTapTime[0] = event.getEventTime();
                    lastTapX[0] = event.getX();
                }
            }
            return false;
        });
    }

    private void commitSliderInput(EditText input, SeekBar seekBar, SliderBinding binding,
            TextView nameLabel, String label, float min, float max, SliderConsumer consumer) {
        String rawValue = input.getText().toString().trim();
        if (rawValue.isEmpty() || "-".equals(rawValue) || ".".equals(rawValue) || "-.".equals(rawValue)) {
            updateSliderLabel(input, label, binding.value);
            return;
        }
        try {
            applySliderValue(seekBar, binding, input, nameLabel, label,
                    Float.parseFloat(rawValue), min, max, consumer, true);
        } catch (NumberFormatException exception) {
            updateSliderLabel(input, label, binding.value);
            Toast.makeText(this, "请输入有效数值", Toast.LENGTH_SHORT).show();
        }
    }

    private void applySliderValue(SeekBar seekBar, SliderBinding binding, TextView valueLabel,
            TextView nameLabel, String label, float value, float min, float max,
            SliderConsumer consumer, boolean pushUndo) {
        float clamped = clamp(value, min, max);
        int progress = sliderProgress(clamped, min, max);
        float steppedValue = min + (max - min) * progress / 200f;
        if (Math.abs(binding.value - steppedValue) < 0.0001f) {
            updateSliderLabel(valueLabel, label, steppedValue);
            return;
        }
        if (pushUndo) {
            pushUndoSnapshot();
        }
        suppressSliderEvents = true;
        seekBar.setProgress(progress);
        suppressSliderEvents = false;
        consumer.accept(steppedValue);
        binding.value = steppedValue;
        updateSliderLabel(valueLabel, label, steppedValue);
        nameLabel.setTextColor(floatChanged(steppedValue) ? Color.WHITE : Color.rgb(202, 211, 224));
        nameLabel.setTypeface(Typeface.DEFAULT, floatChanged(steppedValue) ? Typeface.BOLD : Typeface.NORMAL);
        rebuildPanelTabs();
        renderPreview(false);
    }

    private void updateSliderLabel(TextView label, String name, float value) {
        label.setText(String.format(java.util.Locale.US, "%.2f", value));
    }

    private void setSeekValue(SeekBar seekBar, float value, float min, float max) {
        seekBar.setProgress(sliderProgress(value, min, max));
    }

    private int sliderProgress(float value, float min, float max) {
        int progress = Math.round((value - min) * 200f / (max - min));
        return Math.max(0, Math.min(200, progress));
    }

    private float sliderStep(float min, float max) {
        return (max - min) / 200f;
    }

    private void setCropMode(int mode) {
        pushUndoSnapshot();
        geometry.cropMode = mode;
        geometry.resetCropForMode(sourceAspect());
        if (cropOverlayView != null) {
            cropOverlayView.invalidate();
        }
        renderControls();
        renderPreview();
    }

    private void setCropGridMode(int mode) {
        cropGridMode = mode;
        if (cropOverlayView != null) {
            cropOverlayView.setGridMode(mode);
        }
        renderControls();
    }

    private void applyPreset(Preset preset) {
        pushUndoSnapshot();
        activeFilterPreset = preset;
        filterBaseAdjustments = adjustments.copy();
        filterBaseCurves = curves.copy();
        filterStrength = 1f;
        applyFilterStrength();
        renderControls();
        renderPreview();
    }

    private void applyFilterStrength() {
        if (activeFilterPreset == null || filterBaseAdjustments == null || filterBaseCurves == null) {
            return;
        }
        mixAdjustments(filterBaseAdjustments, activeFilterPreset.adjustments, filterStrength, adjustments);
        curves = mixCurves(filterBaseCurves, activeFilterPreset.curves, filterStrength);
    }

    private void resetAll() {
        pushUndoSnapshot();
        resetAllInternal();
    }

    private void resetAllInternal() {
        activeFilterPreset = null;
        filterBaseAdjustments = null;
        filterBaseCurves = null;
        filterStrength = 1f;
        geometry.reset();
        adjustments.reset();
        curves.reset();
        renderControls();
        renderPreview();
    }

    private void renderPreview() {
        renderPreview(false);
    }

    private void renderInteractivePreview() {
        renderPreview(true);
    }

    private void renderPreview(boolean interactive) {
        if (imageView == null) {
            return;
        }
        if (compareActive) {
            renderComparePreview();
            return;
        }
        if (compareLabel != null) {
            compareLabel.setVisibility(View.GONE);
        }
        persistCurrentEdit();
        imageView.updateState(previewGeometry(), adjustments, curves, previewDisplayAspect(),
                clippingWarningEnabled);
        updateHistogramAsync();
    }

    private void showHistoryDialog() {
        if (undoStack.isEmpty()) {
            Toast.makeText(this, "暂无历史记录", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[undoStack.size()];
        for (int i = 0; i < items.length; i++) {
            String label = i < undoLabels.size() ? undoLabels.get(i) : "参数调整";
            items[i] = "回到 " + (items.length - i) + " 步前 · " + label;
        }
        new AlertDialog.Builder(this)
                .setTitle("历史记录")
                .setItems(items, (dialog, which) -> restoreHistorySnapshot(which))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void restoreHistorySnapshot(int index) {
        List<EditSnapshot> snapshots = new ArrayList<>(undoStack);
        if (index < 0 || index >= snapshots.size()) {
            return;
        }
        redoStack.push(new EditSnapshot(geometry.copy(), adjustments.copy(), curves.copy()));
        EditSnapshot snapshot = snapshots.get(index);
        copyGeometry(snapshot.geometry, geometry);
        copyAdjustments(snapshot.adjustments, adjustments);
        curves = snapshot.curves.copy();
        for (int i = 0; i <= index && !undoStack.isEmpty(); i++) {
            undoStack.pop();
            if (!undoLabels.isEmpty()) {
                undoLabels.remove(0);
            }
        }
        clearActiveFilter();
        renderControls();
        renderPreview(false);
        updateHistoryButtons();
    }

    private void showExportSettingsDialog() {
        String[] sizeLabels = {"原图尺寸", "长边 2400", "长边 1600", "长边 1080"};
        String[] qualityLabels = {"质量 100", "质量 95", "质量 90", "质量 80"};
        int[] qualityValues = {100, 95, 90, 80};
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(8), dp(18), 0);
        TextView sizeLabel = createSectionLabel("导出尺寸");
        layout.addView(sizeLabel);
        final int[] nextSize = {exportSizeMode};
        for (int i = 0; i < sizeLabels.length; i++) {
            final int index = i;
            Button button = createButton(sizeLabels[i], exportSizeMode == i);
            button.setOnClickListener(v -> {
                nextSize[0] = index;
                showExportSettingsDialog();
            });
            button.setTag(index);
            layout.addView(button, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
        }
        layout.addView(createSectionLabel("JPEG 质量"));
        final int[] nextQuality = {exportQuality};
        for (int i = 0; i < qualityLabels.length; i++) {
            final int value = qualityValues[i];
            Button button = createButton(qualityLabels[i], exportQuality == value);
            button.setOnClickListener(v -> {
                nextQuality[0] = value;
                exportQuality = value;
                preferences.edit().putInt(KEY_EXPORT_QUALITY, exportQuality).apply();
                Toast.makeText(this, "导出质量已设为 " + value, Toast.LENGTH_SHORT).show();
            });
            layout.addView(button, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
        }
        new AlertDialog.Builder(this)
                .setTitle("导出设置")
                .setSingleChoiceItems(sizeLabels, exportSizeMode, (dialog, which) -> nextSize[0] = which)
                .setPositiveButton("保存", (dialog, which) -> {
                    exportSizeMode = nextSize[0];
                    preferences.edit()
                            .putInt(KEY_EXPORT_SIZE, exportSizeMode)
                            .putInt(KEY_EXPORT_QUALITY, exportQuality)
                            .apply();
                    Toast.makeText(this, "导出设置已保存", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("质量", (dialog, which) -> showExportQualityDialog(qualityLabels, qualityValues))
                .setNegativeButton("取消", null)
                .show();
    }

    private void showExportQualityDialog(String[] labels, int[] values) {
        int checked = 1;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == exportQuality) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("JPEG 质量")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    exportQuality = values[which];
                    preferences.edit().putInt(KEY_EXPORT_QUALITY, exportQuality).apply();
                    dialog.dismiss();
                })
                .show();
    }

    private void renderComparePreview() {
        if (imageView == null) {
            return;
        }
        if (compareLabel != null) {
            compareLabel.setVisibility(View.VISIBLE);
        }
        imageView.updateState(previewGeometry(), new ColorAdjustments(), new CurveSet(),
                previewDisplayAspect(), false);
    }

    private void updateHistogramAsync() {
        if (histogramView == null) {
            return;
        }
        Bitmap source = fastSourceBitmap != null ? fastSourceBitmap : originalBitmap;
        if (source == null || source.isRecycled()) {
            return;
        }
        int version = histogramVersion.incrementAndGet();
        ColorAdjustments adjustmentSnapshot = adjustments.copy();
        CurveSet curveSnapshot = curves.copy();
        renderExecutor.execute(() -> {
            HistogramData data = buildHistogram(source, adjustmentSnapshot, curveSnapshot, version);
            if (data == null) {
                return;
            }
            runOnUiThread(() -> {
                if (version == histogramVersion.get() && histogramView != null) {
                    histogramView.setHistogram(data.luminance, data.red, data.green, data.blue);
                }
            });
        });
    }

    private HistogramData buildHistogram(Bitmap source, ColorAdjustments adjustmentSnapshot,
            CurveSet curveSnapshot, int version) {
        int width = source.getWidth();
        int height = source.getHeight();
        int stride = Math.max(1, Math.max(width, height) / 180);
        int[] row = new int[width];
        int[] luminance = new int[256];
        int[] red = new int[256];
        int[] green = new int[256];
        int[] blue = new int[256];
        int[] luminanceCurve = ColorMath.buildLookup(curveSnapshot.luminance);
        int[] redCurve = ColorMath.buildLookup(curveSnapshot.red);
        int[] greenCurve = ColorMath.buildLookup(curveSnapshot.green);
        int[] blueCurve = ColorMath.buildLookup(curveSnapshot.blue);
        boolean colorMixEnabled = ColorMath.hasColorMix(adjustmentSnapshot);
        float[] hsvScratch = new float[3];
        for (int y = 0; y < height; y += stride) {
            if (version != histogramVersion.get()) {
                return null;
            }
            source.getPixels(row, 0, width, 0, y, width, 1);
            float normalizedY = height <= 1 ? 0f : y / (float) (height - 1);
            for (int x = 0; x < width; x += stride) {
                float normalizedX = width <= 1 ? 0f : x / (float) (width - 1);
                int argb = ColorMath.adjustArgb(row[x], adjustmentSnapshot, luminanceCurve,
                        redCurve, greenCurve, blueCurve, normalizedX, normalizedY,
                        hsvScratch, colorMixEnabled);
                int r = (argb >>> 16) & 0xff;
                int g = (argb >>> 8) & 0xff;
                int b = argb & 0xff;
                red[r]++;
                green[g]++;
                blue[b]++;
                luminance[Math.round(r * 0.299f + g * 0.587f + b * 0.114f)]++;
            }
        }
        return new HistogramData(luminance, red, green, blue);
    }

    private GeometryAdjustments previewGeometry() {
        GeometryAdjustments preview = geometry.copy();
        if (activePanel == PANEL_SIZE) {
            preview.setCropRect(0f, 0f, 1f, 1f);
            preview.cropZoom = 0f;
        }
        return preview;
    }

    private float previewDisplayAspect() {
        float sourceAspect = sourceAspect();
        if (activePanel == PANEL_SIZE) {
            return sourceAspect;
        }
        float cropWidth = Math.max(0.01f, geometry.cropRight - geometry.cropLeft);
        float cropHeight = Math.max(0.01f, geometry.cropBottom - geometry.cropTop);
        return sourceAspect * cropWidth / cropHeight;
    }

    private void startRender(int version, boolean interactive) {
        Bitmap source = interactive ? fastSourceBitmap : qualitySourceBitmap;
        if (source == null) {
            source = originalBitmap;
        }
        if (source == null) {
            return;
        }
        final Bitmap renderSource = source;
        GeometryAdjustments geometrySnapshot = geometry.copy();
        ColorAdjustments adjustmentsSnapshot = adjustments.copy();
        CurveSet curveSnapshot = curves.copy();
        renderInFlight = true;
        renderExecutor.execute(() -> {
            if (version != renderVersion.get()) {
                runOnUiThread(this::finishRenderAndRunQueued);
                return;
            }
            int maxEdge = Math.max(renderSource.getWidth(), renderSource.getHeight());
            Bitmap rendered = interactive
                    ? ImageProcessor.applyFastPreview(renderSource, geometrySnapshot, adjustmentsSnapshot,
                            curveSnapshot, maxEdge, () -> version != renderVersion.get())
                    : ImageProcessor.apply(renderSource, geometrySnapshot, adjustmentsSnapshot, curveSnapshot,
                            maxEdge, () -> version != renderVersion.get());
            if (rendered == null) {
                runOnUiThread(this::finishRenderAndRunQueued);
                return;
            }
            runOnUiThread(() -> {
                renderInFlight = false;
                if (version != renderVersion.get()) {
                    rendered.recycle();
                    runQueuedRenderIfNeeded();
                    return;
                }
                if (previewBitmap != null && previewBitmap != originalBitmap && previewBitmap != fastSourceBitmap
                        && previewBitmap != qualitySourceBitmap && !previewBitmap.isRecycled()) {
                    previewBitmap.recycle();
                }
                previewBitmap = rendered;
                imageView.setImageBitmap(previewBitmap);
                runQueuedRenderIfNeeded();
            });
        });
    }

    private void finishRenderAndRunQueued() {
        renderInFlight = false;
        runQueuedRenderIfNeeded();
    }

    private void runQueuedRenderIfNeeded() {
        if (!renderQueued) {
            return;
        }
        boolean interactive = queuedInteractive;
        renderQueued = false;
        queuedInteractive = false;
        renderPreview(interactive);
    }

    private void openImage() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_OPEN_IMAGE);
    }

    private void openBatchImages() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_OPEN_BATCH);
    }

    private void collectBatchUris(Intent data) {
        batchImageUris.clear();
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                if (uri != null) {
                    batchImageUris.add(uri);
                }
            }
        } else if (data.getData() != null) {
            batchImageUris.add(data.getData());
        }
        Toast.makeText(this, "已选择 " + batchImageUris.size() + " 张图片", Toast.LENGTH_SHORT).show();
    }

    private void loadImage(Uri uri) {
        renderExecutor.execute(() -> {
            try {
                Bitmap bitmap = decodeBitmap(uri);
                Bitmap scaled = scaleDown(bitmap, MAX_PREVIEW_SIZE);
                if (scaled != bitmap) {
                    bitmap.recycle();
                }
                runOnUiThread(() -> {
                    if (originalBitmap != null && originalBitmap != previewBitmap && !originalBitmap.isRecycled()) {
                        originalBitmap.recycle();
                    }
                    originalBitmap = scaled;
                    originalImageUri = uri;
                    rebuildRenderSources();
                    if (imageView != null) {
                        imageView.setImageBitmap(scaled);
                    }
                    if (cropOverlayView != null) {
                        cropOverlayView.setImageSize(scaled.getWidth(), scaled.getHeight());
                    }
                    undoStack.clear();
                    redoStack.clear();
                    undoLabels.clear();
                    resetAllInternal();
                });
            } catch (IOException exception) {
                runOnUiThread(() -> Toast.makeText(this, "图片加载失败", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private Bitmap decodeBitmap(Uri uri) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(getContentResolver(), uri))
                    .copy(Bitmap.Config.ARGB_8888, false);
        }
        return MediaStore.Images.Media.getBitmap(getContentResolver(), uri)
                .copy(Bitmap.Config.ARGB_8888, false);
    }

    private Bitmap scaleDown(Bitmap bitmap, int maxSize) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int largest = Math.max(width, height);
        if (largest <= maxSize) {
            return bitmap;
        }
        float scale = maxSize / (float) largest;
        return Bitmap.createScaledBitmap(bitmap, Math.round(width * scale), Math.round(height * scale), true);
    }

    private void rebuildRenderSources() {
        recycleRenderSources();
        if (originalBitmap == null) {
            fastSourceBitmap = null;
            qualitySourceBitmap = null;
            return;
        }
        fastSourceBitmap = scaleDown(originalBitmap, RENDER_FAST_MAX_EDGE);
        qualitySourceBitmap = scaleDown(originalBitmap, RENDER_QUALITY_MAX_EDGE);
    }

    private void recycleRenderSources() {
        if (fastSourceBitmap != null && fastSourceBitmap != originalBitmap && !fastSourceBitmap.isRecycled()) {
            fastSourceBitmap.recycle();
        }
        if (qualitySourceBitmap != null && qualitySourceBitmap != originalBitmap
                && qualitySourceBitmap != fastSourceBitmap && !qualitySourceBitmap.isRecycled()) {
            qualitySourceBitmap.recycle();
        }
        fastSourceBitmap = null;
        qualitySourceBitmap = null;
    }

    private void chooseSaveLocation() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/jpeg");
        intent.putExtra(Intent.EXTRA_TITLE, "tonelab_edit.jpg");
        startActivityForResult(intent, REQUEST_SAVE_IMAGE);
    }

    private Uri createDefaultImageUri() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "MyLight_" + timestamp + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + EXPORT_FOLDER);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        } else {
            File directory = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), EXPORT_FOLDER);
            if (!directory.exists() && !directory.mkdirs()) {
                return null;
            }
            values.put(MediaStore.Images.Media.DATA,
                    new File(directory, "MyLight_" + timestamp + ".jpg").getAbsolutePath());
        }
        return getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
    }

    private void markImageReady(Uri uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        getContentResolver().update(uri, values, null, null);
    }

    private void saveImage(Uri uri) {
        if (originalBitmap == null) {
            return;
        }
        Toast.makeText(this, "正在保存到 Pictures/MyLight", Toast.LENGTH_SHORT).show();
        GeometryAdjustments geometrySnapshot = geometry.copy();
        ColorAdjustments adjustmentsSnapshot = adjustments.copy();
        CurveSet curveSnapshot = curves.copy();
        Uri sourceUri = originalImageUri;
        int quality = exportQuality;
        int maxEdge = exportMaxEdge();
        renderExecutor.execute(() -> {
            Bitmap source = null;
            Bitmap bitmap = null;
            Uri outputUri = null;
            try {
                source = sourceUri == null ? originalBitmap : decodeBitmap(sourceUri);
                bitmap = ImageProcessor.apply(source, geometrySnapshot, adjustmentsSnapshot,
                        curveSnapshot, maxEdge);
                outputUri = uri == null ? createDefaultImageUri() : uri;
                if (outputUri == null) {
                    runOnUiThread(() -> Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show());
                    return;
                }
                try (OutputStream outputStream = getContentResolver().openOutputStream(outputUri)) {
                    if (outputStream == null || !bitmap.compress(Bitmap.CompressFormat.JPEG,
                            quality, outputStream)) {
                        runOnUiThread(() -> Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show());
                        return;
                    }
                }
                markImageReady(outputUri);
                runOnUiThread(() -> Toast.makeText(this, "已保存到 Pictures/MyLight", Toast.LENGTH_SHORT).show());
            } catch (IOException | OutOfMemoryError exception) {
                if (outputUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    markImageReady(outputUri);
                }
                runOnUiThread(() -> Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show());
            } finally {
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
                if (source != null && source != originalBitmap && !source.isRecycled()) {
                    source.recycle();
                }
            }
        });
    }

    private void exportBatchImages() {
        if (batchImageUris.isEmpty()) {
            Toast.makeText(this, "请先在更多操作中批量选择图片", Toast.LENGTH_SHORT).show();
            openBatchImages();
            return;
        }
        GeometryAdjustments geometrySnapshot = geometry.copy();
        ColorAdjustments adjustmentsSnapshot = adjustments.copy();
        CurveSet curveSnapshot = curves.copy();
        List<Uri> sources = new ArrayList<>(batchImageUris);
        int quality = exportQuality;
        int maxEdge = exportMaxEdge();
        Toast.makeText(this, "开始批量导出 " + sources.size() + " 张", Toast.LENGTH_SHORT).show();
        renderExecutor.execute(() -> {
            int saved = 0;
            for (Uri sourceUri : sources) {
                Bitmap source = null;
                Bitmap bitmap = null;
                Uri outputUri = null;
                try {
                    source = decodeBitmap(sourceUri);
                    bitmap = ImageProcessor.apply(source, geometrySnapshot, adjustmentsSnapshot,
                            curveSnapshot, maxEdge);
                    outputUri = createDefaultImageUri();
                    if (outputUri == null || bitmap == null) {
                        continue;
                    }
                    try (OutputStream outputStream = getContentResolver().openOutputStream(outputUri)) {
                        if (outputStream != null && bitmap.compress(Bitmap.CompressFormat.JPEG,
                                quality, outputStream)) {
                            saved++;
                        }
                    }
                    markImageReady(outputUri);
                } catch (IOException | OutOfMemoryError ignored) {
                    if (outputUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        markImageReady(outputUri);
                    }
                } finally {
                    if (bitmap != null && !bitmap.isRecycled()) {
                        bitmap.recycle();
                    }
                    if (source != null && !source.isRecycled()) {
                        source.recycle();
                    }
                }
            }
            final int savedCount = saved;
            runOnUiThread(() -> Toast.makeText(this,
                    "批量导出完成：" + savedCount + "/" + sources.size(),
                    Toast.LENGTH_LONG).show());
        });
    }

    private int exportMaxEdge() {
        if (exportSizeMode == 1) {
            return 2400;
        }
        if (exportSizeMode == 2) {
            return 1600;
        }
        if (exportSizeMode == 3) {
            return 1080;
        }
        return originalImageUri == null && originalBitmap != null
                ? Math.max(originalBitmap.getWidth(), originalBitmap.getHeight())
                : 10000;
    }

    private void showSaveFilterDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText("MyLight " + (loadCustomPresets().size() + 1));
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("保存为新滤镜")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> saveCurrentFilter(input.getText().toString()))
                .show();
    }

    private void showExportFiltersDialog() {
        String json = preferences.getString(KEY_CUSTOM_PRESETS, "[]");
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("MyLight filters", json));
        }
        TextView text = new TextView(this);
        text.setText(json);
        text.setTextIsSelectable(true);
        text.setPadding(dp(18), dp(12), dp(18), dp(12));
        text.setTextColor(Color.rgb(226, 232, 240));
        new AlertDialog.Builder(this)
                .setTitle("导出滤镜")
                .setMessage("自定义滤镜 JSON 已复制到剪贴板，可发给其他人导入。")
                .setView(text)
                .setPositiveButton("完成", null)
                .show();
    }

    private void showImportFiltersDialog() {
        EditText input = new EditText(this);
        input.setMinLines(4);
        input.setGravity(Gravity.TOP | Gravity.LEFT);
        input.setHint("粘贴从 MyLight 导出的滤镜 JSON");
        new AlertDialog.Builder(this)
                .setTitle("导入滤镜")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("导入", (dialog, which) ->
                        importFilters(input.getText().toString()))
                .show();
    }

    private void importFilters(String rawJson) {
        try {
            JSONArray incoming = new JSONArray(rawJson == null ? "" : rawJson.trim());
            JSONArray current = new JSONArray(preferences.getString(KEY_CUSTOM_PRESETS, "[]"));
            for (int i = 0; i < incoming.length(); i++) {
                JSONObject item = incoming.getJSONObject(i);
                if (!item.has("adjustments") || !item.has("curves")) {
                    continue;
                }
                JSONObject copy = new JSONObject(item.toString());
                if (copy.optString("name", "").trim().isEmpty()) {
                    copy.put("name", "导入滤镜 " + (current.length() + 1));
                }
                current.put(copy);
            }
            preferences.edit().putString(KEY_CUSTOM_PRESETS, current.toString()).apply();
            renderControls();
            Toast.makeText(this, "已导入滤镜", Toast.LENGTH_SHORT).show();
        } catch (JSONException exception) {
            Toast.makeText(this, "导入失败，请检查 JSON", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveCurrentFilter(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            name = "MyLight " + (loadCustomPresets().size() + 1);
        }
        try {
            JSONArray presets = new JSONArray(preferences.getString(KEY_CUSTOM_PRESETS, "[]"));
            JSONObject preset = new JSONObject();
            preset.put("name", name);
            preset.put("adjustments", adjustmentsToJson(adjustments));
            preset.put("curves", curvesToJson(curves));
            presets.put(preset);
            preferences.edit().putString(KEY_CUSTOM_PRESETS, presets.toString()).apply();
            renderControls();
            Toast.makeText(this, "已保存滤镜", Toast.LENGTH_SHORT).show();
        } catch (JSONException exception) {
            Toast.makeText(this, "滤镜保存失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void showCustomPresetMenu(int index, String currentName) {
        new AlertDialog.Builder(this)
                .setTitle(currentName)
                .setItems(new String[] {"上移", "下移", "重命名", "删除"}, (dialog, which) -> {
                    if (which == 0) {
                        moveCustomFilter(index, -1);
                    } else if (which == 1) {
                        moveCustomFilter(index, 1);
                    } else if (which == 2) {
                        showRenameFilterDialog(index, currentName);
                    } else {
                        confirmDeleteFilter(index, currentName);
                    }
                })
                .show();
    }

    private void showRenameFilterDialog(int index, String currentName) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(currentName);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("重命名滤镜")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) ->
                        renameCustomFilter(index, input.getText().toString()))
                .show();
    }

    private void confirmDeleteFilter(int index, String name) {
        new AlertDialog.Builder(this)
                .setTitle("删除滤镜")
                .setMessage("删除「" + name + "」？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> deleteCustomFilter(index))
                .show();
    }

    private void renameCustomFilter(int index, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONArray presets = new JSONArray(preferences.getString(KEY_CUSTOM_PRESETS, "[]"));
            if (index < 0 || index >= presets.length()) {
                return;
            }
            presets.getJSONObject(index).put("name", name);
            preferences.edit().putString(KEY_CUSTOM_PRESETS, presets.toString()).apply();
            clearActiveFilter();
            renderControls();
            Toast.makeText(this, "已重命名", Toast.LENGTH_SHORT).show();
        } catch (JSONException exception) {
            Toast.makeText(this, "重命名失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteCustomFilter(int index) {
        try {
            JSONArray presets = new JSONArray(preferences.getString(KEY_CUSTOM_PRESETS, "[]"));
            if (index < 0 || index >= presets.length()) {
                return;
            }
            presets.remove(index);
            preferences.edit().putString(KEY_CUSTOM_PRESETS, presets.toString()).apply();
            clearActiveFilter();
            renderControls();
            Toast.makeText(this, "已删除滤镜", Toast.LENGTH_SHORT).show();
        } catch (JSONException exception) {
            Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void moveCustomFilter(int index, int direction) {
        try {
            JSONArray presets = new JSONArray(preferences.getString(KEY_CUSTOM_PRESETS, "[]"));
            int target = index + direction;
            if (index < 0 || index >= presets.length() || target < 0 || target >= presets.length()) {
                Toast.makeText(this, direction < 0 ? "已经在最前" : "已经在最后", Toast.LENGTH_SHORT).show();
                return;
            }
            JSONArray reordered = new JSONArray();
            for (int i = 0; i < presets.length(); i++) {
                if (i == index) {
                    reordered.put(presets.getJSONObject(target));
                } else if (i == target) {
                    reordered.put(presets.getJSONObject(index));
                } else {
                    reordered.put(presets.getJSONObject(i));
                }
            }
            preferences.edit().putString(KEY_CUSTOM_PRESETS, reordered.toString()).apply();
            clearActiveFilter();
            renderControls();
            Toast.makeText(this, "已调整滤镜顺序", Toast.LENGTH_SHORT).show();
        } catch (JSONException exception) {
            Toast.makeText(this, "排序失败", Toast.LENGTH_SHORT).show();
        }
    }

    private List<Preset> loadCustomPresets() {
        List<Preset> presets = new ArrayList<>();
        if (preferences == null) {
            return presets;
        }
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY_CUSTOM_PRESETS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                ColorAdjustments presetAdjustments = new ColorAdjustments();
                CurveSet presetCurves = new CurveSet();
                readAdjustments(object.getJSONObject("adjustments"), presetAdjustments);
                readCurves(object.getJSONArray("curves"), presetCurves);
                presets.add(new Preset(object.optString("name", "MyLight"), presetAdjustments, presetCurves));
            }
        } catch (JSONException exception) {
            preferences.edit().putString(KEY_CUSTOM_PRESETS, "[]").apply();
        }
        return presets;
    }

    private Preset loadLastEditPreset() {
        if (preferences == null) {
            return null;
        }
        String saved = preferences.getString(KEY_LAST_EDIT, "");
        if (saved.isEmpty()) {
            return null;
        }
        try {
            JSONObject object = new JSONObject(saved);
            ColorAdjustments presetAdjustments = new ColorAdjustments();
            CurveSet presetCurves = new CurveSet();
            readAdjustments(object.getJSONObject("adjustments"), presetAdjustments);
            readCurves(object.getJSONArray("curves"), presetCurves);
            return new Preset("上次修改", presetAdjustments, presetCurves);
        } catch (JSONException exception) {
            preferences.edit().remove(KEY_LAST_EDIT).apply();
            return null;
        }
    }

    private void persistCurrentEdit() {
        if (preferences == null) {
            return;
        }
        try {
            JSONObject object = new JSONObject();
            object.put("geometry", geometryToJson());
            object.put("adjustments", adjustmentsToJson(adjustments));
            object.put("curves", curvesToJson(curves));
            preferences.edit().putString(KEY_LAST_EDIT, object.toString()).apply();
        } catch (JSONException ignored) {
            // Ignore persistence failures; the edit session should keep working.
        }
    }

    private JSONObject geometryToJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("cropMode", geometry.cropMode);
        object.put("cropLeft", geometry.cropLeft);
        object.put("cropTop", geometry.cropTop);
        object.put("cropRight", geometry.cropRight);
        object.put("cropBottom", geometry.cropBottom);
        object.put("cropZoom", geometry.cropZoom);
        object.put("rotateDegrees", geometry.rotateDegrees);
        object.put("quarterTurns", geometry.quarterTurns);
        return object;
    }

    private void readGeometry(JSONObject object) {
        geometry.cropMode = object.optInt("cropMode", GeometryAdjustments.CROP_ORIGINAL);
        geometry.cropLeft = (float) object.optDouble("cropLeft", 0.0);
        geometry.cropTop = (float) object.optDouble("cropTop", 0.0);
        geometry.cropRight = (float) object.optDouble("cropRight", 1.0);
        geometry.cropBottom = (float) object.optDouble("cropBottom", 1.0);
        geometry.cropZoom = (float) object.optDouble("cropZoom", 0.0);
        geometry.rotateDegrees = (float) object.optDouble("rotateDegrees", 0.0);
        geometry.quarterTurns = object.optInt("quarterTurns", 0);
        geometry.setCropRect(geometry.cropLeft, geometry.cropTop, geometry.cropRight, geometry.cropBottom);
    }

    private JSONObject adjustmentsToJson(ColorAdjustments source) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("brightness", source.brightness);
        object.put("highlights", source.highlights);
        object.put("shadows", source.shadows);
        object.put("contrast", source.contrast);
        object.put("saturation", source.saturation);
        object.put("temperature", source.temperature);
        object.put("tint", source.tint);
        object.put("exposure", source.exposure);
        object.put("fade", source.fade);
        object.put("vignette", source.vignette);
        object.put("dehaze", source.dehaze);
        object.put("ambiance", source.ambiance);
        object.put("localEnabled", source.localEnabled);
        object.put("localX", source.localX);
        object.put("localY", source.localY);
        object.put("localRadius", source.localRadius);
        object.put("localFeather", source.localFeather);
        object.put("localExposure", source.localExposure);
        object.put("localSaturation", source.localSaturation);
        object.put("mixHue", floatArrayToJson(source.mixHue));
        object.put("mixSaturation", floatArrayToJson(source.mixSaturation));
        object.put("mixLuminance", floatArrayToJson(source.mixLuminance));
        return object;
    }

    private void readAdjustments(JSONObject object, ColorAdjustments target) throws JSONException {
        target.brightness = (float) object.optDouble("brightness", 0.0);
        target.highlights = (float) object.optDouble("highlights", 0.0);
        target.shadows = (float) object.optDouble("shadows", 0.0);
        target.contrast = (float) object.optDouble("contrast", 0.0);
        target.saturation = (float) object.optDouble("saturation", 0.0);
        target.temperature = (float) object.optDouble("temperature", 0.0);
        target.tint = (float) object.optDouble("tint", 0.0);
        target.exposure = (float) object.optDouble("exposure", 0.0);
        target.fade = (float) object.optDouble("fade", 0.0);
        target.vignette = (float) object.optDouble("vignette", 0.0);
        target.dehaze = (float) object.optDouble("dehaze", 0.0);
        target.ambiance = (float) object.optDouble("ambiance", 0.0);
        target.localEnabled = (float) object.optDouble("localEnabled", 0.0);
        target.localX = (float) object.optDouble("localX", 0.5);
        target.localY = (float) object.optDouble("localY", 0.5);
        target.localRadius = (float) object.optDouble("localRadius", 0.35);
        target.localFeather = (float) object.optDouble("localFeather", 0.35);
        target.localExposure = (float) object.optDouble("localExposure", 0.0);
        target.localSaturation = (float) object.optDouble("localSaturation", 0.0);
        readFloatArray(object.optJSONArray("mixHue"), target.mixHue);
        readFloatArray(object.optJSONArray("mixSaturation"), target.mixSaturation);
        readFloatArray(object.optJSONArray("mixLuminance"), target.mixLuminance);
    }

    private JSONArray curvesToJson(CurveSet source) throws JSONException {
        JSONArray array = new JSONArray();
        array.put(curveToJson(source.luminance));
        array.put(curveToJson(source.red));
        array.put(curveToJson(source.green));
        array.put(curveToJson(source.blue));
        return array;
    }

    private void readCurves(JSONArray array, CurveSet target) throws JSONException {
        readCurve(array.optJSONArray(CurveSet.LUMINANCE), target.luminance);
        readCurve(array.optJSONArray(CurveSet.RED), target.red);
        readCurve(array.optJSONArray(CurveSet.GREEN), target.green);
        readCurve(array.optJSONArray(CurveSet.BLUE), target.blue);
    }

    private JSONArray curveToJson(ToneCurve curve) throws JSONException {
        JSONArray points = new JSONArray();
        for (int i = 0; i < curve.pointCount(); i++) {
            JSONArray point = new JSONArray();
            point.put(curve.getX(i));
            point.put(curve.getY(i));
            points.put(point);
        }
        return points;
    }

    private void readCurve(JSONArray points, ToneCurve curve) throws JSONException {
        curve.reset();
        if (points == null || points.length() < 2) {
            return;
        }
        JSONArray first = points.getJSONArray(0);
        curve.setPoint(0, first.optInt(0, 0), first.optInt(1, 0));
        JSONArray last = points.getJSONArray(points.length() - 1);
        curve.setPoint(curve.pointCount() - 1, last.optInt(0, 255), last.optInt(1, 255));
        for (int i = 1; i < points.length() - 1; i++) {
            JSONArray point = points.getJSONArray(i);
            curve.addPoint(point.optInt(0, 0), point.optInt(1, 0));
        }
    }

    private JSONArray floatArrayToJson(float[] values) throws JSONException {
        JSONArray array = new JSONArray();
        for (float value : values) {
            array.put(value);
        }
        return array;
    }

    private void readFloatArray(JSONArray array, float[] target) {
        for (int i = 0; i < target.length; i++) {
            target[i] = array == null ? 0f : (float) array.optDouble(i, 0.0);
        }
    }

    private Bitmap createSampleBitmap(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, width, height,
                new int[] {Color.rgb(39, 52, 72), Color.rgb(159, 107, 87), Color.rgb(232, 197, 127)},
                null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        paint.setColor(Color.argb(185, 22, 27, 33));
        canvas.drawRect(0, height * 0.74f, width, height, paint);
        paint.setColor(Color.argb(175, 20, 26, 32));
        canvas.drawRect(0, height * 0.58f, width, height * 0.74f, paint);
        paint.setColor(Color.argb(210, 215, 225, 235));
        canvas.drawRect(width * 0.08f, height * 0.82f, width * 0.26f, height * 0.87f, paint);
        paint.setColor(Color.argb(220, 113, 167, 109));
        canvas.drawRect(width * 0.52f, height * 0.82f, width * 0.7f, height * 0.87f, paint);
        return bitmap;
    }

    private Button createButton(String text) {
        return createButton(text, false);
    }

    private Button createButton(String text, boolean selected) {
        return createButton(text, selected, semanticAccent(text));
    }

    private Button createButton(String text, boolean selected, int accent) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12f);
        button.setTextColor(selected ? Color.WHITE : blend(Color.rgb(226, 232, 240), accent, 0.18f));
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        GradientDrawable background = new GradientDrawable();
        if (selected) {
            background.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
            background.setColors(new int[] {blend(Color.rgb(18, 22, 29), accent, 0.62f),
                    blend(Color.rgb(18, 22, 29), accent, 0.9f)});
        } else {
            background.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
            background.setColors(new int[] {blend(Color.rgb(34, 40, 51), accent, 0.12f),
                    blend(Color.rgb(20, 25, 33), accent, 0.06f)});
        }
        background.setStroke(dp(1), selected ? blend(Color.WHITE, accent, 0.5f)
                : blend(Color.rgb(68, 78, 95), accent, 0.38f));
        background.setCornerRadius(dp(10));
        button.setBackground(background);
        return button;
    }

    private int semanticAccent(String text) {
        if (text.contains("保存") || text.contains("完成")) {
            return Color.rgb(73, 188, 129);
        }
        if (text.contains("重置") || text.contains("归零")) {
            return Color.rgb(232, 162, 80);
        }
        if (text.contains("滤镜") || text.contains("上次") || text.contains("Clean")
                || text.contains("Film") || text.contains("Mono")) {
            return Color.rgb(142, 128, 255);
        }
        if (text.contains("左转") || text.contains("右转") || text.contains("裁剪")
                || text.contains("尺寸")) {
            return Color.rgb(84, 197, 210);
        }
        if (text.contains("色彩") || text.contains("红") || text.contains("橙") || text.contains("黄")
                || text.contains("绿") || text.contains("青") || text.contains("蓝") || text.contains("紫")) {
            return Color.rgb(101, 196, 122);
        }
        if (text.contains("曲线")) {
            return Color.rgb(95, 179, 243);
        }
        if (text.contains("效果")) {
            return Color.rgb(212, 126, 214);
        }
        return Color.rgb(95, 179, 243);
    }

    private int hslColor(int channel) {
        switch (channel) {
            case ColorAdjustments.MIX_RED:
                return Color.rgb(237, 82, 82);
            case ColorAdjustments.MIX_ORANGE:
                return Color.rgb(238, 145, 62);
            case ColorAdjustments.MIX_YELLOW:
                return Color.rgb(231, 196, 67);
            case ColorAdjustments.MIX_GREEN:
                return Color.rgb(91, 190, 117);
            case ColorAdjustments.MIX_AQUA:
                return Color.rgb(72, 190, 196);
            case ColorAdjustments.MIX_BLUE:
                return Color.rgb(88, 138, 239);
            case ColorAdjustments.MIX_PURPLE:
                return Color.rgb(152, 112, 242);
            case ColorAdjustments.MIX_MAGENTA:
                return Color.rgb(225, 92, 189);
            default:
                return Color.rgb(95, 179, 243);
        }
    }

    private int blend(int base, int accent, float amount) {
        float keep = 1f - amount;
        return Color.rgb(
                Math.round(Color.red(base) * keep + Color.red(accent) * amount),
                Math.round(Color.green(base) * keep + Color.green(accent) * amount),
                Math.round(Color.blue(base) * keep + Color.blue(accent) * amount));
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int curveColor(int channel) {
        if (channel == CurveSet.RED) {
            return Color.rgb(238, 91, 91);
        }
        if (channel == CurveSet.GREEN) {
            return Color.rgb(101, 196, 122);
        }
        if (channel == CurveSet.BLUE) {
            return Color.rgb(93, 145, 245);
        }
        return Color.rgb(95, 179, 243);
    }

    private int sliderAccent(String label) {
        if (label.contains("高光") || label.contains("曝光") || label.contains("明亮")) {
            return Color.rgb(112, 188, 255);
        }
        if (label.contains("阴影") || label.contains("对比") || label.contains("去模糊")) {
            return Color.rgb(139, 152, 170);
        }
        if (label.contains("饱和")) {
            return Color.rgb(236, 110, 157);
        }
        if (label.contains("色温")) {
            return Color.rgb(242, 163, 74);
        }
        if (label.contains("色调")) {
            return Color.rgb(203, 108, 231);
        }
        if (label.contains("色相")) {
            return hslColor(activeMixChannel);
        }
        if (label.contains("滤镜")) {
            return Color.rgb(167, 139, 250);
        }
        if (label.contains("裁剪") || label.contains("旋转")) {
            return Color.rgb(84, 197, 210);
        }
        if (label.contains("晕影") || label.contains("氛围") || label.contains("褪色")) {
            return Color.rgb(216, 128, 218);
        }
        return Color.rgb(95, 179, 243);
    }

    private void setGradientBackground(View view, int startColor, int endColor,
            GradientDrawable.Orientation orientation) {
        GradientDrawable background = new GradientDrawable(orientation, new int[] {startColor, endColor});
        view.setBackground(background);
    }

    private void updateCropOverlay() {
        if (cropOverlayView == null) {
            return;
        }
        cropOverlayView.setVisibility(activePanel == PANEL_SIZE ? View.VISIBLE : View.GONE);
        cropOverlayView.invalidate();
        if (activePanel == PANEL_SIZE) {
            previewZoom = 1f;
            previewPanX = 0f;
            previewPanY = 0f;
            applyPreviewTransform();
        }
        if (histogramView != null) {
            histogramView.setVisibility(activePanel == PANEL_SIZE ? View.GONE : View.VISIBLE);
        }
    }

    private boolean panelHasChanges(int panel) {
        if (panel == PANEL_FILTER) {
            return activeFilterPreset != null && floatChanged(filterStrength);
        }
        if (panel == PANEL_SIZE) {
            return geometry.cropMode != GeometryAdjustments.CROP_ORIGINAL
                    || floatChanged(geometry.cropLeft)
                    || floatChanged(geometry.cropTop)
                    || floatChanged(geometry.cropRight - 1f)
                    || floatChanged(geometry.cropBottom - 1f)
                    || floatChanged(geometry.cropZoom)
                    || floatChanged(geometry.rotateDegrees)
                    || geometry.quarterTurns != 0;
        }
        if (panel == PANEL_CURVE) {
            return curveChanged(curves.luminance) || curveChanged(curves.red)
                    || curveChanged(curves.green) || curveChanged(curves.blue);
        }
        if (panel == PANEL_EFFECTS) {
            return floatChanged(adjustments.vignette) || floatChanged(adjustments.dehaze)
                    || floatChanged(adjustments.ambiance) || floatChanged(adjustments.fade)
                    || adjustments.localEnabled > 0.5f
                    || floatChanged(adjustments.localExposure)
                    || floatChanged(adjustments.localSaturation);
        }
        if (panel == PANEL_LIGHT) {
            return floatChanged(adjustments.exposure)
                    || floatChanged(adjustments.brightness)
                    || floatChanged(adjustments.highlights)
                    || floatChanged(adjustments.shadows)
                    || floatChanged(adjustments.contrast);
        }
        if (panel == PANEL_COLOR) {
            return floatChanged(adjustments.saturation)
                    || floatChanged(adjustments.temperature)
                    || floatChanged(adjustments.tint);
        }
        if (panel == PANEL_HSL) {
            return colorMixChanged();
        }
        return false;
    }

    private boolean isAdjustPanel(int panel) {
        return panel == PANEL_LIGHT || panel == PANEL_COLOR || panel == PANEL_HSL || panel == PANEL_EFFECTS;
    }

    private boolean adjustPanelsHaveChanges() {
        return panelHasChanges(PANEL_LIGHT) || panelHasChanges(PANEL_COLOR)
                || panelHasChanges(PANEL_HSL) || panelHasChanges(PANEL_EFFECTS);
    }

    private boolean colorMixChanged() {
        for (int i = 0; i < ColorAdjustments.MIX_COUNT; i++) {
            if (floatChanged(adjustments.mixHue[i]) || floatChanged(adjustments.mixSaturation[i])
                    || floatChanged(adjustments.mixLuminance[i])) {
                return true;
            }
        }
        return false;
    }

    private boolean curveChanged(ToneCurve curve) {
        return curve.pointCount() != 2 || curve.getX(0) != 0 || curve.getY(0) != 0
                || curve.getX(1) != 255 || curve.getY(1) != 255;
    }

    private boolean floatChanged(float value) {
        return Math.abs(value) > 0.0001f;
    }

    private void pushUndoSnapshot() {
        pushUndoSnapshot("参数调整", true);
    }

    private void pushUndoSnapshot(String label) {
        pushUndoSnapshot(label, true);
    }

    private void pushUndoSnapshot(boolean clearRedo) {
        pushUndoSnapshot("参数调整", clearRedo);
    }

    private void pushUndoSnapshot(String label, boolean clearRedo) {
        undoStack.push(new EditSnapshot(geometry.copy(), adjustments.copy(), curves.copy()));
        undoLabels.add(0, label == null || label.trim().isEmpty() ? "参数调整" : label.trim());
        if (clearRedo) {
            redoStack.clear();
        }
        while (undoStack.size() > MAX_UNDO_STEPS) {
            undoStack.removeLast();
            if (!undoLabels.isEmpty()) {
                undoLabels.remove(undoLabels.size() - 1);
            }
        }
        updateHistoryButtons();
    }

    private void undoLastEdit() {
        if (undoStack.isEmpty()) {
            Toast.makeText(this, "没有可撤销的修改", Toast.LENGTH_SHORT).show();
            return;
        }
        EditSnapshot snapshot = undoStack.pop();
        if (!undoLabels.isEmpty()) {
            undoLabels.remove(0);
        }
        redoStack.push(new EditSnapshot(geometry.copy(), adjustments.copy(), curves.copy()));
        while (redoStack.size() > MAX_UNDO_STEPS) {
            redoStack.removeLast();
        }
        compareActive = false;
        clearActiveFilter();
        copyGeometry(snapshot.geometry, geometry);
        copyAdjustments(snapshot.adjustments, adjustments);
        curves = snapshot.curves.copy();
        renderControls();
        renderPreview(false);
        updateHistoryButtons();
        Toast.makeText(this, "已撤销", Toast.LENGTH_SHORT).show();
    }

    private void redoLastEdit() {
        if (redoStack.isEmpty()) {
            Toast.makeText(this, "没有可重做的修改", Toast.LENGTH_SHORT).show();
            return;
        }
        EditSnapshot snapshot = redoStack.pop();
        pushUndoSnapshot("重做前状态", false);
        compareActive = false;
        clearActiveFilter();
        copyGeometry(snapshot.geometry, geometry);
        copyAdjustments(snapshot.adjustments, adjustments);
        curves = snapshot.curves.copy();
        renderControls();
        renderPreview(false);
        updateHistoryButtons();
        Toast.makeText(this, "已重做", Toast.LENGTH_SHORT).show();
    }

    private void updateHistoryButtons() {
        if (undoToolbarButton != null) {
            boolean enabled = !undoStack.isEmpty();
            undoToolbarButton.setEnabled(enabled);
            undoToolbarButton.setAlpha(enabled ? 1f : 0.38f);
        }
        if (redoToolbarButton != null) {
            boolean enabled = !redoStack.isEmpty();
            redoToolbarButton.setEnabled(enabled);
            redoToolbarButton.setAlpha(enabled ? 1f : 0.38f);
        }
    }

    private static void copyGeometry(GeometryAdjustments source, GeometryAdjustments target) {
        target.cropMode = source.cropMode;
        target.cropLeft = source.cropLeft;
        target.cropTop = source.cropTop;
        target.cropRight = source.cropRight;
        target.cropBottom = source.cropBottom;
        target.cropZoom = source.cropZoom;
        target.rotateDegrees = source.rotateDegrees;
        target.quarterTurns = source.quarterTurns;
    }

    private float sourceAspect() {
        if (originalBitmap == null || originalBitmap.getHeight() == 0) {
            return 1f;
        }
        return originalBitmap.getWidth() / (float) originalBitmap.getHeight();
    }

    private static void copyAdjustments(ColorAdjustments source, ColorAdjustments target) {
        target.brightness = source.brightness;
        target.contrast = source.contrast;
        target.saturation = source.saturation;
        target.temperature = source.temperature;
        target.tint = source.tint;
        target.exposure = source.exposure;
        target.highlights = source.highlights;
        target.shadows = source.shadows;
        target.fade = source.fade;
        target.vignette = source.vignette;
        target.dehaze = source.dehaze;
        target.ambiance = source.ambiance;
        target.localEnabled = source.localEnabled;
        target.localX = source.localX;
        target.localY = source.localY;
        target.localRadius = source.localRadius;
        target.localFeather = source.localFeather;
        target.localExposure = source.localExposure;
        target.localSaturation = source.localSaturation;
        System.arraycopy(source.mixHue, 0, target.mixHue, 0, source.mixHue.length);
        System.arraycopy(source.mixSaturation, 0, target.mixSaturation, 0, source.mixSaturation.length);
        System.arraycopy(source.mixLuminance, 0, target.mixLuminance, 0, source.mixLuminance.length);
    }

    private static void mixAdjustments(ColorAdjustments start, ColorAdjustments end, float amount,
            ColorAdjustments target) {
        target.brightness = lerp(start.brightness, end.brightness, amount);
        target.contrast = lerp(start.contrast, end.contrast, amount);
        target.saturation = lerp(start.saturation, end.saturation, amount);
        target.temperature = lerp(start.temperature, end.temperature, amount);
        target.tint = lerp(start.tint, end.tint, amount);
        target.exposure = lerp(start.exposure, end.exposure, amount);
        target.highlights = lerp(start.highlights, end.highlights, amount);
        target.shadows = lerp(start.shadows, end.shadows, amount);
        target.fade = lerp(start.fade, end.fade, amount);
        target.vignette = lerp(start.vignette, end.vignette, amount);
        target.dehaze = lerp(start.dehaze, end.dehaze, amount);
        target.ambiance = lerp(start.ambiance, end.ambiance, amount);
        target.localEnabled = end.localEnabled;
        target.localX = lerp(start.localX, end.localX, amount);
        target.localY = lerp(start.localY, end.localY, amount);
        target.localRadius = lerp(start.localRadius, end.localRadius, amount);
        target.localFeather = lerp(start.localFeather, end.localFeather, amount);
        target.localExposure = lerp(start.localExposure, end.localExposure, amount);
        target.localSaturation = lerp(start.localSaturation, end.localSaturation, amount);
        for (int i = 0; i < ColorAdjustments.MIX_COUNT; i++) {
            target.mixHue[i] = lerp(start.mixHue[i], end.mixHue[i], amount);
            target.mixSaturation[i] = lerp(start.mixSaturation[i], end.mixSaturation[i], amount);
            target.mixLuminance[i] = lerp(start.mixLuminance[i], end.mixLuminance[i], amount);
        }
    }

    private static CurveSet mixCurves(CurveSet start, CurveSet end, float amount) {
        CurveSet mixed = new CurveSet();
        mixCurve(start.luminance, end.luminance, amount, mixed.luminance);
        mixCurve(start.red, end.red, amount, mixed.red);
        mixCurve(start.green, end.green, amount, mixed.green);
        mixCurve(start.blue, end.blue, amount, mixed.blue);
        return mixed;
    }

    private static void mixCurve(ToneCurve start, ToneCurve end, float amount, ToneCurve target) {
        int[] values = new int[5];
        int[] samples = {0, 64, 128, 192, 255};
        for (int i = 0; i < samples.length; i++) {
            values[i] = Math.round(lerp(start.map(samples[i]), end.map(samples[i]), amount));
        }
        target.setFixedPoints(values);
    }

    private static float lerp(float start, float end, float amount) {
        return start + (end - start) * amount;
    }

    private void clearActiveFilter() {
        activeFilterPreset = null;
        filterBaseAdjustments = null;
        filterBaseCurves = null;
        filterStrength = 1f;
    }

    private static final class EditSnapshot {
        final GeometryAdjustments geometry;
        final ColorAdjustments adjustments;
        final CurveSet curves;

        EditSnapshot(GeometryAdjustments geometry, ColorAdjustments adjustments, CurveSet curves) {
            this.geometry = geometry;
            this.adjustments = adjustments;
            this.curves = curves;
        }
    }

    private static final class HistogramData {
        final int[] luminance;
        final int[] red;
        final int[] green;
        final int[] blue;

        HistogramData(int[] luminance, int[] red, int[] green, int[] blue) {
            this.luminance = luminance;
            this.red = red;
            this.green = green;
            this.blue = blue;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface SliderConsumer {
        void accept(float value);
    }

    private static final class SliderBinding {
        final SeekBar seekBar;
        final float min;
        final float max;
        float value;

        SliderBinding(SeekBar seekBar, float value, float min, float max) {
            this.seekBar = seekBar;
            this.value = value;
            this.min = min;
            this.max = max;
        }
    }
}

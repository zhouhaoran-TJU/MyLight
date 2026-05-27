package com.example.lightroomclone;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Base64;
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
import android.widget.ImageView;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final int FILTER_THUMB_WIDTH = 128;
    private static final int FILTER_THUMB_HEIGHT = 92;
    private static final int FILTER_THUMB_SOURCE_EDGE = 220;
    private static final long QUALITY_RENDER_DELAY_MS = 180L;
    private static final String PREFS_NAME = "tonelab_memory";
    private static final String KEY_CUSTOM_PRESETS = "custom_presets";
    private static final String KEY_LAST_EDIT = "last_edit";
    private static final String KEY_DRAFTS = "drafts";
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
    private final Map<String, Bitmap> filterThumbnailCache = new HashMap<>();

    private GpuImageView imageView;
    private ImageView previewImageView;
    private HorizontalScrollView presetScrollView;
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
    private boolean curveEditMode;
    private int cropGridMode = CropOverlayView.GRID_THIRDS;
    private CurveView curveView;
    private CropOverlayView cropOverlayView;
    private LocalAdjustOverlayView localOverlayView;
    private HistogramView histogramView;
    private TextView compareLabel;
    private TextView messageBar;
    private TextView statusPill;
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
    private boolean compareSliderMode;
    private boolean localDraggingCenter;
    private boolean localDraggingRadius;
    private float compareSplit = 0.5f;
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
    private int presetStripScrollX;
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
        clearFilterThumbnailCache();
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
        setGradientBackground(root, Color.rgb(7, 10, 15), Color.rgb(14, 20, 31),
                GradientDrawable.Orientation.TOP_BOTTOM);

        root.addView(createToolbar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));

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
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(318)));
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
        setGradientBackground(imageFrame, Color.rgb(5, 7, 12), Color.rgb(17, 24, 36),
                GradientDrawable.Orientation.TL_BR);
        imageView = new GpuImageView(this);
        imageView.setVisibility(View.GONE);
        imageView.setOnTouchListener((view, event) -> handlePreviewTouch(event));
        imageFrame.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        previewImageView = new ImageView(this);
        previewImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewImageView.setAdjustViewBounds(false);
        previewImageView.setBackgroundColor(Color.TRANSPARENT);
        previewImageView.setOnTouchListener((view, event) -> handlePreviewTouch(event));
        imageFrame.addView(previewImageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setDisplayedBitmap(previewBitmap);
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
        localOverlayView = new LocalAdjustOverlayView(this, adjustments);
        localOverlayView.setVisibility(View.GONE);
        imageFrame.addView(localOverlayView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        histogramView = new HistogramView(this);
        FrameLayout.LayoutParams histogramParams = new FrameLayout.LayoutParams(dp(136), dp(76),
                Gravity.TOP | Gravity.RIGHT);
        histogramParams.setMargins(0, dp(12), dp(12), 0);
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
        compareBackground.setStroke(dp(1), Color.rgb(89, 199, 255));
        compareBackground.setCornerRadius(dp(14));
        compareLabel.setBackground(compareBackground);
        compareLabel.setVisibility(View.GONE);
        FrameLayout.LayoutParams compareParams = new FrameLayout.LayoutParams(dp(60), dp(28),
                Gravity.LEFT | Gravity.TOP);
        compareParams.setMargins(dp(12), dp(12), 0, 0);
        imageFrame.addView(compareLabel, compareParams);
        statusPill = new TextView(this);
        statusPill.setTextColor(Color.rgb(226, 246, 255));
        statusPill.setTextSize(11f);
        statusPill.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusPill.setGravity(Gravity.CENTER);
        statusPill.setPadding(dp(9), 0, dp(9), 0);
        GradientDrawable statusBackground = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {Color.argb(210, 18, 31, 46), Color.argb(210, 20, 52, 68)});
        statusBackground.setStroke(dp(1), Color.rgb(89, 199, 255));
        statusBackground.setCornerRadius(dp(13));
        statusPill.setBackground(statusBackground);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(26), Gravity.LEFT | Gravity.BOTTOM);
        statusParams.setMargins(dp(12), 0, 0, dp(12));
        imageFrame.addView(statusPill, statusParams);
        imageFrame.setOnTouchListener((view, event) -> handlePreviewTouch(event));
        updateStatusPill();
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
        if (compareSliderMode) {
            compareSplit = clamp(event.getX() / Math.max(1f, previewSurfaceWidth()), 0.02f, 0.98f);
            renderPreview(false);
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_DOWN && whiteBalancePickMode) {
            applyWhiteBalanceFromTap(event.getX(), event.getY());
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_DOWN && localPickMode) {
            applyLocalCenterFromTap(event.getX(), event.getY());
            return true;
        }
        if (activePanel == PANEL_EFFECTS && localPointCount() > 0 && handleLocalDrag(event)) {
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

    private boolean handleLocalDrag(MotionEvent event) {
        int index = activeLocalIndex();
        if (index < 0 || previewImageView == null) {
            return false;
        }
        float width = Math.max(1f, previewImageView.getWidth());
        float height = Math.max(1f, previewImageView.getHeight());
        float x = event.getX();
        float y = event.getY();
        float cx = adjustments.localXs[index] * width;
        float cy = adjustments.localYs[index] * height;
        float radiusPx = adjustments.localRadii[index] * Math.min(width, height);
        float distance = (float) Math.hypot(x - cx, y - cy);
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            localDraggingCenter = distance <= dp(26);
            localDraggingRadius = Math.abs(distance - radiusPx) <= dp(28);
            if (localDraggingCenter || localDraggingRadius) {
                pushUndoSnapshot("局部拖动");
                return true;
            }
            return false;
        }
        if (event.getAction() == MotionEvent.ACTION_MOVE && (localDraggingCenter || localDraggingRadius)) {
            if (localDraggingCenter) {
                adjustments.localXs[index] = clamp(x / width, 0f, 1f);
                adjustments.localYs[index] = clamp(y / height, 0f, 1f);
            } else {
                adjustments.localRadii[index] = clamp(distance / Math.min(width, height), 0.12f, 0.8f);
            }
            syncActiveLocalFromArrays();
            if (localOverlayView != null) {
                localOverlayView.invalidate();
            }
            renderInteractivePreview();
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            if (localDraggingCenter || localDraggingRadius) {
                localDraggingCenter = false;
                localDraggingRadius = false;
                renderControls();
                renderPreview(false);
                return true;
            }
        }
        return false;
    }

    private void setDisplayedBitmap(Bitmap bitmap) {
        if (previewImageView != null) {
            previewImageView.setImageBitmap(bitmap);
        }
        if (imageView != null && imageView.getVisibility() == View.VISIBLE) {
            imageView.setImageBitmap(bitmap);
        }
    }

    private int previewSurfaceWidth() {
        if (previewImageView != null && previewImageView.getWidth() > 0) {
            return previewImageView.getWidth();
        }
        return imageView == null ? 1 : Math.max(1, imageView.getWidth());
    }

    private int previewSurfaceHeight() {
        if (previewImageView != null && previewImageView.getHeight() > 0) {
            return previewImageView.getHeight();
        }
        return imageView == null ? 1 : Math.max(1, imageView.getHeight());
    }

    private void applyPreviewTransform() {
        if (previewImageView == null) {
            return;
        }
        if (previewZoom <= 1.01f) {
            previewZoom = 1f;
            previewPanX = 0f;
            previewPanY = 0f;
        }
        float maxPanX = Math.max(0f, previewSurfaceWidth() * (previewZoom - 1f) * 0.5f);
        float maxPanY = Math.max(0f, previewSurfaceHeight() * (previewZoom - 1f) * 0.5f);
        previewPanX = clamp(previewPanX, -maxPanX, maxPanX);
        previewPanY = clamp(previewPanY, -maxPanY, maxPanY);
        if (imageView != null && imageView.getVisibility() == View.VISIBLE) {
            imageView.setScaleX(previewZoom);
            imageView.setScaleY(previewZoom);
            imageView.setTranslationX(previewPanX);
            imageView.setTranslationY(previewPanY);
        }
        previewImageView.setScaleX(previewZoom);
        previewImageView.setScaleY(previewZoom);
        previewImageView.setTranslationX(previewPanX);
        previewImageView.setTranslationY(previewPanY);
    }

    private void startWhiteBalancePicker() {
        whiteBalancePickMode = true;
        localPickMode = false;
        renderControls();
        updateStatusPill();
        Toast.makeText(this, "点一下图片中的灰白区域", Toast.LENGTH_SHORT).show();
    }

    private void startLocalPicker() {
        ensureLocalPoint();
        localPickMode = true;
        whiteBalancePickMode = false;
        renderControls();
        updateStatusPill();
        Toast.makeText(this, "点一下图片设置局部调整中心", Toast.LENGTH_SHORT).show();
    }

    private void addLocalPoint() {
        if (adjustments.localCount >= ColorAdjustments.MAX_LOCAL_POINTS) {
            Toast.makeText(this, "最多支持 3 个局部点", Toast.LENGTH_SHORT).show();
            return;
        }
        pushUndoSnapshot("新增局部点");
        int index = adjustments.localCount++;
        adjustments.activeLocalIndex = index;
        adjustments.localXs[index] = 0.5f;
        adjustments.localYs[index] = 0.5f;
        adjustments.localRadii[index] = 0.35f;
        adjustments.localFeathers[index] = 0.35f;
        adjustments.localExposures[index] = 0.25f;
        adjustments.localSaturations[index] = 0f;
        syncActiveLocalFromArrays();
        renderControls();
        renderPreview(false);
    }

    private void ensureLocalPoint() {
        if (adjustments.localCount <= 0) {
            int index = adjustments.localCount++;
            adjustments.activeLocalIndex = index;
            adjustments.localXs[index] = adjustments.localX;
            adjustments.localYs[index] = adjustments.localY;
            adjustments.localRadii[index] = adjustments.localRadius;
            adjustments.localFeathers[index] = adjustments.localFeather;
            adjustments.localExposures[index] = adjustments.localExposure;
            adjustments.localSaturations[index] = adjustments.localSaturation;
        }
        syncActiveLocalFromArrays();
    }

    private void switchLocalPoint(int direction) {
        if (adjustments.localCount <= 0) {
            return;
        }
        adjustments.activeLocalIndex = (adjustments.activeLocalIndex + direction + adjustments.localCount)
                % adjustments.localCount;
        syncActiveLocalFromArrays();
        renderControls();
        renderPreview(false);
    }

    private void deleteActiveLocalPoint() {
        if (adjustments.localCount <= 0) {
            return;
        }
        pushUndoSnapshot("删除局部点");
        int index = activeLocalIndex();
        for (int i = index; i < adjustments.localCount - 1; i++) {
            adjustments.localXs[i] = adjustments.localXs[i + 1];
            adjustments.localYs[i] = adjustments.localYs[i + 1];
            adjustments.localRadii[i] = adjustments.localRadii[i + 1];
            adjustments.localFeathers[i] = adjustments.localFeathers[i + 1];
            adjustments.localExposures[i] = adjustments.localExposures[i + 1];
            adjustments.localSaturations[i] = adjustments.localSaturations[i + 1];
        }
        adjustments.localCount--;
        adjustments.activeLocalIndex = Math.max(0, Math.min(adjustments.activeLocalIndex,
                adjustments.localCount - 1));
        syncActiveLocalFromArrays();
        renderControls();
        renderPreview(false);
    }

    private int localPointCount() {
        return adjustments.localCount > 0 ? adjustments.localCount
                : (adjustments.localEnabled > 0.5f ? 1 : 0);
    }

    private int activeLocalIndex() {
        if (adjustments.localCount <= 0) {
            return -1;
        }
        return Math.max(0, Math.min(adjustments.activeLocalIndex, adjustments.localCount - 1));
    }

    private void syncActiveLocalFromArrays() {
        int index = activeLocalIndex();
        if (index < 0) {
            adjustments.localEnabled = 0f;
            return;
        }
        adjustments.localEnabled = 1f;
        adjustments.localX = adjustments.localXs[index];
        adjustments.localY = adjustments.localYs[index];
        adjustments.localRadius = adjustments.localRadii[index];
        adjustments.localFeather = adjustments.localFeathers[index];
        adjustments.localExposure = adjustments.localExposures[index];
        adjustments.localSaturation = adjustments.localSaturations[index];
    }

    private void applyWhiteBalanceFromTap(float x, float y) {
        if (originalBitmap == null || previewImageView == null) {
            return;
        }
        float nx = clamp(x / Math.max(1f, previewImageView.getWidth()), 0f, 1f);
        float ny = clamp(y / Math.max(1f, previewImageView.getHeight()), 0f, 1f);
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
        if (previewImageView == null) {
            return;
        }
        pushUndoSnapshot("局部中心");
        ensureLocalPoint();
        int index = activeLocalIndex();
        adjustments.localXs[index] = clamp(x / Math.max(1f, previewImageView.getWidth()), 0f, 1f);
        adjustments.localYs[index] = clamp(y / Math.max(1f, previewImageView.getHeight()), 0f, 1f);
        syncActiveLocalFromArrays();
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
        setGradientBackground(panel, Color.rgb(18, 24, 38), Color.rgb(7, 10, 16),
                GradientDrawable.Orientation.TOP_BOTTOM);
        panel.setPadding(landscape ? dp(8) : 0, 0, landscape ? dp(8) : 0, 0);
        panelTabs = new LinearLayout(this);
        panelTabs.setOrientation(LinearLayout.HORIZONTAL);
        panelTabs.setPadding(dp(12), dp(8), dp(12), dp(8));
        setGradientBackground(panelTabs, Color.rgb(18, 26, 40), Color.rgb(10, 14, 22),
                GradientDrawable.Orientation.LEFT_RIGHT);
        panel.addView(panelTabs, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));
        rebuildPanelTabs();

        controlScroll = new ScrollView(this);
        controlScroll.setFillViewport(false);
        controlScroll.setBackgroundColor(Color.rgb(10, 14, 22));
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(16), dp(8), dp(16), landscape ? dp(24) : dp(18));
        controls.setBackgroundColor(Color.rgb(10, 14, 22));
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
        toolbar.setPadding(dp(12), dp(5), dp(12), dp(5));
        setGradientBackground(toolbar, Color.rgb(19, 31, 48), Color.rgb(7, 10, 16),
                GradientDrawable.Orientation.LEFT_RIGHT);

        TextView title = new TextView(this);
        title.setText("MyLight");
        title.setTextColor(Color.WHITE);
        title.setTextSize(19f);
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(50), dp(38));
        params.leftMargin = dp(5);
        row.addView(button, params);
    }

    private void addToolbarIconButton(LinearLayout row, Button button) {
        button.setTextSize(17f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(38), dp(38));
        params.leftMargin = dp(5);
        row.addView(button, params);
    }

    private void showMoreActions() {
        new AlertDialog.Builder(this)
                .setTitle("更多操作")
                .setItems(new String[] {
                        "历史记录",
                        "导出设置",
                        compareSliderMode ? "关闭滑杆对比" : "开启滑杆对比",
                        clippingWarningEnabled ? "关闭裁切警告" : "开启裁切警告",
                        "批量选择图片",
                        "批量导出当前效果",
                        "导入滤镜",
                        "导出滤镜",
                        "保存草稿",
                        "加载草稿",
                        "复制滤镜分享码",
                        "粘贴分享码导入",
                        "重置全部",
                        "重置预览缩放",
                        "长按图片可对比原图"
                }, (dialog, which) -> {
                    if (which == 0) {
                        showHistoryDialog();
                    } else if (which == 1) {
                        showExportSettingsDialog();
                    } else if (which == 2) {
                        compareSliderMode = !compareSliderMode;
                        renderPreview(false);
                    } else if (which == 3) {
                        clippingWarningEnabled = !clippingWarningEnabled;
                        renderPreview(false);
                    } else if (which == 4) {
                        openBatchImages();
                    } else if (which == 5) {
                        exportBatchImages();
                    } else if (which == 6) {
                        showImportFiltersDialog();
                    } else if (which == 7) {
                        showExportFiltersDialog();
                    } else if (which == 8) {
                        saveDraft();
                    } else if (which == 9) {
                        showDraftsDialog();
                    } else if (which == 10) {
                        copyPresetShareCode();
                    } else if (which == 11) {
                        showImportShareCodeDialog();
                    } else if (which == 12) {
                        resetAll();
                    } else if (which == 13) {
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
        if (selected) {
            button.setElevation(dp(6));
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1f);
        params.leftMargin = dp(3);
        params.rightMargin = dp(3);
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
        addModeButton(cropRow, "自动水平", false, this::autoStraighten);
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

    private void autoStraighten() {
        Bitmap source = fastSourceBitmap != null ? fastSourceBitmap : originalBitmap;
        if (source == null || source.isRecycled()) {
            return;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        float bestScore = 0f;
        float bestAngle = 0f;
        int centerY = height / 2;
        for (int angle = -8; angle <= 8; angle++) {
            double radians = Math.toRadians(angle);
            double slope = Math.tan(radians);
            float score = 0f;
            int last = -1;
            for (int x = 8; x < width - 8; x += 8) {
                int y = Math.max(1, Math.min(height - 2, Math.round(centerY + (float) ((x - width / 2f) * slope))));
                int color = source.getPixel(x, y);
                int luminance = Math.round(Color.red(color) * 0.299f + Color.green(color) * 0.587f
                        + Color.blue(color) * 0.114f);
                if (last >= 0) {
                    score += Math.abs(luminance - last);
                }
                last = luminance;
            }
            if (score > bestScore) {
                bestScore = score;
                bestAngle = -angle;
            }
        }
        pushUndoSnapshot("自动水平");
        geometry.rotateDegrees = clamp(geometry.rotateDegrees + bestAngle, -45f, 45f);
        renderControls();
        renderPreview(false);
        Toast.makeText(this, "已自动水平 " + String.format(Locale.US, "%.1f", bestAngle) + "°",
                Toast.LENGTH_SHORT).show();
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
        controls.addView(createSectionLabel("常用"));
        addSlider("曝光", adjustments.exposure, -1f, 1f, value -> adjustments.exposure = value);
        addSlider("对比度", adjustments.contrast, -1f, 1f, value -> adjustments.contrast = value);
        addSlider("色温", adjustments.temperature, -1f, 1f, value -> adjustments.temperature = value);
        addSlider("饱和度", adjustments.saturation, -1f, 1f, value -> adjustments.saturation = value);
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
        curveView.setEditingEnabled(curveEditMode);
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
        addModeButton(curveActions, curveEditMode ? "完成" : "调整", curveEditMode, () -> {
            curveEditMode = !curveEditMode;
            if (curveView != null) {
                curveView.setEditingEnabled(curveEditMode);
            }
            if (!curveEditMode) {
                renderPreview(false);
            }
            renderControls();
        });
        addModeButton(curveActions, "删除锚点", false, () -> {
            if (!curveEditMode) {
                Toast.makeText(this, "请先点击「调整」", Toast.LENGTH_SHORT).show();
                return;
            }
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
        addModeButton(localRow, "新增局部", false, this::addLocalPoint);
        addModeButton(localRow, "点选中心", localPickMode, this::startLocalPicker);
        controls.addView(localRow);
        LinearLayout localManageRow = createButtonRow();
        addModeButton(localManageRow, "上一个", false, () -> switchLocalPoint(-1));
        addModeButton(localManageRow, localPointCount() + "/3", localPointCount() > 0, () -> {});
        addModeButton(localManageRow, "下一个", false, () -> switchLocalPoint(1));
        addModeButton(localManageRow, "删除", false, this::deleteActiveLocalPoint);
        controls.addView(localManageRow);
        if (localPointCount() > 0) {
            int active = activeLocalIndex();
            addSlider("局部半径", adjustments.localRadii[active], 0.12f, 0.8f, value -> {
                adjustments.localRadii[activeLocalIndex()] = value;
                syncActiveLocalFromArrays();
            });
            addSlider("局部羽化", adjustments.localFeathers[active], 0f, 1f, value -> {
                adjustments.localFeathers[activeLocalIndex()] = value;
                syncActiveLocalFromArrays();
            });
            addSlider("局部曝光", adjustments.localExposures[active], -1f, 1f, value -> {
                adjustments.localExposures[activeLocalIndex()] = value;
                syncActiveLocalFromArrays();
            });
            addSlider("局部饱和", adjustments.localSaturations[active], -1f, 1f, value -> {
                adjustments.localSaturations[activeLocalIndex()] = value;
                syncActiveLocalFromArrays();
            });
        }
        controls.addView(createSectionLabel("质感"));
        addSlider("锐化", adjustments.sharpness, -1f, 1f, value -> adjustments.sharpness = value);
        addSlider("降噪", adjustments.noiseReduction, 0f, 1f, value -> adjustments.noiseReduction = value);
        addSlider("颗粒", adjustments.grain, 0f, 1f, value -> adjustments.grain = value);
    }

    private View createPresetStrip() {
        HorizontalScrollView scrollView = new HorizontalScrollView(this);
        presetScrollView = scrollView;
        scrollView.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(8));
        Preset lastEdit = loadLastEditPreset();
        if (lastEdit != null) {
            Button button = createPresetButton("上次修改\n记忆", true, lastEdit, "last");
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(118), dp(76));
            params.rightMargin = dp(8);
            row.addView(button, params);
        }
        Button saveFilterButton = createButton("存为滤镜\n当前", true);
        saveFilterButton.setBackground(createFilterButtonBackground(currentPreviewThumbnail(), true));
        saveFilterButton.setTextColor(Color.WHITE);
        saveFilterButton.setShadowLayer(dp(2), 0f, dp(1), Color.argb(190, 0, 0, 0));
        saveFilterButton.setElevation(dp(5));
        saveFilterButton.setOnClickListener(v -> showSaveFilterDialog());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(dp(118), dp(76));
        saveParams.rightMargin = dp(8);
        row.addView(saveFilterButton, saveParams);
        for (Preset preset : Preset.defaults()) {
            Button button = createPresetButton(preset.name + "\n默认", false, preset, "default");
            button.setOnLongClickListener(v -> {
                Toast.makeText(this, "默认滤镜不可管理，可保存为自定义滤镜", Toast.LENGTH_SHORT).show();
                return true;
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(108), dp(76));
            params.rightMargin = dp(8);
            row.addView(button, params);
        }
        List<Preset> customPresets = loadCustomPresets();
        for (int i = 0; i < customPresets.size(); i++) {
            final int index = i;
            Preset preset = customPresets.get(i);
            Button button = createPresetButton(preset.name + "\n自定义", false, preset, "custom:" + index);
            button.setOnLongClickListener(v -> {
                showCustomPresetMenu(index, preset.name);
                return true;
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(112), dp(76));
            params.rightMargin = dp(8);
            row.addView(button, params);
        }
        scrollView.addView(row);
        scrollView.post(() -> scrollView.scrollTo(presetStripScrollX, 0));
        return scrollView;
    }

    private Button createPresetButton(String label, boolean selected, Preset preset, String group) {
        Button button = createButton(label, selected || isActiveFilter(preset));
        boolean active = selected || isActiveFilter(preset);
        button.setBackground(createFilterButtonBackground(filterThumbnailFor(preset, group), active));
        button.setTextColor(Color.WHITE);
        button.setShadowLayer(dp(2), 0f, dp(1), Color.argb(210, 0, 0, 0));
        button.setOnClickListener(v -> {
            rememberPresetStripPosition();
            applyPreset(preset);
        });
        button.setGravity(Gravity.CENTER);
        button.setTextSize(11f);
        button.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
        button.setElevation(active ? dp(7) : dp(2));
        return button;
    }

    private boolean isActiveFilter(Preset preset) {
        return activeFilterPreset != null && activeFilterPreset.name.equals(preset.name);
    }

    private void rememberPresetStripPosition() {
        if (presetScrollView != null) {
            presetStripScrollX = presetScrollView.getScrollX();
        }
    }

    private Bitmap currentPreviewThumbnail() {
        Bitmap source = previewBitmap != null && !previewBitmap.isRecycled() ? previewBitmap : originalBitmap;
        if (source == null || source.isRecycled()) {
            return null;
        }
        return centerCropThumbnail(source, FILTER_THUMB_WIDTH, FILTER_THUMB_HEIGHT);
    }

    private Bitmap filterThumbnailFor(Preset preset, String group) {
        Bitmap source = fastSourceBitmap != null && !fastSourceBitmap.isRecycled() ? fastSourceBitmap : originalBitmap;
        if (source == null || source.isRecycled()) {
            return null;
        }
        String key = filterThumbnailKey(preset, group, source);
        Bitmap cached = filterThumbnailCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }
        Bitmap rendered = null;
        try {
            Bitmap thumbSource = scaleDown(source, FILTER_THUMB_SOURCE_EDGE);
            rendered = ImageProcessor.applyFastPreview(thumbSource, new GeometryAdjustments(),
                    preset.adjustments, preset.curves, FILTER_THUMB_SOURCE_EDGE, () -> false);
            if (thumbSource != source && !thumbSource.isRecycled()) {
                thumbSource.recycle();
            }
        } catch (RuntimeException exception) {
            rendered = null;
        }
        Bitmap thumbnail = rendered == null ? centerCropThumbnail(source, FILTER_THUMB_WIDTH, FILTER_THUMB_HEIGHT)
                : centerCropThumbnail(rendered, FILTER_THUMB_WIDTH, FILTER_THUMB_HEIGHT);
        if (rendered != null && !rendered.isRecycled()) {
            rendered.recycle();
        }
        filterThumbnailCache.put(key, thumbnail);
        return thumbnail;
    }

    private String filterThumbnailKey(Preset preset, String group, Bitmap source) {
        StringBuilder key = new StringBuilder(group).append(':').append(preset.name)
                .append(':').append(source.getWidth()).append('x').append(source.getHeight());
        appendAdjustmentKey(key, preset.adjustments);
        appendCurveKey(key, preset.curves.luminance);
        appendCurveKey(key, preset.curves.red);
        appendCurveKey(key, preset.curves.green);
        appendCurveKey(key, preset.curves.blue);
        return key.toString();
    }

    private void appendAdjustmentKey(StringBuilder key, ColorAdjustments source) {
        key.append(':').append(Float.floatToIntBits(source.brightness))
                .append(':').append(Float.floatToIntBits(source.contrast))
                .append(':').append(Float.floatToIntBits(source.saturation))
                .append(':').append(Float.floatToIntBits(source.temperature))
                .append(':').append(Float.floatToIntBits(source.tint))
                .append(':').append(Float.floatToIntBits(source.exposure))
                .append(':').append(Float.floatToIntBits(source.highlights))
                .append(':').append(Float.floatToIntBits(source.shadows))
                .append(':').append(Float.floatToIntBits(source.fade))
                .append(':').append(Float.floatToIntBits(source.vignette))
                .append(':').append(Float.floatToIntBits(source.dehaze))
                .append(':').append(Float.floatToIntBits(source.ambiance))
                .append(':').append(Float.floatToIntBits(source.sharpness))
                .append(':').append(Float.floatToIntBits(source.noiseReduction))
                .append(':').append(Float.floatToIntBits(source.grain));
        for (int i = 0; i < ColorAdjustments.MIX_COUNT; i++) {
            key.append(':').append(Float.floatToIntBits(source.mixHue[i]))
                    .append(':').append(Float.floatToIntBits(source.mixSaturation[i]))
                    .append(':').append(Float.floatToIntBits(source.mixLuminance[i]));
        }
    }

    private void appendCurveKey(StringBuilder key, ToneCurve curve) {
        for (int i = 0; i < curve.pointCount(); i++) {
            key.append(':').append(curve.getX(i)).append(',').append(curve.getY(i));
        }
    }

    private Bitmap centerCropThumbnail(Bitmap source, int width, int height) {
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.rgb(9, 12, 18));
        float scale = Math.max(width / (float) source.getWidth(), height / (float) source.getHeight());
        float drawWidth = source.getWidth() * scale;
        float drawHeight = source.getHeight() * scale;
        Rect dst = new Rect(Math.round((width - drawWidth) * 0.5f),
                Math.round((height - drawHeight) * 0.5f),
                Math.round((width + drawWidth) * 0.5f),
                Math.round((height + drawHeight) * 0.5f));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(source, null, dst, paint);
        return output;
    }

    private LayerDrawable createFilterButtonBackground(Bitmap thumbnail, boolean selected) {
        GradientDrawable fallback = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {Color.rgb(27, 37, 56), Color.rgb(10, 14, 22)});
        fallback.setCornerRadius(dp(8));
        fallback.setStroke(dp(1), Color.rgb(76, 96, 125));
        if (thumbnail == null || thumbnail.isRecycled()) {
            return new LayerDrawable(new Drawable[] {fallback});
        }
        Drawable image = new CoverBitmapDrawable(thumbnail);
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {Color.argb(36, 255, 255, 255), Color.argb(184, 2, 5, 10)});
        shade.setCornerRadius(dp(8));
        GradientDrawable stroke = new GradientDrawable();
        stroke.setColor(Color.TRANSPARENT);
        stroke.setCornerRadius(dp(8));
        stroke.setStroke(dp(selected ? 2 : 1), selected ? Color.rgb(255, 255, 255)
                : Color.argb(150, 110, 134, 166));
        return new LayerDrawable(new Drawable[] {image, shade, stroke});
    }

    private void clearFilterThumbnailCache() {
        for (Bitmap bitmap : filterThumbnailCache.values()) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        filterThumbnailCache.clear();
    }

    private TextView createSectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText("▌ " + text);
        label.setTextColor(Color.rgb(221, 241, 255));
        label.setTextSize(13f);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPadding(dp(2), dp(12), 0, dp(7));
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
        grid.setPadding(0, 0, 0, dp(8));
        grid.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return grid;
    }

    private void addModeButton(LinearLayout row, String label, boolean selected, Runnable action) {
        Button button = createButton(label, selected);
        button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(40), 1f);
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
        params.height = dp(40);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(3), dp(3), dp(3), dp(4));
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
        sliderRow.setPadding(dp(10), dp(5), dp(8), dp(5));
        GradientDrawable rowBackground = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {Color.rgb(13, 18, 29), Color.rgb(9, 13, 21)});
        rowBackground.setStroke(dp(1), Color.rgb(25, 35, 52));
        rowBackground.setCornerRadius(dp(8));
        sliderRow.setBackground(rowBackground);

        TextView nameLabel = new TextView(this);
        nameLabel.setText(label);
        nameLabel.setTextColor(floatChanged(initialValue) ? Color.WHITE : Color.rgb(202, 211, 224));
        nameLabel.setTextSize(12f);
        nameLabel.setTypeface(Typeface.DEFAULT, floatChanged(initialValue) ? Typeface.BOLD : Typeface.NORMAL);
        sliderRow.addView(nameLabel, new LinearLayout.LayoutParams(dp(66), dp(38)));

        int sliderAccent = sliderAccent(label);
        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(200);
        seekBar.setProgressTintList(ColorStateList.valueOf(sliderAccent));
        seekBar.setThumbTintList(ColorStateList.valueOf(Color.rgb(241, 250, 255)));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(blend(Color.rgb(28, 37, 55),
                sliderAccent, 0.2f)));
        seekBar.setSplitTrack(false);
        sliderRow.addView(seekBar, new LinearLayout.LayoutParams(0, dp(38), 1f));

        EditText valueInput = new EditText(this);
        valueInput.setTextColor(Color.rgb(232, 237, 244));
        valueInput.setTextSize(11f);
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
                new int[] {blend(Color.rgb(12, 17, 27), sliderAccent, 0.18f),
                        Color.rgb(7, 10, 16)});
        inputBackground.setStroke(dp(1), blend(Color.rgb(83, 105, 135), sliderAccent, 0.48f));
        inputBackground.setCornerRadius(dp(8));
        valueInput.setBackground(inputBackground);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(dp(54), dp(32));
        valueParams.leftMargin = dp(6);
        sliderRow.addView(valueInput, valueParams);

        Button resetButton = createButton("0", false, Color.rgb(232, 162, 80));
        resetButton.setTextSize(11f);
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(dp(30), dp(30));
        resetParams.leftMargin = dp(5);
        sliderRow.addView(resetButton, resetParams);

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
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        rowParams.bottomMargin = dp(6);
        controls.addView(sliderRow, rowParams);
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
        if (previewImageView == null && imageView == null) {
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
        if (previewBitmap != null && !previewBitmap.isRecycled()) {
            setDisplayedBitmap(previewBitmap);
        } else if (originalBitmap != null && !originalBitmap.isRecycled()) {
            setDisplayedBitmap(originalBitmap);
        }
        if (imageView != null && imageView.getVisibility() == View.VISIBLE) {
            imageView.updateState(previewGeometry(), adjustments, curves, previewDisplayAspect(),
                    clippingWarningEnabled, compareSliderMode, compareSplit);
        }
        int version = renderVersion.incrementAndGet();
        renderHandler.removeCallbacks(qualityRenderRunnable);
        if (renderInFlight) {
            renderQueued = true;
            queuedInteractive = interactive;
        } else {
            startRender(version, interactive);
        }
        if (interactive) {
            renderHandler.postDelayed(qualityRenderRunnable, QUALITY_RENDER_DELAY_MS);
        }
        updateLocalOverlay();
        updateStatusPill();
        updateHistogramAsync();
    }

    private void updateStatusPill() {
        if (statusPill == null) {
            return;
        }
        StringBuilder status = new StringBuilder("实时预览");
        if (adjustPanelsHaveChanges() || panelHasChanges(PANEL_FILTER)
                || panelHasChanges(PANEL_CURVE) || panelHasChanges(PANEL_SIZE)) {
            status.append("  ·  已修改");
        }
        if (clippingWarningEnabled) {
            status.append("  ·  裁切警告");
        }
        if (compareSliderMode) {
            status.append("  ·  滑杆对比");
        }
        if (whiteBalancePickMode || localPickMode) {
            status.append("  ·  点选模式");
        }
        statusPill.setText(status.toString());
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
        if (previewImageView == null && imageView == null) {
            return;
        }
        renderVersion.incrementAndGet();
        renderHandler.removeCallbacks(qualityRenderRunnable);
        renderQueued = false;
        queuedInteractive = false;
        if (compareLabel != null) {
            compareLabel.setVisibility(View.VISIBLE);
        }
        if (originalBitmap != null && !originalBitmap.isRecycled()) {
            setDisplayedBitmap(originalBitmap);
        }
        if (imageView != null && imageView.getVisibility() == View.VISIBLE) {
            imageView.updateState(previewGeometry(), new ColorAdjustments(), new CurveSet(),
                    previewDisplayAspect(), false, false, compareSplit);
        }
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
        Bitmap source = activePanel == PANEL_SIZE ? originalBitmap
                : (interactive ? fastSourceBitmap : qualitySourceBitmap);
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
                runOnUiThread(this::finishStaleRender);
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
                setDisplayedBitmap(previewBitmap);
                runQueuedRenderIfNeeded();
            });
        });
    }

    private void finishRenderAndRunQueued() {
        renderInFlight = false;
        runQueuedRenderIfNeeded();
    }

    private void finishStaleRender() {
        renderInFlight = false;
        if (!renderQueued && renderVersion.get() > 0) {
            renderQueued = true;
            queuedInteractive = false;
        }
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
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "image/*", "image/x-adobe-dng", "image/dng", "application/octet-stream"
        });
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
                    if (previewBitmap != null && previewBitmap != originalBitmap && !previewBitmap.isRecycled()) {
                        previewBitmap.recycle();
                    }
                    originalBitmap = scaled;
                    previewBitmap = scaled;
                    originalImageUri = uri;
                    clearFilterThumbnailCache();
                    presetStripScrollX = 0;
                    rebuildRenderSources();
                    renderVersion.incrementAndGet();
                    renderHandler.removeCallbacks(qualityRenderRunnable);
                    renderInFlight = false;
                    renderQueued = false;
                    queuedInteractive = false;
                    compareActive = false;
                    setDisplayedBitmap(previewBitmap);
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
        Bitmap decoded;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decoded = ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(getContentResolver(), uri))
                    .copy(Bitmap.Config.ARGB_8888, false);
        } else {
            decoded = MediaStore.Images.Media.getBitmap(getContentResolver(), uri)
                    .copy(Bitmap.Config.ARGB_8888, false);
        }
        return flattenTransparency(applyExifOrientation(uri, decoded));
    }

    private Bitmap flattenTransparency(Bitmap bitmap) {
        if (!bitmap.hasAlpha()) {
            return bitmap;
        }
        Bitmap flattened = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(flattened);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(bitmap, 0f, 0f, null);
        if (flattened != bitmap) {
            bitmap.recycle();
        }
        return flattened;
    }

    private Bitmap applyExifOrientation(Uri uri, Bitmap bitmap) {
        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                return bitmap;
            }
            ExifInterface exif = new ExifInterface(inputStream);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            Matrix matrix = new Matrix();
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) {
                matrix.postRotate(90f);
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) {
                matrix.postRotate(180f);
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) {
                matrix.postRotate(270f);
            } else if (orientation == ExifInterface.ORIENTATION_FLIP_HORIZONTAL) {
                matrix.postScale(-1f, 1f);
            } else if (orientation == ExifInterface.ORIENTATION_FLIP_VERTICAL) {
                matrix.postScale(1f, -1f);
            } else {
                return bitmap;
            }
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) {
                bitmap.recycle();
            }
            return rotated;
        } catch (IOException | RuntimeException exception) {
            return bitmap;
        }
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
                Uri savedUri = outputUri;
                runOnUiThread(() -> showExportCompleteDialog(savedUri));
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

    private void showExportCompleteDialog(Uri uri) {
        Toast.makeText(this, "已保存到 Pictures/MyLight", Toast.LENGTH_SHORT).show();
        new AlertDialog.Builder(this)
                .setTitle("导出完成")
                .setItems(new String[] {"查看", "分享", "继续编辑"}, (dialog, which) -> {
                    if (which == 0) {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(uri, "image/jpeg");
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(intent, "查看图片"));
                    } else if (which == 1) {
                        Intent intent = new Intent(Intent.ACTION_SEND);
                        intent.setType("image/jpeg");
                        intent.putExtra(Intent.EXTRA_STREAM, uri);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(intent, "分享图片"));
                    }
                })
                .show();
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
        AtomicBoolean cancelled = new AtomicBoolean(false);
        ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setTitle("批量导出");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(sources.size());
        progressDialog.setCancelable(false);
        progressDialog.setButton(DialogInterface.BUTTON_NEGATIVE, "取消",
                (dialog, which) -> cancelled.set(true));
        progressDialog.show();
        renderExecutor.execute(() -> {
            int saved = 0;
            for (int i = 0; i < sources.size(); i++) {
                if (cancelled.get()) {
                    break;
                }
                Uri sourceUri = sources.get(i);
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
                final int progress = i + 1;
                final int savedSoFar = saved;
                runOnUiThread(() -> {
                    progressDialog.setProgress(progress);
                    progressDialog.setMessage("已保存 " + savedSoFar + " / " + sources.size());
                });
            }
            final int savedCount = saved;
            runOnUiThread(() -> {
                progressDialog.dismiss();
                Toast.makeText(this,
                        (cancelled.get() ? "批量导出已取消：" : "批量导出完成：")
                                + savedCount + "/" + sources.size(),
                        Toast.LENGTH_LONG).show();
            });
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
            clearFilterThumbnailCache();
            renderControls();
            Toast.makeText(this, "已导入滤镜", Toast.LENGTH_SHORT).show();
        } catch (JSONException exception) {
            Toast.makeText(this, "导入失败，请检查 JSON", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyPresetShareCode() {
        try {
            JSONObject object = new JSONObject();
            object.put("name", "MyLight Share");
            object.put("adjustments", adjustmentsToJson(adjustments));
            object.put("curves", curvesToJson(curves));
            String code = "MYLIGHT:" + Base64.encodeToString(object.toString().getBytes("UTF-8"),
                    Base64.NO_WRAP);
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("MyLight share code", code));
            }
            Toast.makeText(this, "滤镜分享码已复制", Toast.LENGTH_SHORT).show();
        } catch (Exception exception) {
            Toast.makeText(this, "分享码生成失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void showImportShareCodeDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(false);
        input.setMinLines(3);
        input.setHint("粘贴 MYLIGHT: 开头的分享码");
        new AlertDialog.Builder(this)
                .setTitle("导入分享码")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("导入", (dialog, which) -> importShareCode(input.getText().toString()))
                .show();
    }

    private void importShareCode(String code) {
        try {
            String clean = code == null ? "" : code.trim();
            if (clean.startsWith("MYLIGHT:")) {
                clean = clean.substring("MYLIGHT:".length());
            }
            String json = new String(Base64.decode(clean, Base64.DEFAULT), "UTF-8");
            JSONObject object = new JSONObject(json);
            pushUndoSnapshot("导入分享码");
            readAdjustments(object.getJSONObject("adjustments"), adjustments);
            readCurves(object.getJSONArray("curves"), curves);
            clearActiveFilter();
            renderControls();
            renderPreview(false);
            Toast.makeText(this, "已应用分享码", Toast.LENGTH_SHORT).show();
        } catch (Exception exception) {
            Toast.makeText(this, "分享码无效", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveDraft() {
        try {
            JSONArray drafts = new JSONArray(preferences.getString(KEY_DRAFTS, "[]"));
            JSONObject draft = new JSONObject();
            draft.put("name", new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date()));
            draft.put("geometry", geometryToJson());
            draft.put("adjustments", adjustmentsToJson(adjustments));
            draft.put("curves", curvesToJson(curves));
            drafts.put(draft);
            while (drafts.length() > 10) {
                drafts.remove(0);
            }
            preferences.edit().putString(KEY_DRAFTS, drafts.toString()).apply();
            Toast.makeText(this, "草稿已保存", Toast.LENGTH_SHORT).show();
        } catch (JSONException exception) {
            Toast.makeText(this, "草稿保存失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void showDraftsDialog() {
        try {
            JSONArray drafts = new JSONArray(preferences.getString(KEY_DRAFTS, "[]"));
            if (drafts.length() == 0) {
                Toast.makeText(this, "暂无草稿", Toast.LENGTH_SHORT).show();
                return;
            }
            String[] items = new String[drafts.length()];
            for (int i = 0; i < drafts.length(); i++) {
                items[i] = drafts.getJSONObject(i).optString("name", "草稿 " + (i + 1));
            }
            new AlertDialog.Builder(this)
                    .setTitle("项目草稿")
                    .setItems(items, (dialog, which) -> loadDraft(which))
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (JSONException exception) {
            Toast.makeText(this, "草稿读取失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadDraft(int index) {
        try {
            JSONArray drafts = new JSONArray(preferences.getString(KEY_DRAFTS, "[]"));
            JSONObject draft = drafts.getJSONObject(index);
            pushUndoSnapshot("加载草稿");
            readGeometry(draft.getJSONObject("geometry"));
            readAdjustments(draft.getJSONObject("adjustments"), adjustments);
            readCurves(draft.getJSONArray("curves"), curves);
            clearActiveFilter();
            renderControls();
            renderPreview(false);
        } catch (JSONException exception) {
            Toast.makeText(this, "草稿加载失败", Toast.LENGTH_SHORT).show();
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
            clearFilterThumbnailCache();
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
            clearFilterThumbnailCache();
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
            clearFilterThumbnailCache();
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
            clearFilterThumbnailCache();
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
        object.put("sharpness", source.sharpness);
        object.put("noiseReduction", source.noiseReduction);
        object.put("grain", source.grain);
        object.put("localEnabled", source.localEnabled);
        object.put("localX", source.localX);
        object.put("localY", source.localY);
        object.put("localRadius", source.localRadius);
        object.put("localFeather", source.localFeather);
        object.put("localExposure", source.localExposure);
        object.put("localSaturation", source.localSaturation);
        object.put("localCount", source.localCount);
        object.put("activeLocalIndex", source.activeLocalIndex);
        object.put("localXs", floatArrayToJson(source.localXs));
        object.put("localYs", floatArrayToJson(source.localYs));
        object.put("localRadii", floatArrayToJson(source.localRadii));
        object.put("localFeathers", floatArrayToJson(source.localFeathers));
        object.put("localExposures", floatArrayToJson(source.localExposures));
        object.put("localSaturations", floatArrayToJson(source.localSaturations));
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
        target.sharpness = (float) object.optDouble("sharpness", 0.0);
        target.noiseReduction = (float) object.optDouble("noiseReduction", 0.0);
        target.grain = (float) object.optDouble("grain", 0.0);
        target.localEnabled = (float) object.optDouble("localEnabled", 0.0);
        target.localX = (float) object.optDouble("localX", 0.5);
        target.localY = (float) object.optDouble("localY", 0.5);
        target.localRadius = (float) object.optDouble("localRadius", 0.35);
        target.localFeather = (float) object.optDouble("localFeather", 0.35);
        target.localExposure = (float) object.optDouble("localExposure", 0.0);
        target.localSaturation = (float) object.optDouble("localSaturation", 0.0);
        target.localCount = Math.max(0, Math.min(ColorAdjustments.MAX_LOCAL_POINTS,
                object.optInt("localCount", target.localEnabled > 0.5f ? 1 : 0)));
        target.activeLocalIndex = Math.max(0, Math.min(target.localCount - 1,
                object.optInt("activeLocalIndex", 0)));
        readFloatArray(object.optJSONArray("localXs"), target.localXs);
        readFloatArray(object.optJSONArray("localYs"), target.localYs);
        readFloatArray(object.optJSONArray("localRadii"), target.localRadii);
        readFloatArray(object.optJSONArray("localFeathers"), target.localFeathers);
        readFloatArray(object.optJSONArray("localExposures"), target.localExposures);
        readFloatArray(object.optJSONArray("localSaturations"), target.localSaturations);
        if (target.localCount == 1 && object.optJSONArray("localXs") == null) {
            target.localXs[0] = target.localX;
            target.localYs[0] = target.localY;
            target.localRadii[0] = target.localRadius;
            target.localFeathers[0] = target.localFeather;
            target.localExposures[0] = target.localExposure;
            target.localSaturations[0] = target.localSaturation;
        }
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
        button.setTextColor(selected ? Color.WHITE : blend(Color.rgb(218, 230, 243), accent, 0.24f));
        button.setAllCaps(false);
        button.setIncludeFontPadding(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        GradientDrawable background = new GradientDrawable();
        if (selected) {
            background.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
            background.setColors(new int[] {blend(Color.rgb(12, 18, 28), accent, 0.55f),
                    blend(Color.rgb(18, 29, 45), accent, 0.92f)});
        } else {
            background.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
            background.setColors(new int[] {blend(Color.rgb(26, 34, 51), accent, 0.16f),
                    blend(Color.rgb(12, 17, 27), accent, 0.08f)});
        }
        background.setStroke(dp(1), selected ? blend(Color.WHITE, accent, 0.42f)
                : blend(Color.rgb(54, 70, 93), accent, 0.42f));
        background.setCornerRadius(dp(8));
        button.setBackground(background);
        return button;
    }

    private int semanticAccent(String text) {
        if (text.contains("保存") || text.contains("完成")) {
            return Color.rgb(77, 224, 163);
        }
        if (text.contains("重置") || text.contains("归零")) {
            return Color.rgb(255, 180, 92);
        }
        if (text.contains("滤镜") || text.contains("上次") || text.contains("Clean")
                || text.contains("Film") || text.contains("Mono")) {
            return Color.rgb(164, 128, 255);
        }
        if (text.contains("左转") || text.contains("右转") || text.contains("裁剪")
                || text.contains("尺寸")) {
            return Color.rgb(72, 223, 226);
        }
        if (text.contains("色彩") || text.contains("红") || text.contains("橙") || text.contains("黄")
                || text.contains("绿") || text.contains("青") || text.contains("蓝") || text.contains("紫")) {
            return Color.rgb(237, 92, 186);
        }
        if (text.contains("曲线")) {
            return Color.rgb(89, 199, 255);
        }
        if (text.contains("效果")) {
            return Color.rgb(90, 230, 190);
        }
        return Color.rgb(89, 199, 255);
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
            return Color.rgb(255, 196, 86);
        }
        if (label.contains("阴影") || label.contains("对比") || label.contains("去模糊")) {
            return Color.rgb(89, 199, 255);
        }
        if (label.contains("饱和")) {
            return Color.rgb(255, 83, 178);
        }
        if (label.contains("色温")) {
            return Color.rgb(255, 176, 83);
        }
        if (label.contains("色调")) {
            return Color.rgb(201, 108, 255);
        }
        if (label.contains("色相")) {
            return hslColor(activeMixChannel);
        }
        if (label.contains("滤镜")) {
            return Color.rgb(164, 128, 255);
        }
        if (label.contains("局部")) {
            return Color.rgb(90, 230, 190);
        }
        if (label.contains("裁剪") || label.contains("旋转")) {
            return Color.rgb(72, 223, 226);
        }
        if (label.contains("晕影") || label.contains("氛围") || label.contains("褪色")) {
            return Color.rgb(90, 230, 190);
        }
        if (label.contains("局部")) {
            return Color.rgb(90, 230, 190);
        }
        if (label.contains("锐化") || label.contains("降噪") || label.contains("颗粒")) {
            return Color.rgb(184, 117, 255);
        }
        return Color.rgb(89, 199, 255);
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
        updateLocalOverlay();
        updateStatusPill();
    }

    private void updateLocalOverlay() {
        if (localOverlayView == null) {
            return;
        }
        boolean visible = activePanel == PANEL_EFFECTS && localPointCount() > 0;
        localOverlayView.setVisibility(visible ? View.VISIBLE : View.GONE);
        localOverlayView.invalidate();
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
                    || adjustments.localEnabled > 0.5f || adjustments.localCount > 0
                    || floatChanged(adjustments.localExposure)
                    || floatChanged(adjustments.localSaturation)
                    || floatChanged(adjustments.sharpness)
                    || floatChanged(adjustments.noiseReduction)
                    || floatChanged(adjustments.grain);
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
        target.sharpness = source.sharpness;
        target.noiseReduction = source.noiseReduction;
        target.grain = source.grain;
        target.localEnabled = source.localEnabled;
        target.localX = source.localX;
        target.localY = source.localY;
        target.localRadius = source.localRadius;
        target.localFeather = source.localFeather;
        target.localExposure = source.localExposure;
        target.localSaturation = source.localSaturation;
        target.localCount = source.localCount;
        target.activeLocalIndex = source.activeLocalIndex;
        System.arraycopy(source.localXs, 0, target.localXs, 0, source.localXs.length);
        System.arraycopy(source.localYs, 0, target.localYs, 0, source.localYs.length);
        System.arraycopy(source.localRadii, 0, target.localRadii, 0, source.localRadii.length);
        System.arraycopy(source.localFeathers, 0, target.localFeathers, 0, source.localFeathers.length);
        System.arraycopy(source.localExposures, 0, target.localExposures, 0, source.localExposures.length);
        System.arraycopy(source.localSaturations, 0, target.localSaturations, 0, source.localSaturations.length);
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
        target.sharpness = lerp(start.sharpness, end.sharpness, amount);
        target.noiseReduction = lerp(start.noiseReduction, end.noiseReduction, amount);
        target.grain = lerp(start.grain, end.grain, amount);
        target.localEnabled = end.localEnabled;
        target.localX = lerp(start.localX, end.localX, amount);
        target.localY = lerp(start.localY, end.localY, amount);
        target.localRadius = lerp(start.localRadius, end.localRadius, amount);
        target.localFeather = lerp(start.localFeather, end.localFeather, amount);
        target.localExposure = lerp(start.localExposure, end.localExposure, amount);
        target.localSaturation = lerp(start.localSaturation, end.localSaturation, amount);
        target.localCount = end.localCount;
        target.activeLocalIndex = end.activeLocalIndex;
        for (int i = 0; i < ColorAdjustments.MAX_LOCAL_POINTS; i++) {
            target.localXs[i] = lerp(start.localXs[i], end.localXs[i], amount);
            target.localYs[i] = lerp(start.localYs[i], end.localYs[i], amount);
            target.localRadii[i] = lerp(start.localRadii[i], end.localRadii[i], amount);
            target.localFeathers[i] = lerp(start.localFeathers[i], end.localFeathers[i], amount);
            target.localExposures[i] = lerp(start.localExposures[i], end.localExposures[i], amount);
            target.localSaturations[i] = lerp(start.localSaturations[i], end.localSaturations[i], amount);
        }
        for (int i = 0; i < ColorAdjustments.MIX_COUNT; i++) {
            target.mixHue[i] = lerp(start.mixHue[i], end.mixHue[i], amount);
            target.mixSaturation[i] = lerp(start.mixSaturation[i], end.mixSaturation[i], amount);
            target.mixLuminance[i] = lerp(start.mixLuminance[i], end.mixLuminance[i], amount);
        }
        syncLegacyLocal(target);
    }

    private static void syncLegacyLocal(ColorAdjustments target) {
        if (target.localCount <= 0) {
            target.localEnabled = 0f;
            return;
        }
        int index = Math.max(0, Math.min(target.activeLocalIndex, target.localCount - 1));
        target.localEnabled = 1f;
        target.localX = target.localXs[index];
        target.localY = target.localYs[index];
        target.localRadius = target.localRadii[index];
        target.localFeather = target.localFeathers[index];
        target.localExposure = target.localExposures[index];
        target.localSaturation = target.localSaturations[index];
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

    private static final class CoverBitmapDrawable extends Drawable {
        private final Bitmap bitmap;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Rect dst = new Rect();

        CoverBitmapDrawable(Bitmap bitmap) {
            this.bitmap = bitmap;
        }

        @Override
        public void draw(Canvas canvas) {
            if (bitmap == null || bitmap.isRecycled()) {
                return;
            }
            Rect bounds = getBounds();
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                return;
            }
            float scale = Math.max(bounds.width() / (float) bitmap.getWidth(),
                    bounds.height() / (float) bitmap.getHeight());
            int drawWidth = Math.round(bitmap.getWidth() * scale);
            int drawHeight = Math.round(bitmap.getHeight() * scale);
            dst.set(bounds.left + (bounds.width() - drawWidth) / 2,
                    bounds.top + (bounds.height() - drawHeight) / 2,
                    bounds.left + (bounds.width() + drawWidth) / 2,
                    bounds.top + (bounds.height() + drawHeight) / 2);
            canvas.drawBitmap(bitmap, null, dst, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.OPAQUE;
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

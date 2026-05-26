package com.example.lightroomclone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
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
import android.view.Gravity;
import android.view.View;
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
import com.example.lightroomclone.core.CurveSet;
import com.example.lightroomclone.core.GeometryAdjustments;
import com.example.lightroomclone.core.ToneCurve;

import java.io.IOException;
import java.io.OutputStream;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
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
    private static final int MAX_PREVIEW_SIZE = 1400;
    private static final int RENDER_FAST_MAX_EDGE = 540;
    private static final int RENDER_QUALITY_MAX_EDGE = 960;
    private static final long QUALITY_RENDER_DELAY_MS = 180L;
    private static final String PREFS_NAME = "tonelab_memory";
    private static final String KEY_CUSTOM_PRESETS = "custom_presets";
    private static final String KEY_LAST_EDIT = "last_edit";
    private static final String EXPORT_FOLDER = "MyLight";

    private static final int PANEL_SIZE = 0;
    private static final int PANEL_COLOR = 1;
    private static final int PANEL_CURVE = 2;
    private static final int PANEL_EFFECTS = 3;

    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger renderVersion = new AtomicInteger();
    private final Handler renderHandler = new Handler(Looper.getMainLooper());
    private final GeometryAdjustments geometry = new GeometryAdjustments();
    private final ColorAdjustments adjustments = new ColorAdjustments();
    private CurveSet curves = new CurveSet();
    private final List<SliderBinding> sliderBindings = new ArrayList<>();

    private GpuImageView imageView;
    private LinearLayout panelTabs;
    private LinearLayout controls;
    private ScrollView controlScroll;
    private Bitmap originalBitmap;
    private Bitmap fastSourceBitmap;
    private Bitmap qualitySourceBitmap;
    private Bitmap previewBitmap;
    private boolean suppressSliderEvents;
    private int activePanel = PANEL_COLOR;
    private int activeCurveChannel = CurveSet.LUMINANCE;
    private int activeMixChannel = ColorAdjustments.MIX_RED;
    private CurveView curveView;
    private CropOverlayView cropOverlayView;
    private final Runnable qualityRenderRunnable = () -> renderPreview(false);
    private SharedPreferences preferences;
    private boolean renderInFlight;
    private boolean renderQueued;
    private boolean queuedInteractive;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
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
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_OPEN_IMAGE) {
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
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(418)));
        }
        return root;
    }

    private View createImageFrame() {
        FrameLayout imageFrame = new FrameLayout(this);
        imageFrame.setBackgroundColor(Color.rgb(6, 7, 10));
        imageView = new GpuImageView(this);
        imageView.setImageBitmap(previewBitmap);
        imageFrame.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        cropOverlayView = new CropOverlayView(this, geometry);
        cropOverlayView.setImageSize(originalBitmap.getWidth(), originalBitmap.getHeight());
        cropOverlayView.setVisibility(View.GONE);
        cropOverlayView.setListener(finished -> {
            if (finished) {
                renderPreview(false);
            } else {
                renderInteractivePreview();
            }
            if (finished) {
                renderControls();
            }
        });
        imageFrame.addView(cropOverlayView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return imageFrame;
    }

    private View createControlPanel(boolean landscape) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.rgb(17, 21, 28));
        panel.setPadding(landscape ? dp(10) : 0, 0, landscape ? dp(10) : 0, 0);
        panelTabs = new LinearLayout(this);
        panelTabs.setOrientation(LinearLayout.HORIZONTAL);
        panelTabs.setPadding(dp(12), dp(10), dp(12), dp(10));
        panelTabs.setBackgroundColor(Color.rgb(15, 19, 26));
        panel.addView(panelTabs, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(62)));
        rebuildPanelTabs();

        controlScroll = new ScrollView(this);
        controlScroll.setFillViewport(false);
        controlScroll.setBackgroundColor(Color.rgb(18, 22, 30));
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(18), dp(12), dp(18), landscape ? dp(28) : dp(24));
        controls.setBackgroundColor(Color.rgb(18, 22, 30));
        controlScroll.addView(controls);
        panel.addView(controlScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return panel;
    }

    private int landscapePanelWidth() {
        int widthPixels = getResources().getDisplayMetrics().widthPixels;
        int target = Math.round(widthPixels * 0.38f);
        return Math.max(dp(330), Math.min(dp(430), target));
    }

    private View createToolbar() {
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(14), dp(7), dp(14), dp(7));
        toolbar.setBackgroundColor(Color.rgb(15, 18, 24));

        TextView title = new TextView(this);
        title.setText("MyLight");
        title.setTextColor(Color.WHITE);
        title.setTextSize(21f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        Button openButton = createButton("相册");
        openButton.setOnClickListener(v -> openImage());
        toolbar.addView(openButton, new LinearLayout.LayoutParams(dp(76), dp(42)));

        Button resetButton = createButton("重置");
        resetButton.setOnClickListener(v -> resetAll());
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(dp(76), dp(42));
        resetParams.leftMargin = dp(8);
        toolbar.addView(resetButton, resetParams);

        Button saveButton = createButton("保存");
        saveButton.setOnClickListener(v -> saveImage(null));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(dp(76), dp(42));
        saveParams.leftMargin = dp(8);
        toolbar.addView(saveButton, saveParams);
        return toolbar;
    }

    private void rebuildPanelTabs() {
        panelTabs.removeAllViews();
        addPanelTab("尺寸", PANEL_SIZE);
        addPanelTab("色彩", PANEL_COLOR);
        addPanelTab("曲线", PANEL_CURVE);
        addPanelTab("效果", PANEL_EFFECTS);
    }

    private void addPanelTab(String label, int panel) {
        Button button = createButton(label, activePanel == panel);
        button.setTypeface(Typeface.DEFAULT, activePanel == panel ? Typeface.BOLD : Typeface.NORMAL);
        button.setOnClickListener(v -> {
            activePanel = panel;
            rebuildPanelTabs();
            renderControls();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1f);
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        panelTabs.addView(button, params);
    }

    private void renderControls() {
        controls.removeAllViews();
        sliderBindings.clear();
        curveView = null;
        if (activePanel == PANEL_SIZE) {
            renderSizePanel();
        } else if (activePanel == PANEL_CURVE) {
            renderCurvePanel();
        } else if (activePanel == PANEL_EFFECTS) {
            renderEffectsPanel();
        } else {
            renderColorPanel();
        }
        updateCropOverlay();
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
            geometry.resetCropForMode(sourceAspect());
            cropOverlayView.invalidate();
            renderPreview();
        });
        addModeButton(cropRow, "完成", true, this::finishCrop);
        controls.addView(cropRow);
        addSlider("裁剪缩放", geometry.cropZoom, 0f, 1f, value -> geometry.cropZoom = value);
        addSlider("任意旋转", geometry.rotateDegrees, -45f, 45f, value -> geometry.rotateDegrees = value);

        LinearLayout rotateRow = createButtonRow();
        addModeButton(rotateRow, "左转90", false, () -> {
            geometry.quarterTurns = (geometry.quarterTurns + 3) % 4;
            renderPreview();
        });
        addModeButton(rotateRow, "右转90", false, () -> {
            geometry.quarterTurns = (geometry.quarterTurns + 1) % 4;
            renderPreview();
        });
        addModeButton(rotateRow, "归零", false, () -> {
            geometry.rotateDegrees = 0f;
            geometry.quarterTurns = 0;
            renderControls();
            renderPreview();
        });
        controls.addView(rotateRow);
    }

    private void finishCrop() {
        activePanel = PANEL_COLOR;
        persistCurrentEdit();
        rebuildPanelTabs();
        renderControls();
        renderPreview();
        Toast.makeText(this, "已应用裁剪", Toast.LENGTH_SHORT).show();
    }

    private void renderColorPanel() {
        controls.addView(createSectionLabel("预设滤镜"));
        controls.addView(createPresetStrip());
        controls.addView(createSectionLabel("基础色彩"));
        addSlider("明亮度", adjustments.brightness, -1f, 1f, value -> adjustments.brightness = value);
        addSlider("高光", adjustments.highlights, -1f, 1f, value -> adjustments.highlights = value);
        addSlider("阴影", adjustments.shadows, -1f, 1f, value -> adjustments.shadows = value);
        addSlider("对比度", adjustments.contrast, -1f, 1f, value -> adjustments.contrast = value);
        addSlider("饱和度", adjustments.saturation, -1f, 1f, value -> adjustments.saturation = value);
        addSlider("色温", adjustments.temperature, -1f, 1f, value -> adjustments.temperature = value);
        addSlider("色调", adjustments.tint, -1f, 1f, value -> adjustments.tint = value);
        addSlider("曝光", adjustments.exposure, -1f, 1f, value -> adjustments.exposure = value);

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
        curveView.setListener(finished -> {
            if (finished) {
                renderPreview(false);
            } else {
                renderInteractivePreview();
            }
        });
        controls.addView(curveView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(170)));

        Button resetCurveButton = createButton("重置当前曲线");
        resetCurveButton.setOnClickListener(v -> {
            curves.reset(activeCurveChannel);
            curveView.invalidate();
            renderPreview();
        });
        controls.addView(resetCurveButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
    }

    private void renderEffectsPanel() {
        controls.addView(createSectionLabel("效果"));
        addSlider("晕影", adjustments.vignette, -1f, 1f, value -> adjustments.vignette = value);
        addSlider("去模糊", adjustments.dehaze, -1f, 1f, value -> adjustments.dehaze = value);
        addSlider("氛围", adjustments.ambiance, -1f, 1f, value -> adjustments.ambiance = value);
        addSlider("褪色", adjustments.fade, 0f, 1f, value -> adjustments.fade = value);
    }

    private View createPresetStrip() {
        HorizontalScrollView scrollView = new HorizontalScrollView(this);
        scrollView.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(10));
        Preset lastEdit = loadLastEditPreset();
        if (lastEdit != null) {
            Button button = createButton("上次修改", true);
            button.setOnClickListener(v -> applyPreset(lastEdit));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(112), dp(42));
            params.rightMargin = dp(8);
            row.addView(button, params);
        }
        Button saveFilterButton = createButton("存为滤镜", true);
        saveFilterButton.setOnClickListener(v -> showSaveFilterDialog());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(dp(112), dp(42));
        saveParams.rightMargin = dp(8);
        row.addView(saveFilterButton, saveParams);
        for (Preset preset : Preset.defaults()) {
            Button button = createButton(preset.name);
            button.setOnClickListener(v -> applyPreset(preset));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(92), dp(42));
            params.rightMargin = dp(8);
            row.addView(button, params);
        }
        for (Preset preset : loadCustomPresets()) {
            Button button = createButton(preset.name);
            button.setOnClickListener(v -> applyPreset(preset));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(104), dp(42));
            params.rightMargin = dp(8);
            row.addView(button, params);
        }
        scrollView.addView(row);
        return scrollView;
    }

    private TextView createSectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.rgb(224, 231, 240));
        label.setTextSize(14f);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPadding(dp(2), dp(16), 0, dp(9));
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
        TextView valueLabel = new TextView(this);
        valueLabel.setTextColor(Color.rgb(232, 237, 244));
        valueLabel.setTextSize(13f);
        valueLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        controls.addView(valueLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(26)));

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(200);
        seekBar.setProgressTintList(ColorStateList.valueOf(Color.rgb(95, 179, 243)));
        seekBar.setThumbTintList(ColorStateList.valueOf(Color.rgb(232, 244, 255)));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(47, 54, 65)));
        seekBar.setSplitTrack(false);
        SliderBinding binding = new SliderBinding(seekBar, initialValue, min, max);
        sliderBindings.add(binding);
        setSeekValue(seekBar, initialValue, min, max);
        updateSliderLabel(valueLabel, label, initialValue);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float value = min + (max - min) * progress / 200f;
                consumer.accept(value);
                binding.value = value;
                updateSliderLabel(valueLabel, label, value);
                if (!suppressSliderEvents) {
                    if (fromUser) {
                        renderInteractivePreview();
                    } else {
                        renderPreview(false);
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                renderPreview(false);
            }
        });
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        seekParams.bottomMargin = dp(6);
        controls.addView(seekBar, seekParams);
    }

    private void updateSliderLabel(TextView label, String name, float value) {
        label.setText(name + "  " + String.format(java.util.Locale.US, "%.2f", value));
    }

    private void setSeekValue(SeekBar seekBar, float value, float min, float max) {
        int progress = Math.round((value - min) * 200f / (max - min));
        seekBar.setProgress(Math.max(0, Math.min(200, progress)));
    }

    private void setCropMode(int mode) {
        geometry.cropMode = mode;
        geometry.resetCropForMode(sourceAspect());
        if (cropOverlayView != null) {
            cropOverlayView.invalidate();
        }
        renderControls();
        renderPreview();
    }

    private void applyPreset(Preset preset) {
        ColorAdjustments presetAdjustments = preset.adjustments.copy();
        copyAdjustments(presetAdjustments, adjustments);
        curves = preset.newCurves();
        renderControls();
        renderPreview();
    }

    private void resetAll() {
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
        persistCurrentEdit();
        imageView.updateState(previewGeometry(), adjustments, curves, previewDisplayAspect());
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
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_OPEN_IMAGE);
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
                    rebuildRenderSources();
                    if (imageView != null) {
                        imageView.setImageBitmap(scaled);
                    }
                    if (cropOverlayView != null) {
                        cropOverlayView.setImageSize(scaled.getWidth(), scaled.getHeight());
                    }
                    resetAll();
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
        Bitmap source = originalBitmap;
        if (source == null) {
            return;
        }
        Toast.makeText(this, "正在保存到 Pictures/MyLight", Toast.LENGTH_SHORT).show();
        GeometryAdjustments geometrySnapshot = geometry.copy();
        ColorAdjustments adjustmentsSnapshot = adjustments.copy();
        CurveSet curveSnapshot = curves.copy();
        renderExecutor.execute(() -> {
            Bitmap bitmap = ImageProcessor.apply(source, geometrySnapshot, adjustmentsSnapshot, curveSnapshot);
            Uri outputUri = uri == null ? createDefaultImageUri() : uri;
            if (outputUri == null) {
                bitmap.recycle();
                runOnUiThread(() -> Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show());
                return;
            }
            try (OutputStream outputStream = getContentResolver().openOutputStream(outputUri)) {
                if (outputStream == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)) {
                    runOnUiThread(() -> Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show());
                    bitmap.recycle();
                    return;
                }
                markImageReady(outputUri);
                bitmap.recycle();
                runOnUiThread(() -> Toast.makeText(this, "已保存到 Pictures/MyLight", Toast.LENGTH_SHORT).show());
            } catch (IOException exception) {
                bitmap.recycle();
                runOnUiThread(() -> Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show());
            }
        });
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
        button.setTextColor(selected ? Color.WHITE : Color.rgb(224, 230, 238));
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        GradientDrawable background = new GradientDrawable();
        if (selected) {
            background.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
            background.setColors(new int[] {blend(Color.rgb(18, 22, 29), accent, 0.55f),
                    blend(Color.rgb(18, 22, 29), accent, 0.82f)});
        } else {
            background.setColor(blend(Color.rgb(25, 30, 38), accent, 0.08f));
        }
        background.setStroke(dp(1), selected ? blend(Color.WHITE, accent, 0.58f)
                : blend(Color.rgb(62, 72, 86), accent, 0.32f));
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

    private void updateCropOverlay() {
        if (cropOverlayView == null) {
            return;
        }
        cropOverlayView.setVisibility(activePanel == PANEL_SIZE ? View.VISIBLE : View.GONE);
        cropOverlayView.invalidate();
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
        System.arraycopy(source.mixHue, 0, target.mixHue, 0, source.mixHue.length);
        System.arraycopy(source.mixSaturation, 0, target.mixSaturation, 0, source.mixSaturation.length);
        System.arraycopy(source.mixLuminance, 0, target.mixLuminance, 0, source.mixLuminance.length);
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

const preview = document.querySelector("#preview");
const previewCtx = preview.getContext("2d", { willReadFrequently: true });
const cropOverlay = document.querySelector("#cropOverlay");
const cropCtx = cropOverlay.getContext("2d");
const controls = document.querySelector("#dynamicControls");

const fixedCurveX = [0, 64, 128, 192, 255];
const maxSourceEdge = 1600;
const maxRenderEdge = 1100;
const mixNames = ["红", "橙", "黄", "绿", "青", "蓝", "紫", "洋红"];
const mixCenters = [0, 30, 60, 120, 180, 230, 275, 315];
const curveNames = ["亮度", "红", "绿", "蓝"];
const curveColors = ["#5fb3f3", "#ee5b5b", "#65c47a", "#5d91f5"];

const state = {
  source: null,
  panel: "filter",
  adjustPanel: "light",
  activeCurve: 0,
  activeMix: 0,
  activeCurvePoint: -1,
  renderPending: false,
  geometry: {
    cropMode: "original",
    cropLeft: 0,
    cropTop: 0,
    cropRight: 1,
    cropBottom: 1,
    cropZoom: 0,
    rotateDegrees: 0,
    quarterTurns: 0,
  },
  adjustments: {
    brightness: 0,
    highlights: 0,
    shadows: 0,
    contrast: 0,
    saturation: 0,
    temperature: 0,
    tint: 0,
    exposure: 0,
    fade: 0,
    vignette: 0,
    dehaze: 0,
    ambiance: 0,
    mixHue: Array(8).fill(0),
    mixSaturation: Array(8).fill(0),
    mixLuminance: Array(8).fill(0),
  },
  curves: [
    defaultCurve(),
    defaultCurve(),
    defaultCurve(),
    defaultCurve(),
  ],
};

const presets = [
  ["Clean", 0, 0, 0, 0.08, 0.06, 0, 0, 0, 0, 0, 0, [0, 64, 128, 192, 255]],
  ["Vivid", 0.02, -0.04, 0.06, 0.18, 0.22, 0.03, 0, 0.05, 0, 0, 0.16, [0, 56, 132, 205, 255]],
  ["Warm", 0.04, -0.03, 0.04, 0.08, 0.12, 0.45, 0.05, 0.04, 0.02, 0, 0.08, [4, 66, 132, 198, 255]],
  ["Cool", 0, 0, 0.03, 0.1, 0.04, -0.38, -0.03, 0, 0, 0, 0.04, [0, 62, 130, 198, 255]],
  ["Matte", 0.02, -0.08, 0.14, -0.08, -0.04, 0.08, 0, 0, 0.42, 0, 0.1, [24, 70, 125, 184, 236]],
  ["Film", -0.01, -0.06, 0.1, 0.04, -0.12, 0.18, 0.08, -0.02, 0.28, 0, 0.12, [18, 58, 122, 190, 246]],
  ["Mono", 0.01, -0.02, 0.05, 0.16, -0.95, 0, 0, 0.03, 0.08, 0, 0, [8, 60, 128, 204, 255]],
];

function init() {
  state.source = createSampleImage();
  renderTabs();
  renderControls();
  renderPreview();
  bindGlobalEvents();
}

function bindGlobalEvents() {
  document.querySelector("#fileInput").addEventListener("change", loadFile);
  document.querySelector("#resetAll").addEventListener("click", resetAll);
  document.querySelector("#download").addEventListener("click", downloadPreview);
  window.addEventListener("resize", updateCropOverlay);
  cropOverlay.addEventListener("pointerdown", handleCropDown);
  cropOverlay.addEventListener("pointermove", handleCropMove);
  cropOverlay.addEventListener("pointerup", handleCropUp);
  cropOverlay.addEventListener("pointercancel", handleCropUp);
}

function renderTabs() {
  const tabs = document.querySelector("#panelTabs");
  tabs.replaceChildren();
  [
    ["filter", "滤镜"],
    ["adjust", "调节"],
    ["curve", "曲线"],
    ["size", "裁剪"],
  ].forEach(([key, label]) => {
    const button = createButton(label);
    button.classList.toggle("active", key === "adjust" ? isAdjustPanel(state.panel) : state.panel === key);
    button.addEventListener("click", () => {
      state.panel = key === "adjust" ? state.adjustPanel : key;
      renderTabs();
      renderControls();
    });
    tabs.appendChild(button);
  });
  updateCropOverlay();
}

function renderControls() {
  controls.replaceChildren();
  if (isAdjustPanel(state.panel)) renderAdjustSwitcher();
  if (state.panel === "size") renderSizePanel();
  else if (state.panel === "filter") renderFilterPanel();
  else if (state.panel === "light") renderLightPanel();
  else if (state.panel === "hsl") renderHslPanel();
  else if (state.panel === "curve") renderCurvePanel();
  else if (state.panel === "effects") renderEffectsPanel();
  else renderColorPanel();
}

function isAdjustPanel(panel) {
  return panel === "light" || panel === "color" || panel === "hsl" || panel === "effects";
}

function renderAdjustSwitcher() {
  const row = document.createElement("div");
  row.className = "adjust-tabs";
  [
    ["light", "光线"],
    ["color", "色彩"],
    ["hsl", "HSL"],
    ["effects", "效果"],
  ].forEach(([key, label]) => {
    const button = createButton(label);
    button.classList.toggle("active", state.panel === key);
    button.addEventListener("click", () => {
      state.panel = key;
      state.adjustPanel = key;
      renderTabs();
      renderControls();
    });
    row.appendChild(button);
  });
  controls.appendChild(row);
}

function renderSizePanel() {
  addSectionTitle("裁剪比例");
  const modes = [
    ["free", "自由"],
    ["original", "原图"],
    ["square", "1:1"],
    ["4:3", "4:3"],
    ["16:9", "16:9"],
  ];
  addModeRow(modes, state.geometry.cropMode, (mode) => {
    state.geometry.cropMode = mode;
    resetCropForMode();
    renderControls();
    renderPreview();
  });
  addModeRow([["resetCrop", "重置裁剪"]], "", () => {
    resetCropForMode();
    renderPreview();
  });
  addSlider("裁剪缩放", state.geometry.cropZoom, 0, 1, (v) => state.geometry.cropZoom = v);
  addSlider("任意旋转", state.geometry.rotateDegrees, -45, 45, (v) => state.geometry.rotateDegrees = v);
  addModeRow([["left", "左转90"], ["right", "右转90"], ["reset", "归零"]], "", (mode) => {
    if (mode === "left") state.geometry.quarterTurns = (state.geometry.quarterTurns + 3) % 4;
    if (mode === "right") state.geometry.quarterTurns = (state.geometry.quarterTurns + 1) % 4;
    if (mode === "reset") {
      state.geometry.rotateDegrees = 0;
      state.geometry.quarterTurns = 0;
      renderControls();
    }
    renderPreview();
  });
}

function renderFilterPanel() {
  addPresetStrip();
}

function renderLightPanel() {
  addSectionTitle("光线");
  const a = state.adjustments;
  addSlider("曝光", a.exposure, -1, 1, (v) => a.exposure = v);
  addSlider("明亮度", a.brightness, -1, 1, (v) => a.brightness = v);
  addSlider("高光", a.highlights, -1, 1, (v) => a.highlights = v);
  addSlider("阴影", a.shadows, -1, 1, (v) => a.shadows = v);
  addSlider("对比度", a.contrast, -1, 1, (v) => a.contrast = v);
}

function renderColorPanel() {
  addSectionTitle("基础色彩");
  const a = state.adjustments;
  addSlider("饱和度", a.saturation, -1, 1, (v) => a.saturation = v);
  addSlider("色温", a.temperature, -1, 1, (v) => a.temperature = v);
  addSlider("色调", a.tint, -1, 1, (v) => a.tint = v);
}

function renderHslPanel() {
  addSectionTitle("原色 / HSL");
  addModeRow(mixNames.map((name, index) => [String(index), name]), String(state.activeMix), (value) => {
    state.activeMix = Number(value);
    renderControls();
  });
  addSlider("色相", a.mixHue[state.activeMix], -1, 1, (v) => a.mixHue[state.activeMix] = v);
  addSlider("饱和度", a.mixSaturation[state.activeMix], -1, 1, (v) => a.mixSaturation[state.activeMix] = v);
  addSlider("明亮度", a.mixLuminance[state.activeMix], -1, 1, (v) => a.mixLuminance[state.activeMix] = v);
}

function renderCurvePanel() {
  addSectionTitle("曲线通道");
  addModeRow(curveNames.map((name, index) => [String(index), name]), String(state.activeCurve), (value) => {
    state.activeCurve = Number(value);
    renderControls();
  });
  const canvas = document.createElement("canvas");
  canvas.id = "curve";
  canvas.width = 520;
  canvas.height = 220;
  controls.appendChild(canvas);
  canvas.addEventListener("pointerdown", handleCurveDown);
  canvas.addEventListener("pointermove", handleCurveMove);
  canvas.addEventListener("pointerup", () => {
    state.activeCurvePoint = -1;
    drawCurve(canvas);
  });
  canvas.addEventListener("pointercancel", () => {
    state.activeCurvePoint = -1;
    drawCurve(canvas);
  });
  drawCurve(canvas);
  const reset = createButton("重置当前曲线");
  reset.addEventListener("click", () => {
    state.curves[state.activeCurve] = defaultCurve();
    drawCurve(canvas);
    renderPreview();
  });
  controls.appendChild(reset);
}

function renderEffectsPanel() {
  addSectionTitle("效果");
  const a = state.adjustments;
  addSlider("晕影", a.vignette, -1, 1, (v) => a.vignette = v);
  addSlider("去模糊", a.dehaze, -1, 1, (v) => a.dehaze = v);
  addSlider("氛围", a.ambiance, -1, 1, (v) => a.ambiance = v);
  addSlider("褪色", a.fade, 0, 1, (v) => a.fade = v);
}

function addPresetStrip() {
  const row = document.createElement("nav");
  row.className = "preset-row";
  presets.forEach((preset) => {
    const button = createButton(`${preset[0]}\n默认`);
    button.classList.add("preset-card");
    button.addEventListener("click", () => applyPreset(preset));
    row.appendChild(button);
  });
  controls.appendChild(row);
}

function addSectionTitle(text) {
  const section = document.createElement("section");
  section.className = "section";
  const title = document.createElement("h2");
  title.textContent = text;
  section.appendChild(title);
  controls.appendChild(section);
}

function addModeRow(items, activeValue, onSelect) {
  const row = document.createElement("div");
  row.className = "mode-row";
  items.forEach(([value, label]) => {
    const button = createButton(label);
    button.classList.toggle("active", value === activeValue);
    button.addEventListener("click", () => onSelect(value));
    row.appendChild(button);
  });
  controls.appendChild(row);
}

function addSlider(label, value, min, max, onChange) {
  const wrap = document.createElement("div");
  wrap.className = "slider";
  const labelEl = document.createElement("label");
  const name = document.createElement("span");
  const output = document.createElement("output");
  name.textContent = label;
  output.textContent = format(value);
  labelEl.append(name, output);

  const input = document.createElement("input");
  input.type = "range";
  input.min = String(min);
  input.max = String(max);
  input.step = "0.01";
  input.value = String(value);
  input.addEventListener("input", () => {
    const next = Number(input.value);
    onChange(next);
    output.textContent = format(next);
    requestPreviewRender();
  });
  wrap.append(labelEl, input);
  controls.appendChild(wrap);
}

function createButton(label) {
  const button = document.createElement("button");
  button.type = "button";
  button.textContent = label;
  return button;
}

function applyPreset(preset) {
  const a = state.adjustments;
  [a.brightness, a.highlights, a.shadows, a.contrast, a.saturation, a.temperature, a.tint,
    a.exposure, a.fade, a.dehaze, a.ambiance] = preset.slice(1, 12);
  state.curves[0] = fixedCurve(preset[12]);
  state.curves[1] = defaultCurve();
  state.curves[2] = defaultCurve();
  state.curves[3] = defaultCurve();
  renderControls();
  renderPreview();
}

function resetAll() {
  state.geometry = {
    cropMode: "original",
    cropLeft: 0,
    cropTop: 0,
    cropRight: 1,
    cropBottom: 1,
    cropZoom: 0,
    rotateDegrees: 0,
    quarterTurns: 0,
  };
  Object.assign(state.adjustments, {
    brightness: 0, highlights: 0, shadows: 0, contrast: 0, saturation: 0, temperature: 0, tint: 0,
    exposure: 0, fade: 0, vignette: 0, dehaze: 0, ambiance: 0,
    mixHue: Array(8).fill(0), mixSaturation: Array(8).fill(0), mixLuminance: Array(8).fill(0),
  });
  state.curves = [defaultCurve(), defaultCurve(), defaultCurve(), defaultCurve()];
  renderControls();
  renderPreview();
}

function loadFile(event) {
  const file = event.target.files?.[0];
  if (!file) return;
  const img = new Image();
  img.onload = () => {
    const canvas = document.createElement("canvas");
    const maxSize = maxSourceEdge;
    const scale = Math.min(1, maxSize / Math.max(img.naturalWidth, img.naturalHeight));
    canvas.width = Math.max(1, Math.round(img.naturalWidth * scale));
    canvas.height = Math.max(1, Math.round(img.naturalHeight * scale));
    canvas.getContext("2d").drawImage(img, 0, 0, canvas.width, canvas.height);
    URL.revokeObjectURL(img.src);
    state.source = canvas;
    resetAll();
  };
  img.src = URL.createObjectURL(file);
}

function requestPreviewRender() {
  if (state.renderPending) return;
  state.renderPending = true;
  requestAnimationFrame(() => {
    state.renderPending = false;
    renderPreview();
  });
}

function renderPreview() {
  const transformed = transformSource();
  preview.width = transformed.width;
  preview.height = transformed.height;
  previewCtx.drawImage(transformed, 0, 0);
  const image = previewCtx.getImageData(0, 0, preview.width, preview.height);
  const data = image.data;
  const curveLookup = state.curves.map(buildCurveLookup);
  const colorMixEnabled = hasColorMix();
  for (let i = 0; i < data.length; i += 4) {
    const x = ((i / 4) % preview.width) / Math.max(1, preview.width - 1);
    const y = Math.floor((i / 4) / preview.width) / Math.max(1, preview.height - 1);
    const adjusted = adjustRgb(data[i], data[i + 1], data[i + 2], x, y, curveLookup, colorMixEnabled);
    data[i] = adjusted[0];
    data[i + 1] = adjusted[1];
    data[i + 2] = adjusted[2];
  }
  previewCtx.putImageData(image, 0, 0);
  updateCropOverlay();
}

function transformSource() {
  const source = state.source;
  const canvas = document.createElement("canvas");
  const renderScale = Math.min(1, maxRenderEdge / Math.max(source.width, source.height));
  canvas.width = Math.max(1, Math.round(source.width * renderScale));
  canvas.height = Math.max(1, Math.round(source.height * renderScale));
  const ctx = canvas.getContext("2d");
  ctx.fillStyle = "#08090c";
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  const crop = cropRect(source);
  const scaleBase = Math.max(canvas.width / crop.width, canvas.height / crop.height);
  const angle = (state.geometry.rotateDegrees + state.geometry.quarterTurns * 90) * Math.PI / 180;
  const cover = Math.max(1, Math.abs(Math.cos(angle)) + Math.abs(Math.sin(angle)));
  const scale = scaleBase * (1 + state.geometry.cropZoom * 0.8) * cover;
  ctx.translate(canvas.width / 2, canvas.height / 2);
  ctx.rotate(angle);
  ctx.scale(scale, scale);
  ctx.drawImage(source, -crop.x - crop.width / 2, -crop.y - crop.height / 2);
  return canvas;
}

function cropRect(source) {
  const left = source.width * state.geometry.cropLeft;
  const top = source.height * state.geometry.cropTop;
  const right = source.width * state.geometry.cropRight;
  const bottom = source.height * state.geometry.cropBottom;
  return { x: left, y: top, width: right - left, height: bottom - top };
}

function adjustRgb(red, green, blue, nx, ny, curveLookup, colorMixEnabled) {
  const a = state.adjustments;
  let r = red / 255;
  let g = green / 255;
  let b = blue / 255;
  const exposureScale = 2 ** a.exposure;
  r = r * exposureScale + a.brightness * 0.35;
  g = g * exposureScale + a.brightness * 0.35;
  b = b * exposureScale + a.brightness * 0.35;

  let luminance = r * 0.299 + g * 0.587 + b * 0.114;
  const highlightMask = smoothstep(0.45, 1, luminance);
  const shadowMask = 1 - smoothstep(0, 0.55, luminance);
  r += a.highlights * 0.28 * highlightMask + a.shadows * 0.32 * shadowMask;
  g += a.highlights * 0.28 * highlightMask + a.shadows * 0.32 * shadowMask;
  b += a.highlights * 0.28 * highlightMask + a.shadows * 0.32 * shadowMask;

  if (a.ambiance !== 0) {
    const amount = a.ambiance * 0.28;
    r += (0.5 - luminance) * amount;
    g += (0.5 - luminance) * amount;
    b += (0.5 - luminance) * amount;
  }

  const contrastScale = a.contrast >= 0 ? 1 + a.contrast * 1.6 : 1 + a.contrast * 0.85;
  r = (r - 0.5) * contrastScale + 0.5;
  g = (g - 0.5) * contrastScale + 0.5;
  b = (b - 0.5) * contrastScale + 0.5;

  if (a.dehaze !== 0) {
    const dehazeScale = a.dehaze >= 0 ? 1 + a.dehaze * 0.9 : 1 + a.dehaze * 0.35;
    r = (r - 0.5) * dehazeScale + 0.5 - a.dehaze * 0.03;
    g = (g - 0.5) * dehazeScale + 0.5 - a.dehaze * 0.03;
    b = (b - 0.5) * dehazeScale + 0.5 - a.dehaze * 0.03;
  }

  luminance = r * 0.299 + g * 0.587 + b * 0.114;
  const saturationScale = a.saturation >= 0 ? 1 + a.saturation * 1.5 : 1 + a.saturation;
  r = luminance + (r - luminance) * saturationScale;
  g = luminance + (g - luminance) * saturationScale;
  b = luminance + (b - luminance) * saturationScale;

  if (colorMixEnabled) [r, g, b] = applyColorMix(r, g, b);
  r += a.temperature * 0.12 + a.tint * 0.04;
  g -= a.tint * 0.08;
  b -= a.temperature * 0.12;
  b += a.tint * 0.04;

  if (a.fade > 0) {
    r = r * (1 - a.fade * 0.35) + 0.06 * a.fade;
    g = g * (1 - a.fade * 0.35) + 0.06 * a.fade;
    b = b * (1 - a.fade * 0.35) + 0.06 * a.fade;
  }
  if (a.vignette !== 0) {
    const edge = smoothstep(0.18, 0.72, Math.hypot(nx - 0.5, ny - 0.5));
    const scale = 1 - a.vignette * 0.65 * edge;
    r *= scale;
    g *= scale;
    b *= scale;
  }

  let ri = curveLookup[1][toChannel(r)];
  let gi = curveLookup[2][toChannel(g)];
  let bi = curveLookup[3][toChannel(b)];
  ri = curveLookup[0][ri];
  gi = curveLookup[0][gi];
  bi = curveLookup[0][bi];
  return [ri, gi, bi];
}

function applyColorMix(r, g, b) {
  const hsv = rgbToHsv(clamp01(r), clamp01(g), clamp01(b));
  const a = state.adjustments;
  let hueShift = 0;
  let satShift = 0;
  let lumShift = 0;
  let total = 0;
  for (let i = 0; i < 8; i += 1) {
    const weight = hueWeight(hsv[0], mixCenters[i]);
    if (weight <= 0) continue;
    total += weight;
    hueShift += a.mixHue[i] * 36 * weight;
    satShift += a.mixSaturation[i] * 0.55 * weight;
    lumShift += a.mixLuminance[i] * 0.32 * weight;
  }
  if (total > 0) {
    hueShift /= total;
    satShift /= total;
    lumShift /= total;
  }
  hsv[0] = (hsv[0] + hueShift + 360) % 360;
  hsv[1] = clamp01(hsv[1] + satShift);
  const rgb = hsvToRgb(hsv[0], hsv[1], hsv[2]);
  return [rgb[0] + lumShift, rgb[1] + lumShift, rgb[2] + lumShift];
}

function hasColorMix() {
  const a = state.adjustments;
  for (let i = 0; i < 8; i += 1) {
    if (a.mixHue[i] !== 0 || a.mixSaturation[i] !== 0 || a.mixLuminance[i] !== 0) return true;
  }
  return false;
}

function drawCurve(canvas) {
  const ctx = canvas.getContext("2d");
  const w = canvas.width;
  const h = canvas.height;
  const pad = 22;
  ctx.clearRect(0, 0, w, h);
  ctx.fillStyle = "#1c1f26";
  ctx.fillRect(0, 0, w, h);
  ctx.strokeStyle = "#3e444e";
  ctx.lineWidth = 1;
  for (let i = 0; i <= 4; i += 1) {
    const x = pad + ((w - pad * 2) * i) / 4;
    const y = pad + ((h - pad * 2) * i) / 4;
    ctx.beginPath();
    ctx.moveTo(x, pad);
    ctx.lineTo(x, h - pad);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(pad, y);
    ctx.lineTo(w - pad, y);
    ctx.stroke();
  }
  ctx.strokeStyle = curveColors[state.activeCurve];
  ctx.lineWidth = 5;
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  ctx.beginPath();
  for (let value = 0; value <= 255; value += 1) {
    const x = pointX(value, w, pad);
    const y = pointY(mapCurve(value, state.curves[state.activeCurve]), h, pad);
    if (value === 0) ctx.moveTo(x, y);
    else ctx.lineTo(x, y);
  }
  ctx.stroke();
  ctx.fillStyle = "#fff";
  state.curves[state.activeCurve].forEach((point, index) => {
    ctx.beginPath();
    ctx.fillStyle = index === state.activeCurvePoint ? curveColors[state.activeCurve] : "#fff";
    ctx.arc(pointX(point.x, w, pad), pointY(point.y, h, pad), 8, 0, Math.PI * 2);
    ctx.fill();
  });
}

function handleCurveDown(event) {
  const canvas = event.currentTarget;
  canvas.setPointerCapture(event.pointerId);
  const pos = curvePointer(event, canvas);
  const nearest = nearestCurvePoint(pos.x, pos.y, canvas);
  if (nearest >= 0) state.activeCurvePoint = nearest;
  else state.activeCurvePoint = addCurvePoint(canvasToCurveX(pos.x, canvas), canvasToCurveY(pos.y, canvas));
  updateCurvePoint(pos.x, pos.y, canvas);
}

function handleCurveMove(event) {
  if (state.activeCurvePoint < 0) return;
  const pos = curvePointer(event, event.currentTarget);
  updateCurvePoint(pos.x, pos.y, event.currentTarget);
}

function updateCurvePoint(canvasX, canvasY, canvas) {
  const curve = state.curves[state.activeCurve];
  const point = curve[state.activeCurvePoint];
  point.y = canvasToCurveY(canvasY, canvas);
  if (state.activeCurvePoint === 0) point.x = 0;
  else if (state.activeCurvePoint === curve.length - 1) point.x = 255;
  else {
    const left = curve[state.activeCurvePoint - 1].x + 4;
    const right = curve[state.activeCurvePoint + 1].x - 4;
    point.x = clamp(canvasToCurveX(canvasX, canvas), left, right);
  }
  drawCurve(canvas);
  requestPreviewRender();
}

function curvePointer(event, canvas) {
  const rect = canvas.getBoundingClientRect();
  return { x: ((event.clientX - rect.left) / rect.width) * canvas.width, y: ((event.clientY - rect.top) / rect.height) * canvas.height };
}

function nearestCurvePoint(canvasX, canvasY, canvas) {
  let bestIndex = -1;
  let bestDistance = 20 * 20;
  state.curves[state.activeCurve].forEach((point, index) => {
    const dx = canvasX - pointX(point.x, canvas.width, 22);
    const dy = canvasY - pointY(point.y, canvas.height, 22);
    const distance = dx * dx + dy * dy;
    if (distance < bestDistance) {
      bestDistance = distance;
      bestIndex = index;
    }
  });
  return bestIndex;
}

function addCurvePoint(x, y) {
  const curve = state.curves[state.activeCurve];
  if (curve.length >= 12) return nearestCurveByValue(x, y);
  let insertAt = 1;
  while (insertAt < curve.length && curve[insertAt].x < x) insertAt += 1;
  const left = curve[insertAt - 1].x + 4;
  const right = curve[insertAt].x - 4;
  if (left > right) return nearestCurveByValue(x, y);
  curve.splice(insertAt, 0, { x: clamp(x, left, right), y: clamp255(y) });
  return insertAt;
}

function nearestCurveByValue(x, y) {
  let bestIndex = 0;
  let bestDistance = Infinity;
  state.curves[state.activeCurve].forEach((point, index) => {
    const dx = point.x - x;
    const dy = point.y - y;
    const distance = dx * dx + dy * dy;
    if (distance < bestDistance) {
      bestDistance = distance;
      bestIndex = index;
    }
  });
  return bestIndex;
}

function canvasToCurveX(canvasX, canvas) {
  const pad = 22;
  return clamp255(Math.round(((canvasX - pad) / (canvas.width - pad * 2)) * 255));
}

function canvasToCurveY(canvasY, canvas) {
  const pad = 22;
  return clamp255(Math.round((1 - (canvasY - pad) / (canvas.height - pad * 2)) * 255));
}

function resetCropForMode() {
  const sourceAspect = state.source.width / state.source.height;
  const targetAspect = state.geometry.cropMode === "square" ? 1
    : state.geometry.cropMode === "4:3" ? 4 / 3
      : state.geometry.cropMode === "16:9" ? 16 / 9
        : sourceAspect;
  let width = 1;
  let height = 1;
  if (sourceAspect > targetAspect) width = targetAspect / sourceAspect;
  else height = sourceAspect / targetAspect;
  setCropRect((1 - width) / 2, (1 - height) / 2, (1 + width) / 2, (1 + height) / 2);
}

function setCropRect(left, top, right, bottom) {
  const minSize = 0.08;
  left = clamp(left, 0, 1);
  top = clamp(top, 0, 1);
  right = clamp(right, 0, 1);
  bottom = clamp(bottom, 0, 1);
  if (right - left < minSize) {
    if (state.cropHandle === "left" || state.cropHandle === "top-left" || state.cropHandle === "bottom-left") left = right - minSize;
    else right = left + minSize;
  }
  if (bottom - top < minSize) {
    if (state.cropHandle === "top" || state.cropHandle === "top-left" || state.cropHandle === "top-right") top = bottom - minSize;
    else bottom = top + minSize;
  }
  state.geometry.cropLeft = clamp(left, 0, 1 - minSize);
  state.geometry.cropTop = clamp(top, 0, 1 - minSize);
  state.geometry.cropRight = clamp(right, state.geometry.cropLeft + minSize, 1);
  state.geometry.cropBottom = clamp(bottom, state.geometry.cropTop + minSize, 1);
}

function updateCropOverlay() {
  const active = state.panel === "size";
  cropOverlay.hidden = !active;
  if (!active) return;
  const previewRect = preview.getBoundingClientRect();
  const stageRect = preview.parentElement.getBoundingClientRect();
  cropOverlay.style.left = `${previewRect.left - stageRect.left}px`;
  cropOverlay.style.top = `${previewRect.top - stageRect.top}px`;
  cropOverlay.style.width = `${previewRect.width}px`;
  cropOverlay.style.height = `${previewRect.height}px`;
  cropOverlay.width = preview.width;
  cropOverlay.height = preview.height;
  drawCropOverlay();
}

function drawCropOverlay() {
  const w = cropOverlay.width;
  const h = cropOverlay.height;
  cropCtx.clearRect(0, 0, w, h);
  if (cropOverlay.hidden) return;
  const rect = cropCanvasRect();
  cropCtx.fillStyle = "rgba(0,0,0,.45)";
  cropCtx.fillRect(0, 0, w, rect.top);
  cropCtx.fillRect(0, rect.bottom, w, h - rect.bottom);
  cropCtx.fillRect(0, rect.top, rect.left, rect.bottom - rect.top);
  cropCtx.fillRect(rect.right, rect.top, w - rect.right, rect.bottom - rect.top);
  cropCtx.strokeStyle = "#fff";
  cropCtx.lineWidth = 3;
  cropCtx.strokeRect(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top);
  cropCtx.strokeStyle = "rgba(255,255,255,.58)";
  cropCtx.lineWidth = 1;
  for (let i = 1; i <= 2; i += 1) {
    const x = lerp(rect.left, rect.right, i / 3);
    const y = lerp(rect.top, rect.bottom, i / 3);
    cropCtx.beginPath();
    cropCtx.moveTo(x, rect.top);
    cropCtx.lineTo(x, rect.bottom);
    cropCtx.moveTo(rect.left, y);
    cropCtx.lineTo(rect.right, y);
    cropCtx.stroke();
  }
  cropCtx.fillStyle = "#fff";
  [[rect.left, rect.top], [rect.right, rect.top], [rect.left, rect.bottom], [rect.right, rect.bottom],
    [rect.left, (rect.top + rect.bottom) / 2], [rect.right, (rect.top + rect.bottom) / 2],
    [(rect.left + rect.right) / 2, rect.top], [(rect.left + rect.right) / 2, rect.bottom]].forEach(([x, y]) => {
    cropCtx.beginPath();
    cropCtx.arc(x, y, 7, 0, Math.PI * 2);
    cropCtx.fill();
  });
}

function cropCanvasRect() {
  const g = state.geometry;
  return {
    left: g.cropLeft * cropOverlay.width,
    top: g.cropTop * cropOverlay.height,
    right: g.cropRight * cropOverlay.width,
    bottom: g.cropBottom * cropOverlay.height,
  };
}

function handleCropDown(event) {
  if (state.panel !== "size") return;
  const pos = cropPointer(event);
  const rect = cropCanvasRect();
  state.cropHandle = hitCropHandle(pos.x, pos.y, rect);
  if (!state.cropHandle) return;
  cropOverlay.setPointerCapture(event.pointerId);
  state.geometry.cropMode = "free";
  state.cropLastX = pos.x;
  state.cropLastY = pos.y;
}

function handleCropMove(event) {
  if (!state.cropHandle) return;
  const pos = cropPointer(event);
  updateCropFromPointer(pos.x, pos.y, false);
}

function handleCropUp(event) {
  if (!state.cropHandle) return;
  const pos = cropPointer(event);
  updateCropFromPointer(pos.x, pos.y, true);
  state.cropHandle = "";
  renderControls();
}

function updateCropFromPointer(x, y, finished) {
  const dx = (x - state.cropLastX) / cropOverlay.width;
  const dy = (y - state.cropLastY) / cropOverlay.height;
  let { cropLeft: left, cropTop: top, cropRight: right, cropBottom: bottom } = state.geometry;
  if (state.cropHandle === "move") {
    const width = right - left;
    const height = bottom - top;
    left = clamp(left + dx, 0, 1 - width);
    top = clamp(top + dy, 0, 1 - height);
    right = left + width;
    bottom = top + height;
  } else {
    if (state.cropHandle.includes("left")) left += dx;
    if (state.cropHandle.includes("right")) right += dx;
    if (state.cropHandle.includes("top")) top += dy;
    if (state.cropHandle.includes("bottom")) bottom += dy;
  }
  setCropRect(left, top, right, bottom);
  state.cropLastX = x;
  state.cropLastY = y;
  drawCropOverlay();
  requestPreviewRender();
  if (finished) drawCropOverlay();
}

function cropPointer(event) {
  const rect = cropOverlay.getBoundingClientRect();
  return {
    x: ((event.clientX - rect.left) / rect.width) * cropOverlay.width,
    y: ((event.clientY - rect.top) / rect.height) * cropOverlay.height,
  };
}

function hitCropHandle(x, y, rect) {
  const radius = 34;
  const near = (tx, ty) => (x - tx) ** 2 + (y - ty) ** 2 <= radius ** 2;
  if (near(rect.left, rect.top)) return "top-left";
  if (near(rect.right, rect.top)) return "top-right";
  if (near(rect.left, rect.bottom)) return "bottom-left";
  if (near(rect.right, rect.bottom)) return "bottom-right";
  if (Math.abs(x - rect.left) <= radius && y >= rect.top && y <= rect.bottom) return "left";
  if (Math.abs(x - rect.right) <= radius && y >= rect.top && y <= rect.bottom) return "right";
  if (Math.abs(y - rect.top) <= radius && x >= rect.left && x <= rect.right) return "top";
  if (Math.abs(y - rect.bottom) <= radius && x >= rect.left && x <= rect.right) return "bottom";
  if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) return "move";
  return "";
}

function defaultCurve() {
  return [{ x: 0, y: 0 }, { x: 255, y: 255 }];
}

function fixedCurve(values) {
  return values.map((value, index) => ({ x: fixedCurveX[index], y: value }));
}

function createSampleImage() {
  const canvas = document.createElement("canvas");
  canvas.width = 1400;
  canvas.height = 980;
  const ctx = canvas.getContext("2d");
  const gradient = ctx.createLinearGradient(0, 0, canvas.width, canvas.height);
  gradient.addColorStop(0, "#273448");
  gradient.addColorStop(0.55, "#9f6b57");
  gradient.addColorStop(1, "#e8c57f");
  ctx.fillStyle = gradient;
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  const sun = ctx.createRadialGradient(canvas.width * 0.78, canvas.height * 0.24, 12, canvas.width * 0.78, canvas.height * 0.24, canvas.width * 0.18);
  sun.addColorStop(0, "rgba(255,238,196,.95)");
  sun.addColorStop(1, "rgba(255,238,196,0)");
  ctx.fillStyle = sun;
  ctx.beginPath();
  ctx.arc(canvas.width * 0.78, canvas.height * 0.24, canvas.width * 0.2, 0, Math.PI * 2);
  ctx.fill();
  ctx.fillStyle = "rgba(20,26,32,.68)";
  ctx.beginPath();
  ctx.moveTo(0, canvas.height * 0.62);
  ctx.lineTo(canvas.width * 0.28, canvas.height * 0.42);
  ctx.lineTo(canvas.width * 0.56, canvas.height * 0.68);
  ctx.lineTo(canvas.width, canvas.height * 0.45);
  ctx.lineTo(canvas.width, canvas.height);
  ctx.lineTo(0, canvas.height);
  ctx.closePath();
  ctx.fill();
  ctx.fillStyle = "rgba(12,16,20,.42)";
  ctx.fillRect(0, canvas.height * 0.74, canvas.width, canvas.height * 0.26);
  ["#cbd5df", "#b46a56", "#71a76d", "#5180b2"].forEach((color, index) => {
    ctx.fillStyle = color;
    ctx.fillRect(canvas.width * (0.08 + index * 0.22), canvas.height * 0.82, canvas.width * 0.18, canvas.height * 0.045);
  });
  return canvas;
}

function downloadPreview() {
  const link = document.createElement("a");
  link.download = "tonelab-web-debug.png";
  link.href = preview.toDataURL("image/png");
  link.click();
}

function rgbToHsv(r, g, b) {
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const d = max - min;
  let h = 0;
  if (d !== 0) {
    if (max === r) h = ((g - b) / d) % 6;
    else if (max === g) h = (b - r) / d + 2;
    else h = (r - g) / d + 4;
    h *= 60;
    if (h < 0) h += 360;
  }
  const s = max === 0 ? 0 : d / max;
  return [h, s, max];
}

function hsvToRgb(h, s, v) {
  const c = v * s;
  const x = c * (1 - Math.abs((h / 60) % 2 - 1));
  const m = v - c;
  let r = 0, g = 0, b = 0;
  if (h < 60) [r, g, b] = [c, x, 0];
  else if (h < 120) [r, g, b] = [x, c, 0];
  else if (h < 180) [r, g, b] = [0, c, x];
  else if (h < 240) [r, g, b] = [0, x, c];
  else if (h < 300) [r, g, b] = [x, 0, c];
  else [r, g, b] = [c, 0, x];
  return [r + m, g + m, b + m];
}

function mapCurve(value, curve) {
  const input = clamp255(value);
  if (input <= curve[0].x) return curve[0].y;
  for (let i = 1; i < curve.length; i += 1) {
    if (input <= curve[i].x) return interpolateCurve(curve, i - 1, i, input);
  }
  return curve[curve.length - 1].y;
}

function buildCurveLookup(curve) {
  const lookup = new Array(256);
  for (let i = 0; i < lookup.length; i += 1) {
    lookup[i] = mapCurve(i, curve);
  }
  return lookup;
}

function interpolateCurve(curve, leftIndex, rightIndex, input) {
  const p0 = curve[leftIndex];
  const p1 = curve[rightIndex];
  const t = (input - p0.x) / Math.max(1, p1.x - p0.x);
  const m0 = curveSlope(curve, leftIndex);
  const m1 = curveSlope(curve, rightIndex);
  const t2 = t * t;
  const t3 = t2 * t;
  const h00 = 2 * t3 - 3 * t2 + 1;
  const h10 = t3 - 2 * t2 + t;
  const h01 = -2 * t3 + 3 * t2;
  const h11 = t3 - t2;
  const dx = p1.x - p0.x;
  return clamp255(Math.round(h00 * p0.y + h10 * dx * m0 + h01 * p1.y + h11 * dx * m1));
}

function curveSlope(curve, index) {
  if (index === 0) return segmentSlope(curve[0], curve[1]);
  if (index === curve.length - 1) return segmentSlope(curve[index - 1], curve[index]);
  return (curve[index + 1].y - curve[index - 1].y) / Math.max(1, curve[index + 1].x - curve[index - 1].x);
}

function segmentSlope(left, right) {
  return (right.y - left.y) / Math.max(1, right.x - left.x);
}

function hueWeight(hue, center) {
  let distance = Math.abs(hue - center);
  distance = Math.min(distance, 360 - distance);
  return Math.max(0, 1 - distance / 45);
}

function smoothstep(edge0, edge1, value) {
  const x = clamp01((value - edge0) / (edge1 - edge0));
  return x * x * (3 - 2 * x);
}

function pointX(value, width, pad) {
  return pad + ((width - pad * 2) * value) / 255;
}

function pointY(value, height, pad) {
  return height - pad - ((height - pad * 2) * value) / 255;
}

function toChannel(value) {
  return clamp255(Math.round(clamp01(value) * 255));
}

function clamp01(value) {
  return Math.max(0, Math.min(1, value));
}

function clamp255(value) {
  return Math.max(0, Math.min(255, value));
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function lerp(start, end, amount) {
  return start + (end - start) * amount;
}

function format(value) {
  return Number(value).toFixed(2);
}

init();

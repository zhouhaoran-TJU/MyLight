package com.example.lightroomclone;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.util.Log;

import com.example.lightroomclone.core.ColorAdjustments;
import com.example.lightroomclone.core.ColorMath;
import com.example.lightroomclone.core.CurveSet;
import com.example.lightroomclone.core.GeometryAdjustments;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

final class GpuImageView extends GLSurfaceView {
    private static final String TAG = "GpuImageView";
    private final ToneRenderer renderer;

    GpuImageView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        renderer = new ToneRenderer();
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    void setImageBitmap(Bitmap bitmap) {
        renderer.setImageBitmap(bitmap);
        requestRender();
    }

    void updateState(GeometryAdjustments geometry, ColorAdjustments adjustments, CurveSet curves) {
        renderer.updateState(geometry, adjustments, curves);
        requestRender();
    }

    private static final class ToneRenderer implements Renderer {
        private static final float[] VERTICES = {
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f
        };

        private final FloatBuffer vertexBuffer = ByteBuffer.allocateDirect(VERTICES.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        private final Object lock = new Object();
        private final float[] adjustments = new float[12];
        private final float[] mixHue = new float[ColorAdjustments.MIX_COUNT];
        private final float[] mixSaturation = new float[ColorAdjustments.MIX_COUNT];
        private final float[] mixLuminance = new float[ColorAdjustments.MIX_COUNT];
        private final float[] crop = new float[] {0f, 0f, 1f, 1f};
        private final byte[] curveBytes = new byte[256 * 4 * 4];

        private Bitmap pendingBitmap;
        private int program;
        private int imageTexture;
        private int curveTexture;
        private int viewWidth = 1;
        private int viewHeight = 1;
        private int imageWidth = 1;
        private int imageHeight = 1;
        private float cropZoom;
        private float rotateDegrees;
        private int quarterTurns;
        private boolean curveDirty = true;
        private boolean stateDirty = true;

        ToneRenderer() {
            vertexBuffer.put(VERTICES).position(0);
            writeIdentityCurve();
        }

        void setImageBitmap(Bitmap bitmap) {
            synchronized (lock) {
                pendingBitmap = bitmap;
                if (bitmap != null) {
                    imageWidth = Math.max(1, bitmap.getWidth());
                    imageHeight = Math.max(1, bitmap.getHeight());
                }
            }
        }

        void updateState(GeometryAdjustments geometry, ColorAdjustments sourceAdjustments, CurveSet curves) {
            synchronized (lock) {
                adjustments[0] = sourceAdjustments.brightness;
                adjustments[1] = sourceAdjustments.highlights;
                adjustments[2] = sourceAdjustments.shadows;
                adjustments[3] = sourceAdjustments.contrast;
                adjustments[4] = sourceAdjustments.saturation;
                adjustments[5] = sourceAdjustments.temperature;
                adjustments[6] = sourceAdjustments.tint;
                adjustments[7] = sourceAdjustments.exposure;
                adjustments[8] = sourceAdjustments.fade;
                adjustments[9] = sourceAdjustments.vignette;
                adjustments[10] = sourceAdjustments.dehaze;
                adjustments[11] = sourceAdjustments.ambiance;
                System.arraycopy(sourceAdjustments.mixHue, 0, mixHue, 0, mixHue.length);
                System.arraycopy(sourceAdjustments.mixSaturation, 0, mixSaturation, 0, mixSaturation.length);
                System.arraycopy(sourceAdjustments.mixLuminance, 0, mixLuminance, 0, mixLuminance.length);
                crop[0] = geometry.cropLeft;
                crop[1] = geometry.cropTop;
                crop[2] = geometry.cropRight;
                crop[3] = geometry.cropBottom;
                cropZoom = geometry.cropZoom;
                rotateDegrees = geometry.rotateDegrees;
                quarterTurns = geometry.quarterTurns;
                writeCurve(0, ColorMath.buildLookup(curves.luminance));
                writeCurve(1, ColorMath.buildLookup(curves.red));
                writeCurve(2, ColorMath.buildLookup(curves.green));
                writeCurve(3, ColorMath.buildLookup(curves.blue));
                curveDirty = true;
                stateDirty = true;
            }
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(8f / 255f, 9f / 255f, 12f / 255f, 1f);
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            imageTexture = createTexture();
            curveTexture = createTexture();
            uploadCurveTexture();
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            viewWidth = Math.max(1, width);
            viewHeight = Math.max(1, height);
            GLES20.glViewport(0, 0, viewWidth, viewHeight);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            uploadPendingImage();
            if (imageTexture == 0 || program == 0) {
                return;
            }
            if (curveDirty) {
                uploadCurveTexture();
            }
            GLES20.glUseProgram(program);
            int positionLocation = GLES20.glGetAttribLocation(program, "a_position");
            GLES20.glEnableVertexAttribArray(positionLocation);
            GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);

            float[] imageRect = imageRect();
            float[] localAdjustments;
            float[] localMixHue;
            float[] localMixSaturation;
            float[] localMixLuminance;
            float[] localCrop;
            float localCropZoom;
            float localRotateDegrees;
            int localQuarterTurns;
            synchronized (lock) {
                localAdjustments = adjustments.clone();
                localMixHue = mixHue.clone();
                localMixSaturation = mixSaturation.clone();
                localMixLuminance = mixLuminance.clone();
                localCrop = crop.clone();
                localCropZoom = cropZoom;
                localRotateDegrees = rotateDegrees;
                localQuarterTurns = quarterTurns;
                stateDirty = false;
            }
            float angle = (float) Math.toRadians(localRotateDegrees + localQuarterTurns * 90f);
            float cover = Math.max(1f, Math.abs((float) Math.cos(angle)) + Math.abs((float) Math.sin(angle)));
            float cropWidth = Math.max(0.01f, localCrop[2] - localCrop[0]);
            float cropHeight = Math.max(0.01f, localCrop[3] - localCrop[1]);
            float sourceAspect = imageWidth / (float) imageHeight;
            float scale = Math.max(1f / cropWidth, 1f / cropHeight) * (1f + localCropZoom * 0.8f) * cover;

            GLES20.glUniform4fv(uniform("u_imageRect"), 1, imageRect, 0);
            GLES20.glUniform2f(uniform("u_imageSize"), imageWidth, imageHeight);
            GLES20.glUniform4fv(uniform("u_crop"), 1, localCrop, 0);
            GLES20.glUniform1f(uniform("u_scale"), scale);
            GLES20.glUniform1f(uniform("u_angle"), angle);
            GLES20.glUniform1f(uniform("u_sourceAspect"), sourceAspect);
            GLES20.glUniform1fv(uniform("u_adjustments"), localAdjustments.length, localAdjustments, 0);
            GLES20.glUniform1fv(uniform("u_mixHue"), localMixHue.length, localMixHue, 0);
            GLES20.glUniform1fv(uniform("u_mixSaturation"), localMixSaturation.length, localMixSaturation, 0);
            GLES20.glUniform1fv(uniform("u_mixLuminance"), localMixLuminance.length, localMixLuminance, 0);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexture);
            GLES20.glUniform1i(uniform("u_image"), 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTexture);
            GLES20.glUniform1i(uniform("u_curve"), 1);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionLocation);
        }

        private void uploadPendingImage() {
            Bitmap bitmap;
            synchronized (lock) {
                bitmap = pendingBitmap;
                pendingBitmap = null;
            }
            if (bitmap == null) {
                return;
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexture);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            imageWidth = Math.max(1, bitmap.getWidth());
            imageHeight = Math.max(1, bitmap.getHeight());
        }

        private void uploadCurveTexture() {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTexture);
            ByteBuffer buffer;
            synchronized (lock) {
                buffer = ByteBuffer.allocateDirect(curveBytes.length).order(ByteOrder.nativeOrder());
                buffer.put(curveBytes).position(0);
                curveDirty = false;
            }
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 256, 4, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
        }

        private float[] imageRect() {
            float viewAspect = viewWidth / (float) viewHeight;
            float imageAspect = imageWidth / (float) imageHeight;
            float width = 1f;
            float height = 1f;
            if (viewAspect > imageAspect) {
                width = imageAspect / viewAspect;
            } else {
                height = viewAspect / imageAspect;
            }
            float left = (1f - width) * 0.5f;
            float top = (1f - height) * 0.5f;
            return new float[] {left, top, width, height};
        }

        private int uniform(String name) {
            return GLES20.glGetUniformLocation(program, name);
        }

        private void writeIdentityCurve() {
            int[] values = new int[256];
            for (int i = 0; i < values.length; i++) {
                values[i] = i;
            }
            for (int row = 0; row < 4; row++) {
                writeCurve(row, values);
            }
        }

        private void writeCurve(int row, int[] values) {
            for (int x = 0; x < 256; x++) {
                int offset = (row * 256 + x) * 4;
                byte value = (byte) Math.max(0, Math.min(255, values[x]));
                curveBytes[offset] = value;
                curveBytes[offset + 1] = value;
                curveBytes[offset + 2] = value;
                curveBytes[offset + 3] = (byte) 255;
            }
        }

        private int createTexture() {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            return textures[0];
        }

        private int createProgram(String vertexShader, String fragmentShader) {
            int vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader);
            int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader);
            int result = GLES20.glCreateProgram();
            GLES20.glAttachShader(result, vertex);
            GLES20.glAttachShader(result, fragment);
            GLES20.glLinkProgram(result);
            int[] status = new int[1];
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                Log.e(TAG, "GL program link failed: " + GLES20.glGetProgramInfoLog(result));
            }
            return result;
        }

        private int compileShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] status = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                Log.e(TAG, "GL shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
            }
            return shader;
        }
    }

    private static final String VERTEX_SHADER =
            "attribute vec2 a_position;\n"
                    + "varying vec2 v_view;\n"
                    + "void main() {\n"
                    + "  v_view = a_position * 0.5 + 0.5;\n"
                    + "  gl_Position = vec4(a_position, 0.0, 1.0);\n"
                    + "}\n";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n"
                    + "varying vec2 v_view;\n"
                    + "uniform sampler2D u_image;\n"
                    + "uniform sampler2D u_curve;\n"
                    + "uniform vec4 u_imageRect;\n"
                    + "uniform vec2 u_imageSize;\n"
                    + "uniform vec4 u_crop;\n"
                    + "uniform float u_scale;\n"
                    + "uniform float u_angle;\n"
                    + "uniform float u_sourceAspect;\n"
                    + "uniform float u_adjustments[12];\n"
                    + "uniform float u_mixHue[8];\n"
                    + "uniform float u_mixSaturation[8];\n"
                    + "uniform float u_mixLuminance[8];\n"
                    + "float centerAt(int i) {\n"
                    + "  if (i == 0) return 0.0; if (i == 1) return 30.0; if (i == 2) return 60.0; if (i == 3) return 120.0;\n"
                    + "  if (i == 4) return 180.0; if (i == 5) return 230.0; if (i == 6) return 275.0; return 315.0;\n"
                    + "}\n"
                    + "float clamp01(float v) { return clamp(v, 0.0, 1.0); }\n"
                    + "float curve(float v, float row) { return texture2D(u_curve, vec2((clamp01(v) * 255.0 + 0.5) / 256.0, (row + 0.5) / 4.0)).r; }\n"
                    + "vec3 rgb2hsv(vec3 c) {\n"
                    + "  vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);\n"
                    + "  vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));\n"
                    + "  vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));\n"
                    + "  float d = q.x - min(q.w, q.y);\n"
                    + "  float e = 1.0e-10;\n"
                    + "  return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)) * 360.0, d / (q.x + e), q.x);\n"
                    + "}\n"
                    + "vec3 hsv2rgb(vec3 c) {\n"
                    + "  vec3 p = abs(fract(c.xxx / 60.0 + vec3(0.0, 4.0, 2.0) / 6.0) * 6.0 - 3.0);\n"
                    + "  return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);\n"
                    + "}\n"
                    + "float hueWeight(float hue, float center) {\n"
                    + "  float d = abs(hue - center);\n"
                    + "  d = min(d, 360.0 - d);\n"
                    + "  return max(0.0, 1.0 - d / 45.0);\n"
                    + "}\n"
                    + "vec3 applyMix(vec3 rgb) {\n"
                    + "  vec3 hsv = rgb2hsv(clamp(rgb, 0.0, 1.0));\n"
                    + "  float hueShift = 0.0; float satShift = 0.0; float lumShift = 0.0; float total = 0.0;\n"
                    + "  for (int i = 0; i < 8; i++) {\n"
                    + "    float w = hueWeight(hsv.x, centerAt(i));\n"
                    + "    total += w; hueShift += u_mixHue[i] * 36.0 * w; satShift += u_mixSaturation[i] * 0.55 * w; lumShift += u_mixLuminance[i] * 0.32 * w;\n"
                    + "  }\n"
                    + "  if (total > 0.0) { hueShift /= total; satShift /= total; lumShift /= total; }\n"
                    + "  hsv.x = mod(hsv.x + hueShift + 360.0, 360.0); hsv.y = clamp01(hsv.y + satShift);\n"
                    + "  return hsv2rgb(hsv) + lumShift;\n"
                    + "}\n"
                    + "vec3 adjust(vec3 rgb, vec2 p) {\n"
                    + "  float brightness = u_adjustments[0]; float highlights = u_adjustments[1]; float shadows = u_adjustments[2]; float contrast = u_adjustments[3];\n"
                    + "  float saturation = u_adjustments[4]; float temperature = u_adjustments[5]; float tint = u_adjustments[6]; float exposure = u_adjustments[7];\n"
                    + "  float fade = u_adjustments[8]; float vignette = u_adjustments[9]; float dehaze = u_adjustments[10]; float ambiance = u_adjustments[11];\n"
                    + "  rgb = rgb * pow(2.0, exposure) + brightness * 0.35;\n"
                    + "  float l = dot(rgb, vec3(0.299, 0.587, 0.114));\n"
                    + "  float hm = smoothstep(0.45, 1.0, l); float sm = 1.0 - smoothstep(0.0, 0.55, l);\n"
                    + "  rgb += highlights * 0.28 * hm + shadows * 0.32 * sm;\n"
                    + "  rgb += (0.5 - l) * ambiance * 0.28;\n"
                    + "  float cs = contrast >= 0.0 ? 1.0 + contrast * 1.6 : 1.0 + contrast * 0.85;\n"
                    + "  rgb = (rgb - 0.5) * cs + 0.5;\n"
                    + "  float ds = dehaze >= 0.0 ? 1.0 + dehaze * 0.9 : 1.0 + dehaze * 0.35;\n"
                    + "  rgb = (rgb - 0.5) * ds + 0.5 - dehaze * 0.03;\n"
                    + "  l = dot(rgb, vec3(0.299, 0.587, 0.114));\n"
                    + "  float ss = saturation >= 0.0 ? 1.0 + saturation * 1.5 : 1.0 + saturation;\n"
                    + "  rgb = vec3(l) + (rgb - vec3(l)) * ss;\n"
                    + "  rgb = applyMix(rgb);\n"
                    + "  rgb += vec3(temperature * 0.12 + tint * 0.04, -tint * 0.08, -temperature * 0.12 + tint * 0.04);\n"
                    + "  if (fade > 0.0) rgb = rgb * (1.0 - fade * 0.35) + vec3(0.06 * fade);\n"
                    + "  float edge = smoothstep(0.18, 0.72, distance(p, vec2(0.5)));\n"
                    + "  rgb *= 1.0 - vignette * 0.65 * edge;\n"
                    + "  rgb.r = curve(curve(rgb.r, 1.0), 0.0); rgb.g = curve(curve(rgb.g, 2.0), 0.0); rgb.b = curve(curve(rgb.b, 3.0), 0.0);\n"
                    + "  return clamp(rgb, 0.0, 1.0);\n"
                    + "}\n"
                    + "void main() {\n"
                    + "  vec2 local = (v_view - u_imageRect.xy) / u_imageRect.zw;\n"
                    + "  if (local.x < 0.0 || local.x > 1.0 || local.y < 0.0 || local.y > 1.0) { gl_FragColor = vec4(8.0/255.0, 9.0/255.0, 12.0/255.0, 1.0); return; }\n"
                    + "  vec2 canvas = local * u_imageSize;\n"
                    + "  vec2 d = canvas - u_imageSize * 0.5;\n"
                    + "  float c = cos(u_angle); float s = sin(u_angle);\n"
                    + "  vec2 srcDelta = vec2(c * d.x + s * d.y, -s * d.x + c * d.y) / u_scale;\n"
                    + "  vec2 cropCenter = vec2((u_crop.x + u_crop.z) * 0.5 * u_imageSize.x, (u_crop.y + u_crop.w) * 0.5 * u_imageSize.y);\n"
                    + "  vec2 uv = (cropCenter + srcDelta) / u_imageSize;\n"
                    + "  if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) { gl_FragColor = vec4(8.0/255.0, 9.0/255.0, 12.0/255.0, 1.0); return; }\n"
                    + "  vec4 color = texture2D(u_image, vec2(uv.x, 1.0 - uv.y));\n"
                    + "  gl_FragColor = vec4(adjust(color.rgb, local), color.a);\n"
                    + "}\n";
}

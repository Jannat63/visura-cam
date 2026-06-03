package com.visura.cam.camera

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30

import android.graphics.SurfaceTexture
import android.opengl.*
import android.view.Surface
import com.visura.cam.correction.ColorCorrectionEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PreviewCorrectionRenderer — OpenGL ES 3.1 renderer for Realme 8 Pro.
 *
 * Applies real-time LUT (Look-Up Table) color correction to the camera
 * preview using the Adreno 618 GPU. Zero-lag correction — runs entirely
 * on GPU, does NOT touch CPU per frame.
 *
 * Pipeline:
 *   Camera2 → SurfaceTexture → OES texture → Fragment shader (LUT) → Output Surface
 *
 * The 3D LUT encodes the yellow cast correction specific to this phone's
 * damaged Samsung HM2 sensor. LUT is pre-computed and uploaded once to GPU.
 */
class PreviewCorrectionRenderer(
    private val colorEngine: ColorCorrectionEngine,
    private val lensId: String = ColorCorrectionEngine.LENS_MAIN_108MP
) {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var shaderProgram = 0
    private var cameraTextureId = 0
    private var lutTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    var inputSurface: Surface? = null
        private set

    // Fullscreen quad vertices (position + UV)
    private val quadVertices = floatArrayOf(
        -1f, -1f, 0f, 1f,
         1f, -1f, 1f, 1f,
        -1f,  1f, 0f, 0f,
         1f,  1f, 1f, 0f
    )

    // ── GLSL Shaders ──────────────────────────────────────────────

    private val VERTEX_SHADER = """
        #version 300 es
        in vec4 aPosition;
        in vec2 aTexCoord;
        out vec2 vTexCoord;
        uniform mat4 uTexMatrix;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    /**
     * Fragment shader — applies 3D LUT correction to each pixel.
     * OES sampler reads directly from camera feed.
     * 3D LUT sampler applies color transformation.
     *
     * The LUT contains the specific correction for the Realme 8 Pro's
     * yellow cast: maps warm (yellow-shifted) input colors to
     * neutral correct output colors.
     */
    private val FRAGMENT_SHADER = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision mediump float;

        uniform samplerExternalOES uCameraTexture;   // Live camera frame
        uniform sampler3D uLutTexture;               // 64^3 correction LUT
        uniform float uLutSize;                      // 64.0
        uniform float uCorrectionStrength;           // 0.0–1.0 blend
        uniform bool uCorrectionEnabled;

        in vec2 vTexCoord;
        out vec4 fragColor;

        vec3 applyLUT(vec3 color) {
            float scale = (uLutSize - 1.0) / uLutSize;
            float offset = 0.5 / uLutSize;
            vec3 lutCoord = color * scale + offset;
            return texture(uLutTexture, lutCoord).rgb;
        }

        void main() {
            vec4 cameraColor = texture(uCameraTexture, vTexCoord);

            if (!uCorrectionEnabled) {
                fragColor = cameraColor;
                return;
            }

            // Apply 3D LUT correction (yellow cast fix)
            vec3 corrected = applyLUT(cameraColor.rgb);

            // Blend between corrected and original for strength control
            vec3 result = mix(cameraColor.rgb, corrected, uCorrectionStrength);

            fragColor = vec4(result, cameraColor.a);
        }
    """.trimIndent()

    // ── Initialization ────────────────────────────────────────────

    fun initialize(outputSurface: Surface, width: Int, height: Int) {
        setupEGL(outputSurface)
        setupShaders()
        setupCameraTexture()
        uploadLUT()
    }

    private fun setupEGL(outputSurface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(eglDisplay, null, 0, null, 0)

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], outputSurface, surfaceAttribs, 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun setupShaders() {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        shaderProgram = GLES30.glCreateProgram().also { prog ->
            GLES30.glAttachShader(prog, vs)
            GLES30.glAttachShader(prog, fs)
            GLES30.glLinkProgram(prog)
        }
    }

    private fun setupCameraTexture() {
        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        cameraTextureId = texIds[0]
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES.let { target ->
            GLES30.glBindTexture(target, cameraTextureId)
            GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        }
        surfaceTexture = SurfaceTexture(cameraTextureId)
        inputSurface = Surface(surfaceTexture)
    }

    /**
     * Upload pre-computed 3D LUT to GPU texture.
     * Called once during initialization.
     */
    private fun uploadLUT() {
        val lutData = colorEngine.generatePreviewLUT(lensId)
        val lutSize = 64

        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        lutTextureId = texIds[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

        val buffer = ByteBuffer.allocateDirect(lutData.size)
            .order(ByteOrder.nativeOrder())
            .put(lutData)
            .also { it.position(0) }

        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8,
            lutSize, lutSize, lutSize,
            0, GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, buffer
        )
    }

    /**
     * Draw one corrected frame. Called per-frame from camera callback.
     */
    fun drawFrame(correctionEnabled: Boolean = true, strength: Float = 1.0f) {
        surfaceTexture?.updateTexImage()

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(shaderProgram)

        // Bind camera texture (unit 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES.let { GLES30.glBindTexture(it, cameraTextureId) }
        GLES30.glUniform1i(GLES30.glGetUniformLocation(shaderProgram, "uCameraTexture"), 0)

        // Bind LUT texture (unit 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(shaderProgram, "uLutTexture"), 1)

        // Uniforms
        GLES30.glUniform1f(GLES30.glGetUniformLocation(shaderProgram, "uLutSize"), 64f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(shaderProgram, "uCorrectionStrength"), strength)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(shaderProgram, "uCorrectionEnabled"),
            if (correctionEnabled) 1 else 0)

        // Draw fullscreen quad
        val vbo = IntArray(1)
        GLES30.glGenBuffers(1, vbo, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        val vertexBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(quadVertices); position(0) }
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quadVertices.size * 4, vertexBuffer, GLES30.GL_STATIC_DRAW)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    /**
     * Reload LUT when correction profile changes (e.g. after calibration).
     */
    fun reloadLUT() {
        GLES30.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
        uploadLUT()
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        return shader
    }

    fun release() {
        surfaceTexture?.release()
        inputSurface?.release()
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
    }
}

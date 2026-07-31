package com.marathonrecomp.launcher.gpu;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Log;

import java.util.Locale;

/**
 * Identifies the GPU so the launcher can tell whether Turnip is applicable.
 *
 * <p>Turnip only drives <b>Adreno</b> hardware. Pointing it at a Mali, Xclipse or PowerVR
 * device does not fall back gracefully — the loader either fails to create a device or the
 * app renders nothing — so it is worth detecting up front and saying so.</p>
 *
 * <p>The renderer string is read through a throwaway off-screen EGL context, which is the
 * cheapest reliable way to get it without a Vulkan dependency.</p>
 */
public final class GpuInfo {

    private static final String TAG = "MarathonDroid/Gpu";

    public final String vendor;
    public final String renderer;

    private GpuInfo(String vendor, String renderer) {
        this.vendor = vendor == null ? "" : vendor;
        this.renderer = renderer == null ? "" : renderer;
    }

    /** Cached so the EGL probe only ever runs once. */
    private static GpuInfo cached;

    /** Runs a small EGL probe — call off the UI thread the first time. */
    public static synchronized GpuInfo query() {
        if (cached != null) {
            return cached;
        }

        cached = probe();
        return cached;
    }

    public boolean isKnown() {
        return !renderer.isEmpty();
    }

    public boolean isAdreno() {
        return renderer.toLowerCase(Locale.ROOT).contains("adreno");
    }

    /** True when we positively identified a GPU that Turnip cannot drive. */
    public boolean isTurnipIncompatible() {
        return isKnown() && !isAdreno();
    }

    public String displayName() {
        if (!renderer.isEmpty()) {
            return renderer;
        }

        return "Unknown GPU";
    }

    private static GpuInfo probe() {
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;

        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);

            if (display == EGL14.EGL_NO_DISPLAY) {
                return new GpuInfo("", "");
            }

            int[] version = new int[2];

            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return new GpuInfo("", "");
            }

            int[] configAttribs = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE
            };

            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];

            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                    || numConfigs[0] == 0) {
                return new GpuInfo("", "");
            }

            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[] { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE }, 0);

            if (context == EGL14.EGL_NO_CONTEXT) {
                return new GpuInfo("", "");
            }

            surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                    new int[] { EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE }, 0);

            if (surface == EGL14.EGL_NO_SURFACE
                    || !EGL14.eglMakeCurrent(display, surface, surface, context)) {
                return new GpuInfo("", "");
            }

            String vendor = GLES20.glGetString(GLES20.GL_VENDOR);
            String renderer = GLES20.glGetString(GLES20.GL_RENDERER);

            Log.i(TAG, "GPU: " + renderer + " (" + vendor + ")");

            return new GpuInfo(vendor, renderer);
        } catch (Throwable t) {
            Log.w(TAG, "GPU probe failed", t);
            return new GpuInfo("", "");
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);

                if (surface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, surface);
                }

                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context);
                }

                EGL14.eglTerminate(display);
            }
        }
    }
}

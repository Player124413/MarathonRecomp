/*
 * vpad_bridge.c - writer side of the Marathon Recompiled virtual pad.
 *
 * The launcher owns a small shared memory file that the game maps read/write
 * (see MarathonRecomp/hid/virtual_pad.cpp). Touch controls and physical gamepads
 * are merged in Java into a single Xbox 360 pad state, then pushed here.
 *
 * A seqlock keeps the reader from ever seeing a half-written frame: the writer
 * makes 'seq' odd, writes, then makes it even again.
 */

#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <stdatomic.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include "virtual_pad_shm.h"   /* pulled straight from MarathonRecomp/hid via CMake include path */
#include "log.h"

static MRVirtualPadShm *g_shm = NULL;
static int g_fd = -1;

#define JNI_FN(name) Java_com_marathonrecomp_launcher_NativeBridge_##name

JNIEXPORT jboolean JNICALL
JNI_FN(vpadOpen)(JNIEnv *env, jclass clazz, jstring jpath)
{
    (void) clazz;

    if (g_shm != NULL)
        return JNI_TRUE;

    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);

    if (path == NULL)
        return JNI_FALSE;

    jboolean ok = JNI_FALSE;

    g_fd = open(path, O_RDWR | O_CREAT, 0600);

    if (g_fd < 0) {
        LOGE("vpad: open(%s) failed: %s", path, strerror(errno));
        goto done;
    }

    if (ftruncate(g_fd, (off_t) sizeof(MRVirtualPadShm)) != 0) {
        LOGE("vpad: ftruncate failed: %s", strerror(errno));
        close(g_fd);
        g_fd = -1;
        goto done;
    }

    void *map = mmap(NULL, sizeof(MRVirtualPadShm), PROT_READ | PROT_WRITE, MAP_SHARED, g_fd, 0);

    if (map == MAP_FAILED) {
        LOGE("vpad: mmap failed: %s", strerror(errno));
        close(g_fd);
        g_fd = -1;
        goto done;
    }

    g_shm = (MRVirtualPadShm *) map;

    memset(g_shm, 0, sizeof(MRVirtualPadShm));
    g_shm->magic = MR_VPAD_MAGIC;
    g_shm->version = MR_VPAD_VERSION;
    g_shm->size = (uint32_t) sizeof(MRVirtualPadShm);
    g_shm->padCount = MR_VPAD_MAX_PADS;
    g_shm->writerAlive = 1;

    atomic_thread_fence(memory_order_release);

    LOGI("vpad: attached to %s", path);
    ok = JNI_TRUE;

done:
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return ok;
}

JNIEXPORT void JNICALL
JNI_FN(vpadClose)(JNIEnv *env, jclass clazz)
{
    (void) env;
    (void) clazz;

    if (g_shm != NULL) {
        g_shm->writerAlive = 0;
        atomic_thread_fence(memory_order_release);
        munmap(g_shm, sizeof(MRVirtualPadShm));
        g_shm = NULL;
    }

    if (g_fd >= 0) {
        close(g_fd);
        g_fd = -1;
    }
}

JNIEXPORT jboolean JNICALL
JNI_FN(vpadIsOpen)(JNIEnv *env, jclass clazz)
{
    (void) env;
    (void) clazz;
    return g_shm != NULL ? JNI_TRUE : JNI_FALSE;
}

/*
 * Push one full pad frame. 'source' is MR_VPAD_SOURCE_TOUCH or MR_VPAD_SOURCE_GAMEPAD and is
 * only informational for the game; what matters is that a single writer owns the slot.
 */
JNIEXPORT void JNICALL
JNI_FN(vpadPush)(JNIEnv *env, jclass clazz, jint slot, jboolean connected, jint source,
                 jint buttons, jint leftTrigger, jint rightTrigger,
                 jint lx, jint ly, jint rx, jint ry)
{
    (void) env;
    (void) clazz;

    if (g_shm == NULL || slot < 0 || slot >= (jint) MR_VPAD_MAX_PADS)
        return;

    MRVirtualPadState *pad = &g_shm->pads[slot];

    /* seqlock: odd => update in progress. */
    g_shm->seq++;
    atomic_thread_fence(memory_order_release);

    pad->connected = connected ? 1u : 0u;
    pad->source = (uint32_t) source;
    pad->buttons = (uint32_t) (buttons & 0xFFFF);
    pad->leftTrigger = (uint32_t) (leftTrigger < 0 ? 0 : (leftTrigger > 255 ? 255 : leftTrigger));
    pad->rightTrigger = (uint32_t) (rightTrigger < 0 ? 0 : (rightTrigger > 255 ? 255 : rightTrigger));
    pad->thumbLX = (int32_t) lx;
    pad->thumbLY = (int32_t) ly;
    pad->thumbRX = (int32_t) rx;
    pad->thumbRY = (int32_t) ry;
    pad->updateCounter++;

    atomic_thread_fence(memory_order_release);
    g_shm->seq++;
}

/*
 * Rumble read-back, packed so Java needs a single call:
 *   bits 63..48 = rumble counter (wraps), 31..16 = left motor, 15..0 = right motor.
 * Java compares the counter against the last one it saw to detect a new rumble request.
 */
JNIEXPORT jlong JNICALL
JNI_FN(vpadPollRumble)(JNIEnv *env, jclass clazz, jint slot)
{
    (void) env;
    (void) clazz;

    if (g_shm == NULL || slot < 0 || slot >= (jint) MR_VPAD_MAX_PADS)
        return 0;

    MRVirtualPadState *pad = &g_shm->pads[slot];

    atomic_thread_fence(memory_order_acquire);

    jlong counter = (jlong) (pad->rumbleCounter & 0xFFFFu);
    jlong left = (jlong) (pad->rumbleLeft & 0xFFFFu);
    jlong right = (jlong) (pad->rumbleRight & 0xFFFFu);

    return (counter << 48) | (left << 16) | right;
}

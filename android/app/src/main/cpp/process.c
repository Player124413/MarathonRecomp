/*
 * process.c - start the emulator with a controlled environment.
 *
 * Java's ProcessBuilder cannot give a child a completely custom environment on Android
 * (it inherits the app's, and Android's is full of things box64/FEX trip over), and it
 * cannot easily redirect both streams into one growing log file. posix_spawn does both
 * in a handful of lines.
 */

#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <spawn.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

#include "log.h"

extern char **environ;

#define JNI_FN(name) Java_com_marathonrecomp_launcher_NativeBridge_##name

static char **build_array(JNIEnv *env, jobjectArray array)
{
    jsize count = (*env)->GetArrayLength(env, array);
    char **result = calloc((size_t) count + 1, sizeof(char *));

    if (result == NULL)
        return NULL;

    for (jsize i = 0; i < count; i++) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, array, i);

        if (item == NULL) {
            result[i] = strdup("");
            continue;
        }

        const char *chars = (*env)->GetStringUTFChars(env, item, NULL);
        result[i] = strdup(chars != NULL ? chars : "");

        if (chars != NULL)
            (*env)->ReleaseStringUTFChars(env, item, chars);

        (*env)->DeleteLocalRef(env, item);
    }

    return result;
}

static void free_array(char **array)
{
    if (array == NULL)
        return;

    for (char **p = array; *p != NULL; p++)
        free(*p);

    free(array);
}

JNIEXPORT jint JNICALL
JNI_FN(spawn)(JNIEnv *env, jclass clazz, jobjectArray jargv, jobjectArray jenvp,
              jstring jcwd, jstring jlog)
{
    (void) clazz;

    char **argv = build_array(env, jargv);
    char **envp = build_array(env, jenvp);

    if (argv == NULL || envp == NULL || argv[0] == NULL) {
        free_array(argv);
        free_array(envp);
        return -1;
    }

    const char *cwd = jcwd != NULL ? (*env)->GetStringUTFChars(env, jcwd, NULL) : NULL;
    const char *logPath = jlog != NULL ? (*env)->GetStringUTFChars(env, jlog, NULL) : NULL;

    pid_t pid = -1;
    posix_spawn_file_actions_t actions;
    int haveActions = 0;

    if (posix_spawn_file_actions_init(&actions) == 0) {
        haveActions = 1;

        if (cwd != NULL) {
            /* addchdir_np arrived in API 34; fall back to chdir in the parent below. */
#if defined(__ANDROID_API__) && __ANDROID_API__ >= 34
            posix_spawn_file_actions_addchdir_np(&actions, cwd);
#endif
        }

        if (logPath != NULL) {
            posix_spawn_file_actions_addopen(&actions, STDOUT_FILENO, logPath,
                                             O_WRONLY | O_CREAT | O_APPEND, 0600);
            posix_spawn_file_actions_adddup2(&actions, STDOUT_FILENO, STDERR_FILENO);
        }
    }

    char *savedCwd = NULL;

#if !defined(__ANDROID_API__) || __ANDROID_API__ < 34
    /* Without addchdir_np, chdir here and restore right after the spawn. */
    if (cwd != NULL) {
        savedCwd = getcwd(NULL, 0);

        if (chdir(cwd) != 0)
            LOGW("spawn: chdir(%s) failed: %s", cwd, strerror(errno));
    }
#endif

    int rc = posix_spawn(&pid, argv[0], haveActions ? &actions : NULL, NULL, argv, envp);

    if (savedCwd != NULL) {
        if (chdir(savedCwd) != 0)
            LOGW("spawn: cannot restore cwd: %s", strerror(errno));

        free(savedCwd);
    }

    if (haveActions)
        posix_spawn_file_actions_destroy(&actions);

    if (rc != 0) {
        LOGE("spawn: %s failed: %s", argv[0], strerror(rc));
        pid = -1;
    } else {
        LOGI("spawn: started %s as pid %d", argv[0], (int) pid);
    }

    if (cwd != NULL)
        (*env)->ReleaseStringUTFChars(env, jcwd, cwd);

    if (logPath != NULL)
        (*env)->ReleaseStringUTFChars(env, jlog, logPath);

    free_array(argv);
    free_array(envp);

    return (jint) pid;
}

JNIEXPORT jint JNICALL
JNI_FN(pollExit)(JNIEnv *env, jclass clazz, jint pid)
{
    (void) env;
    (void) clazz;

    if (pid <= 0)
        return 0;

    int status = 0;
    pid_t result = waitpid((pid_t) pid, &status, WNOHANG);

    if (result == 0)
        return INT32_MIN;          /* still running */

    if (result < 0)
        return 0;                  /* already reaped or not ours */

    if (WIFEXITED(status))
        return WEXITSTATUS(status);

    if (WIFSIGNALED(status))
        return 128 + WTERMSIG(status);

    return 0;
}

JNIEXPORT void JNICALL
JNI_FN(terminate)(JNIEnv *env, jclass clazz, jint pid)
{
    (void) env;
    (void) clazz;

    if (pid <= 0)
        return;

    kill((pid_t) pid, SIGTERM);

    /* Give it half a second to shut down cleanly, then insist. */
    for (int i = 0; i < 50; i++) {
        int status = 0;

        if (waitpid((pid_t) pid, &status, WNOHANG) != 0)
            return;

        usleep(10 * 1000);
    }

    kill((pid_t) pid, SIGKILL);
    waitpid((pid_t) pid, NULL, 0);
}

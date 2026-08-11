#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstdio>

#include <atomic>
#include <string>
#include <vector>

namespace {
constexpr const char* kTag = "LocalAgentQemu";

/**
 * QEMU can only be initialised once in the lifetime of a process.
 *
 * `qemu_init` writes process-wide globals that `qemu_cleanup` does not undo — the first of them,
 * `qemu_init_exec_dir`, asserts `!exec_dir[0]` and aborts the whole process on a second call. So a
 * stop followed by a start inside one process is not a restart, it is a SIGABRT. Refusing here
 * turns that crash into an error the caller can report, and `RuntimeService` retires this process
 * once QEMU has exited so the next start gets a fresh one.
 */
constexpr const char* kAlreadyUsed =
    "This process has already run QEMU once and cannot run it again";

std::atomic<bool> running{false};
std::atomic<bool> ever_started{false};
void* qemu_handle = nullptr;
void* compat_handle = nullptr;
std::string private_storage_dir;

using QemuInit = void (*)(int, char**, char**);
using QemuMainLoop = void (*)();
using QemuCleanup = void (*)();
using QemuShutdown = void (*)(int);
using SetJni = void (*)(JNIEnv*, jobject, jclass, const char*, const char*);

struct LaunchRequest {
    std::vector<std::string> args;
};

/**
 * Put QEMU's own stderr into logcat.
 *
 * Without this, a rejected option is invisible: `qemu_init` prints the reason to stderr and calls
 * `exit(1)`, Android discards stderr, and the whole `:computer` process vanishes between one log
 * line and the next with nothing to say why. The symptom is a VM that never boots and a log that
 * ends mid-sentence — which is indistinguishable from a hang, and is the wrong thing to be
 * debugging when the actual message was one line long.
 *
 * stdout goes the same way. QEMU is not run with a serial console on stdio here, so anything
 * arriving on either stream is diagnostics meant for a person.
 */
constexpr const char* kOutputLogName = "/qemu-output.log";

void capture_qemu_output() {
    // A file, not a pipe into logcat. `qemu_init` prints the reason and calls `exit(1)`, which
    // takes the whole process down — including any thread that was going to forward the message.
    // The one line worth having is therefore exactly the line a pipe loses. The kernel keeps a
    // file whether or not anything is left alive to read it.
    const std::string path = private_storage_dir + kOutputLogName;
    const int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "could not capture QEMU output to %s", path.c_str());
        return;
    }
    dup2(fd, STDOUT_FILENO);
    dup2(fd, STDERR_FILENO);
    close(fd);
    // Unbuffered, for the same reason: anything still sitting in a stdio buffer at exit is gone.
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    __android_log_print(ANDROID_LOG_INFO, kTag, "QEMU output captured to %s", path.c_str());
}

void* run_qemu(void* opaque) {
    auto* request = static_cast<LaunchRequest*>(opaque);
    std::vector<char*> argv;
    argv.reserve(request->args.size() + 1);
    for (auto& argument : request->args) argv.push_back(argument.data());
    argv.push_back(nullptr);

    auto init = reinterpret_cast<QemuInit>(dlsym(qemu_handle, "qemu_init"));
    auto main_loop = reinterpret_cast<QemuMainLoop>(dlsym(qemu_handle, "qemu_main_loop"));
    auto cleanup = reinterpret_cast<QemuCleanup>(dlsym(qemu_handle, "qemu_cleanup"));
    if (!init || !main_loop || !cleanup) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "QEMU entry points missing: %s", dlerror());
        delete request;
        running.store(false);
        return nullptr;
    }

    __android_log_print(ANDROID_LOG_INFO, kTag, "Starting QEMU with %zu arguments", request->args.size());
    for (const auto& argument : request->args) {
        __android_log_print(ANDROID_LOG_DEBUG, kTag, "  arg: %s", argument.c_str());
    }
    // Before qemu_init, because qemu_init is what exits on a bad option.
    capture_qemu_output();
    init(static_cast<int>(request->args.size()), argv.data(), nullptr);
    __android_log_print(ANDROID_LOG_INFO, kTag, "Entering QEMU main loop");
    main_loop();
    __android_log_print(ANDROID_LOG_INFO, kTag, "Cleaning up QEMU");
    cleanup();
    delete request;
    running.store(false);
    return nullptr;
}

jstring message(JNIEnv* env, const char* value) {
    return value ? env->NewStringUTF(value) : nullptr;
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_localagent_runtime_qemu_NativeQemu_start(JNIEnv* env, jobject caller, jobjectArray arguments,
                                                   jstring private_storage) {
    if (running.exchange(true)) return message(env, "QEMU is already running");
    // Only a launch that actually reached `qemu_init` consumes this process. Failing to load the
    // library or spawn the thread leaves QEMU untouched, and must stay retryable.
    if (ever_started.load()) {
        running.store(false);
        __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", kAlreadyUsed);
        return message(env, kAlreadyUsed);
    }

    qemu_handle = dlopen("libqemu-system-aarch64.so", RTLD_NOW | RTLD_GLOBAL);
    if (!qemu_handle) {
        running.store(false);
        return message(env, dlerror());
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "Loaded QEMU system library");

    compat_handle = dlopen("libcompat-limbo.so", RTLD_NOW | RTLD_GLOBAL);
    auto set_jni = reinterpret_cast<SetJni>(compat_handle ? dlsym(compat_handle, "set_jni") : nullptr);
    if (!set_jni) {
        const char* detail = dlerror();
        dlclose(qemu_handle);
        qemu_handle = nullptr;
        running.store(false);
        return message(env, detail ? detail : "Could not initialize QEMU filesystem compatibility");
    }
    const char* storage_utf8 = env->GetStringUTFChars(private_storage, nullptr);
    private_storage_dir.assign(storage_utf8);
    env->ReleaseStringUTFChars(private_storage, storage_utf8);
    jclass caller_class = env->GetObjectClass(caller);
    set_jni(env, caller, caller_class, private_storage_dir.c_str(), private_storage_dir.c_str());
    env->DeleteLocalRef(caller_class);

    auto* request = new LaunchRequest();
    const auto count = env->GetArrayLength(arguments);
    request->args.reserve(count);
    for (jsize index = 0; index < count; ++index) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(arguments, index));
        const char* utf8 = env->GetStringUTFChars(value, nullptr);
        request->args.emplace_back(utf8);
        env->ReleaseStringUTFChars(value, utf8);
        env->DeleteLocalRef(value);
    }

    pthread_t thread;
    const int result = pthread_create(&thread, nullptr, run_qemu, request);
    if (result != 0) {
        delete request;
        dlclose(qemu_handle);
        qemu_handle = nullptr;
        running.store(false);
        return message(env, "Could not create QEMU thread");
    }
    pthread_detach(thread);
    ever_started.store(true);
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_localagent_runtime_qemu_NativeQemu_stop(JNIEnv* env, jobject) {
    if (!running.load() || !qemu_handle) return message(env, "QEMU is not running");
    auto shutdown = reinterpret_cast<QemuShutdown>(dlsym(qemu_handle, "qemu_system_shutdown_request"));
    if (!shutdown) return message(env, dlerror());
    shutdown(3);  // SHUTDOWN_CAUSE_HOST_SIGNAL
    return nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_localagent_runtime_qemu_NativeQemu_isRunning(JNIEnv*, jobject) {
    return running.load();
}

/** Whether QEMU has been initialised in this process, running or since exited. */
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_localagent_runtime_qemu_NativeQemu_hasRun(JNIEnv*, jobject) {
    return ever_started.load();
}

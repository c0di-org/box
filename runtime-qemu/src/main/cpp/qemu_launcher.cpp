#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>

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

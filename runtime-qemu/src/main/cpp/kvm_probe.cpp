#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdio>
#include <string>

/**
 * Whether this device could run the guest on hardware rather than emulating it.
 *
 * A diagnostic, and deliberately only that: nothing here selects an accelerator, and the answer
 * never reaches [QemuCommand]. That separation is not tidiness. `QemuCommand.machine()` fingerprints
 * the launch arguments so a saved guest is only restored into the machine it left, so anything that
 * could vary the command line would invalidate every paused box on every device that answered
 * differently — including, on a device whose answer changed between one boot and the next, silently.
 * A probe that only writes to the log cannot do that.
 *
 * Three questions rather than one, because they fail independently and only the last one is proof:
 * a node can exist and refuse to open, and it can open and still refuse to make a VM. SELinux
 * decides the second, and it is a per-domain rule an app cannot inspect from inside itself — the
 * only honest way to ask is to try it and keep the errno.
 */
namespace {
constexpr const char* kTag = "LocalAgentQemu";

// _IO(KVMIO, n) with KVMIO = 0xAE, identical on every architecture Linux supports.
constexpr unsigned long kKvmGetApiVersion = 0xAE00;
constexpr unsigned long kKvmCreateVm = 0xAE01;

/** Whether a path exists at all, kept apart from whether it opens: ENOENT and EACCES mean very
 *  different things here and answering "no" to both would hide which one this device is. */
bool present(const char* path) {
    struct stat info {};
    return stat(path, &info) == 0;
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_localagent_runtime_qemu_NativeQemu_probeHypervisor(JNIEnv* env, jobject) {
    std::string report;
    char part[256];

    const bool kvm_present = present("/dev/kvm");
    snprintf(part, sizeof(part), "/dev/kvm exists=%s", kvm_present ? "yes" : "no");
    report += part;

    const int fd = open("/dev/kvm", O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        snprintf(part, sizeof(part), ", open=failed errno=%d (%s)", errno, strerror(errno));
        report += part;
    } else {
        const int api = ioctl(fd, kKvmGetApiVersion, 0);
        snprintf(part, sizeof(part), ", open=ok api=%d", api);
        report += part;

        // Opening the node is not the same as being allowed to use it, and this is the difference
        // QEMU would hit: kvm_init opens, checks the version, then creates a VM, and a policy that
        // permits the first two and denies the third presents as QEMU exiting during startup.
        const int vm = ioctl(fd, kKvmCreateVm, 0);
        if (vm < 0) {
            snprintf(part, sizeof(part), ", createVm=failed errno=%d (%s)", errno, strerror(errno));
        } else {
            snprintf(part, sizeof(part), ", createVm=ok");
            close(vm);
        }
        report += part;
        close(fd);
    }

    // Not idle curiosity: on a Snapdragon the hypervisor may be Qualcomm's Gunyah rather than
    // pKVM, in which case /dev/kvm is absent by design and its absence says nothing about whether
    // the SoC virtualizes. Recording which node is here is what distinguishes "no hardware
    // virtualization" from "hardware virtualization this QEMU has no backend for".
    snprintf(part, sizeof(part), ", /dev/gunyah exists=%s", present("/dev/gunyah") ? "yes" : "no");
    report += part;

    __android_log_print(ANDROID_LOG_INFO, kTag, "Hypervisor probe: %s", report.c_str());
    return env->NewStringUTF(report.c_str());
}

package dev.localagent.runtime.qemu

import java.io.IOException
import java.net.SocketTimeoutException

/**
 * A LocalSocket read that hits its setSoTimeout deadline surfaces the raw EAGAIN strerror text in
 * a plain IOException instead of SocketTimeoutException, so polling reads must accept both forms.
 */
internal fun IOException.isSocketReadTimeout(): Boolean =
    this is SocketTimeoutException ||
        message?.contains("Try again", ignoreCase = true) == true ||
        message?.contains("EAGAIN", ignoreCase = true) == true

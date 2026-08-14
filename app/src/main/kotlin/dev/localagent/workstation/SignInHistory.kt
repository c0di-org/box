package dev.localagent.workstation

import android.content.Context

/**
 * Whether this install has ever held a Claude credential.
 *
 * The guest is the authority and cannot be asked until it has booted, which is three minutes after
 * Box most needs the answer. Two screens are wrong without it: the closed box, which should say
 * signing in is coming before someone commits to the wait, and the arrival, which gets exactly one
 * full-window moment per install and must not spend it drawing two doors then swapping one out.
 *
 * So this is a *hint*, written the first time the guest says yes and never trusted over it. A fresh
 * install has no credential — uninstalling takes the workspace disk it lives on — which makes the
 * hint reliable in the one case that matters. Everywhere else it is wrong for seconds, in the
 * direction of offering a sign-in to someone already signed in, and [GuestAuth] corrects it.
 */
class SignInHistory(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun hasSignedIn(): Boolean = preferences.getBoolean(KEY, false)

    fun remember(signedIn: Boolean) {
        preferences.edit().putBoolean(KEY, signedIn).apply()
    }

    private companion object {
        /** Shared with [OpeningHistory] and the notification-permission flag in `MainActivity`. */
        const val PREFERENCES = "box_product"
        const val KEY = "has_signed_in"
    }
}

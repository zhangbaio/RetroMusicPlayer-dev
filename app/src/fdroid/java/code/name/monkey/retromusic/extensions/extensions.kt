@file:Suppress("UNUSED_PARAMETER", "unused")

package code.name.monkey.retromusic.extensions

import android.content.Context
import android.view.Menu
import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.mediarouter.app.MediaRouteButton

fun Context.setUpMediaRouteButton(menu: Menu) {}

fun MediaRouteButton.setUpCastButton(context: Context) {
    // Cast not available in fdroid flavor
}

fun FragmentActivity.installLanguageAndRecreate(code: String, onInstallComplete: () -> Unit) {
    onInstallComplete()
}

fun Context.goToProVersion() {}

fun Context.installSplitCompat() {}
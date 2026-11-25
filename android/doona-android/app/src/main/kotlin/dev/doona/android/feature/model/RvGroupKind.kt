package dev.doona.android.feature.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.doona.android.R

/** How a group picks its node: the user picks it (a selector), or the fastest by the last test does (url-test). */
internal enum class RvGroupKind(@StringRes val label: Int, @DrawableRes val glyph: Int) {
    Select(R.string.rv_policy_kind_select, R.drawable.rv_sym_touch_app),
    Auto(R.string.rv_policy_kind_auto, R.drawable.rv_sym_bolt),

    /** honk's Score, doona's `policy: score`: named and drawn as the templates name and draw it. */
    Score(R.string.policy_kind_score, R.drawable.rv_sym_bolt),
}

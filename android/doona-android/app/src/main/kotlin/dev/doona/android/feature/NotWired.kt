package dev.doona.android.feature

/**
 * The click of an action the sketch shows and no screen backs yet (search, menus, edit). It is named so that each stays
 * greppable and visible at its call site; the screen that implements one replaces it with its handler.
 */
internal val NOT_WIRED: () -> Unit = {}

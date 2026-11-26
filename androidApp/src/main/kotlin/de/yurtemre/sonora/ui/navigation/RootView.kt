package de.yurtemre.sonora.ui.navigation

import kotlinx.serialization.Serializable

/** The two views of the library root: the Home dashboard above, the tabbed Library below. */
@Serializable
enum class RootView { Home, Library }

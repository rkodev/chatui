package dev.chatui.shell

/**
 * Top-level shell destinations.
 *
 * Every destination is a placeholder in M0. Feature behavior is added in later
 * milestones without changing the route names.
 */
enum class ShellDestination(
    val route: String,
    val label: String,
    val shortLabel: String,
    val title: String,
) {
    Onboarding(
        route = "onboarding",
        label = "Onboarding",
        shortLabel = "OB",
        title = "Onboarding",
    ),
    Chat(
        route = "chat",
        label = "Chat",
        shortLabel = "CH",
        title = "Chat",
    ),
    Conversations(
        route = "conversations",
        label = "Conversations",
        shortLabel = "CV",
        title = "Conversations",
    ),
    Providers(
        route = "providers",
        label = "Providers",
        shortLabel = "PR",
        title = "Providers",
    ),
    Settings(
        route = "settings",
        label = "Settings",
        shortLabel = "ST",
        title = "Settings",
    ),
    Backup(
        route = "backup",
        label = "Backup",
        shortLabel = "BK",
        title = "Backup",
    ),
    ;

    /**
     * True when [route] belongs to this destination.
     *
     * The chat destination has a second route with a conversation argument, so
     * an exact match is not enough for the bottom-navigation selection.
     */
    fun matches(route: String?): Boolean =
        route == this.route || (this == Chat && route?.startsWith("chat/") == true)
}

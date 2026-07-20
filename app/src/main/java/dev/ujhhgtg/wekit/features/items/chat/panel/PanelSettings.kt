package dev.ujhhgtg.wekit.features.items.chat.panel

import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption

object PanelSettings {
    const val DEFAULT_FUNBOX_API_CLIENT_WXID = "wxid_1234567890abcd"

    var stickerLastDestination by prefOption("sticker_panel_last_destination", "RECENT")
    var voiceLastDestination by prefOption("voice_panel_last_destination", "RECENT")
    var stickerColumnCount by prefOption("sticker_panel_column_count", 5)
    var stickerMaxHistory by prefOption("sticker_panel_max_history", 50L)
    var voiceMaxHistory by prefOption("voice_panel_max_history", 50L)
    var stickerRecentSortMode by prefOption("sticker_panel_recent_sort_mode", 0)
    var voiceRecentSortMode by prefOption("voice_panel_recent_sort_mode", 0)
    var stickerSortType by prefOption("sticker_panel_sort_type", 0)
    var voiceSortType by prefOption("voice_panel_sort_type", 0)
    var stickerAutoClose by prefOption("sticker_panel_auto_close", true)
    var voiceAutoClose by prefOption("voice_panel_auto_close", true)
    var onlineStickerPacksUseList by prefOption("sticker_panel_online_packs_use_list", false)
    var onlineStickerSortMode by prefOption("sticker_panel_online_sort_mode", 0)
    var selectedVoiceProvider by prefOption("voice_panel_selected_provider", "funbox_share")
    var selectedEdgeVoice by prefOption("voice_panel_edge_voice", "zh-CN-XiaoxiaoNeural")
    var funBoxApiClientWxId by prefOption(
        "funbox_api_client_wxid",
        DEFAULT_FUNBOX_API_CLIENT_WXID,
    )

    val effectiveFunBoxApiClientWxId: String
        get() = funBoxApiClientWxId.takeIf(::isValidFunBoxApiClientWxId)
            ?: DEFAULT_FUNBOX_API_CLIENT_WXID

    fun isValidFunBoxApiClientWxId(value: String): Boolean =
        FUNBOX_WXID_REGEX.matches(value.trim())

    fun randomFunBoxApiClientWxId(): String {
        val bytes = ByteArray(7)
        java.security.SecureRandom().nextBytes(bytes)
        return "wxid_" + bytes.joinToString("") { "%02x".format(it) }
    }

    private val FUNBOX_WXID_REGEX = Regex("[A-Za-z][A-Za-z0-9_-]{5,63}")
}

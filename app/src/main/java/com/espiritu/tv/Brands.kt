package com.espiritu.tv

/**
 * How a known app looks in the dock (the canvas tiles: brand colour + logo) and, for streaming services,
 * which shows its "Popular on …" hero cycles through.
 */
class Brand(
    val mark: Int,
    val bg: Int,
    /** Wordmarks are sized by width (62% of the tile), symbols by height (46%). */
    val wordmark: Boolean,
    /** Logo used next to "Popular on …" / "Continue watching" in the hero. */
    val heroMark: Int = mark,
    val heroMarkHeight: Int = 44,
)

/** A service whose popular shows can fill the screen. [shows] are TVmaze ids, in the order the hero shows them. */
class Provider(val name: String, val shows: List<Int>)

object Brands {
    private fun c(hex: Long) = hex.toInt()

    private val NETFLIX = Brand(R.drawable.brand_netflix, c(0xFF141414), false)
    private val YOUTUBE = Brand(R.drawable.brand_youtube, c(0xFFFFFFFF), false)
    private val APPLE_TV = Brand(R.drawable.brand_appletv, c(0xFF2C2C2E), true, heroMarkHeight = 30)
    private val SPOTIFY = Brand(R.drawable.brand_spotify, c(0xFF121212), false)
    private val HBO_MAX = Brand(R.drawable.brand_hbomax, c(0xFF002BE7), true, R.drawable.brand_hbomax_wide, 20)
    private val TWITCH = Brand(R.drawable.brand_twitch, c(0xFF9146FF), false)
    private val CRUNCHYROLL = Brand(R.drawable.brand_crunchyroll, c(0xFFFF5E00), false)
    private val PARAMOUNT = Brand(R.drawable.brand_paramountplus, c(0xFF0064FF), false)
    private val TUBI = Brand(R.drawable.brand_tubi, c(0xFF7408FF), true, heroMarkHeight = 26)
    private val PLAY_STORE = Brand(R.drawable.brand_googleplay, c(0xFFFFFFFF), false)
    private val PLEX = Brand(R.drawable.brand_plex, c(0xFF1F1F1F), true, heroMarkHeight = 26)
    private val YOUTUBE_TV = Brand(R.drawable.brand_youtubetv, c(0xFF212121), true, heroMarkHeight = 26)

    private val brands = mapOf(
        "com.google.android.youtube.tv" to YOUTUBE,
        "com.google.android.youtube" to YOUTUBE,
        "com.netflix.ninja" to NETFLIX,
        "com.netflix.mediaclient" to NETFLIX,
        "com.apple.atve.androidtv.appletv" to APPLE_TV,
        "com.apple.atve.amazon.appletv" to APPLE_TV,
        "com.spotify.tv.android" to SPOTIFY,
        "com.spotify.music" to SPOTIFY,
        "com.wbd.stream" to HBO_MAX,
        "com.hbo.hbonow" to HBO_MAX,
        "tv.twitch.android.app" to TWITCH,
        "tv.twitch.android.viewer" to TWITCH,
        "com.crunchyroll.crunchyroid" to CRUNCHYROLL,
        "com.cbs.ott" to PARAMOUNT,
        "com.cbs.app" to PARAMOUNT,
        "com.tubitv" to TUBI,
        "com.android.vending" to PLAY_STORE,
        "com.plexapp.android" to PLEX,
        "com.google.android.youtube.tvunplugged" to YOUTUBE_TV,
    )

    // Hand-picked current hits per service (TVmaze ids), nothing tagged Horror. Art and details are fetched live.
    private val NETFLIX_SHOWS = Provider("Netflix", listOf(53647, 43687, 42966, 77060))
    private val HBO_SHOWS = Provider("HBO Max", listOf(75632, 51394, 44778, 54914))
    private val DISNEY_SHOWS = Provider("Disney+", listOf(52341, 63234, 48108, 38963))
    private val HULU_SHOWS = Provider("Hulu", listOf(54198, 48830, 37336, 75030))
    private val PRIME_SHOWS = Provider("Prime Video", listOf(15299, 43031, 49041, 33352))
    private val APPLE_SHOWS = Provider("Apple TV", listOf(44933, 44458, 45039, 75605))
    private val PARAMOUNT_SHOWS = Provider("Paramount+", listOf(53777, 59344, 48090, 78665))
    private val PEACOCK_SHOWS = Provider("Peacock", listOf(526, 54128, 42289, 64977))
    private val CRUNCHYROLL_SHOWS = Provider("Crunchyroll", listOf(73023, 69956, 59698))

    private val providers = mapOf(
        "com.netflix.ninja" to NETFLIX_SHOWS,
        "com.netflix.mediaclient" to NETFLIX_SHOWS,
        "com.wbd.stream" to HBO_SHOWS,
        "com.hbo.hbonow" to HBO_SHOWS,
        "com.disney.disneyplus" to DISNEY_SHOWS,
        "com.hulu.livingroomplus" to HULU_SHOWS,
        "com.hulu.plus" to HULU_SHOWS,
        "com.amazon.amazonvideo.livingroom" to PRIME_SHOWS,
        "com.amazon.avod.thirdpartyclient" to PRIME_SHOWS,
        "com.apple.atve.androidtv.appletv" to APPLE_SHOWS,
        "com.apple.atve.amazon.appletv" to APPLE_SHOWS,
        "com.cbs.ott" to PARAMOUNT_SHOWS,
        "com.cbs.app" to PARAMOUNT_SHOWS,
        "com.peacocktv.peacockandroid" to PEACOCK_SHOWS,
        "com.crunchyroll.crunchyroid" to CRUNCHYROLL_SHOWS,
    )

    /** Dock order on first run (the canvas order), keeping only what's installed. */
    val PREFERRED = listOf(
        "com.google.android.youtube.tv",
        "com.netflix.ninja",
        "com.apple.atve.androidtv.appletv",
        "com.spotify.tv.android",
        "com.wbd.stream",
        "com.hbo.hbonow",
        "tv.twitch.android.app",
        "com.crunchyroll.crunchyroid",
        "com.cbs.ott",
        "com.tubitv",
        "com.disney.disneyplus",
        "com.hulu.livingroomplus",
        "com.amazon.amazonvideo.livingroom",
        "com.peacocktv.peacockandroid",
        "com.google.android.youtube.tvunplugged",
        "com.plexapp.android",
        "com.android.vending",
    )

    /** Whose shows fill the screen when no streaming app is installed yet. */
    const val DEFAULT_PROVIDER = "com.wbd.stream"

    // Official trailers on YouTube for the curated shows (TVmaze id → video id), checked to allow embedding.
    private val trailers = mapOf(
        53647 to "Qa5kFRxBkNw", // Wednesday
        42966 to "gpv7ayf_tyE", // Bridgerton
        77060 to "Xn2PsXD8m4A", // Nobody Wants This
        75632 to "ufR_08V38sQ", // The Pitt
        51394 to "TGLq7_MonZ4", // The White Lotus
        44778 to "Wg86eQkdudI", // House of the Dragon
        54914 to "pJcfoJCCJ50", // Hacks
        52341 to "cKOegEuCcfw", // Andor
        63234 to "7xALolZzhSM", // Daredevil: Born Again
        48108 to "YbBAb0FxDbU", // Percy Jackson and the Olympians
        38963 to "aOC8E8z_ifw", // The Mandalorian
        54198 to "WBlIUsFEnsw", // The Bear
        37336 to "yAN5uspO_hk", // Shōgun
        75030 to "tCObCpYScdE", // Paradise
        15299 to "tcrNsIaQkb4", // The Boys
        43031 to "GSycMV-_Csw", // Reacher
        49041 to "0kQ8i2FpRDk", // Fallout
        33352 to "uYnQDsaxHZU", // The Lord of the Rings: The Rings of Power
        44933 to "xEQP4VVuyrY", // Severance
        44458 to "3u7EIiohs6U", // Ted Lasso
        45039 to "O9ZJChzPn0U", // Slow Horses
        75605 to "EIQuE7JGXU8", // The Studio
        53777 to "7zxh49-bsIk", // Landman
        59344 to "PXd3tO5ITyw", // Tulsa King
        48090 to "nxuyzk5DUFc", // Star Trek: Strange New Worlds
        78665 to "84o1Q6fB20k", // Dexter: Resurrection
        54128 to "4x2NzusLAqk", // Poker Face
        42289 to "fpIehnHmjRY", // Love Island
        64977 to "qXJ2HKqFbE0", // The Day of the Jackal
        59698 to "6sosTNRw_uQ", // Spy×Family
    )

    fun trailer(tvmazeId: Int): String? = trailers[tvmazeId]

    fun of(pkg: String): Brand? = brands[pkg]
    fun provider(pkg: String): Provider? = providers[pkg]
}

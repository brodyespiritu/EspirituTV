package com.espiritu.tv

/**
 * How a known app looks on its card (the canvas cards: brand colour + logo) and, for streaming services,
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

/**
 * A service whose shows fill the screen. [shows] are TVmaze ids; [new] marks the brand-new series among them
 * (the hero says "New on …" for those, "Popular on …" for the rest).
 */
class Provider(val name: String, val shows: List<Int>, val new: Set<Int> = emptySet())

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

    // Each service's slideshow: mostly series that premiered June–October 2026 (from TVmaze's schedules), plus one
    // established hit; nothing tagged Horror. TVmaze ids; art and details are fetched live.
    private val NETFLIX_SHOWS = Provider("Netflix", listOf(81110, 79399, 82345, 77059, 53647), new = setOf(81110, 79399, 82345, 77059))
    private val HBO_SHOWS = Provider("HBO Max", listOf(44776, 83360, 86587, 93792, 75632), new = setOf(44776, 83360, 86587, 93792))
    private val DISNEY_SHOWS = Provider("Disney+", listOf(64950, 83159, 92003, 93304, 63234), new = setOf(64950, 83159, 92003, 93304))
    private val HULU_SHOWS = Provider("Hulu", listOf(87753, 68193, 92751, 63873, 54198), new = setOf(87753, 68193, 92751, 63873))
    private val PRIME_SHOWS = Provider("Prime Video", listOf(82707, 91854, 77011, 82911, 49041), new = setOf(82707, 91854, 77011, 82911))
    private val APPLE_SHOWS = Provider("Apple TV", listOf(81228, 78183, 68625, 44933), new = setOf(81228, 78183, 68625))
    private val PARAMOUNT_SHOWS = Provider("Paramount+", listOf(83073, 93997, 93506, 76188, 53777), new = setOf(83073, 93997, 93506, 76188))
    private val PEACOCK_SHOWS = Provider("Peacock", listOf(82297, 54128, 526, 64977), new = setOf(82297))
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

    /** The pinned cards on first run (the canvas order), keeping only what's installed. */
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

    // Title logos for a dark screen (TMDB, English, light or saturated colour), TVmaze id → TMDB image path.
    private val logos = mapOf<Int, String>(
        81110 to "mGVDe43J6OaopYxkDnSoBSYnw0v.png", // Harlan Coben's I Will Find You
        79399 to "jd9EWL7f9Mefmj4c3YGEPqVx4r7.png", // East of Eden
        82345 to "7x7xpCPNZOp0MrkzKhXPVAB8hBW.png", // Little House on the Prairie
        77059 to "YnYh23AnbxT2BFiaDxEnwVDQ8U.png", // The Hawk
        44776 to "kANHXGvFKW13UEkZbYwl9nIJeEl.png", // Lanterns
        83360 to "bLnszAIKG4YC6KBgS17aczxryDU.png", // Stuart Fails to Save the Universe
        86587 to "w80ohpg5izwi0wCBW6060CH7zwP.png", // War
        93792 to "fLK6t6AUI35F5PaZJqKETezdL4G.png", // Youth
        64950 to "mQwaoWvWJGiuVY6CT1V6AiuSIqi.png", // VisionQuest
        83159 to "aazmvkdMZs4nuYAEl0ukTPrto03.png", // Coven Academy
        92003 to "mAskBkgq1if7eoBGW9IuNPl8Npq.png", // Adventure Time: Side Quests
        93304 to "hCEJbnbhSliWgnq2USJhnOhdT2N.png", // Locker Diaries: Descendants
        87753 to "t70Qj9WkIGVVNA0qjgwbK4elsZp.png", // Furious
        68193 to "xNTiBsF3FmLvCeHBMYF97p6E46J.png", // The Shards
        92751 to "43wNg4tLfZFtiAYkzw7NoTa9Odp.png", // The Drop: A Snowfall Saga
        63873 to "xqc5IeHc6TzT1uvNZ1CCNIiTeNC.png", // Phony
        82707 to "lI6zO2lw8pteZnIzm1DuucVGutG.png", // Neagley
        91854 to "1n1alDnlTXQ4rMU7ClncmPeJcoM.png", // Ride or Die
        77011 to "eKIz4O3OGCuyzivg2HHLwJUdSTx.png", // Elle
        82911 to "oI9xCt2yBSRhJRWpDb5v3JB3uZ0.png", // Sterling Point
        81228 to "ofBcrmZTjCSh2bL9Cpy6AgJQX2J.png", // Lucky
        78183 to "9wkVRKD4wxTflK0cRGcVnt0u1pD.png", // Last Seen
        68625 to "34yURPkrtNaP3NYQJDI2SZOVXud.png", // Brothers
        83073 to "fMRLNeqqvJEFLjQcXr0buqnMdQP.png", // Avatar: Seven Havens
        93997 to "m2XKQx11sJAgVcmlLIF0MwTFhDx.png", // Route 187
        93506 to "zZvkrkfeAhgtBJCSDSKbTAsAx4V.png", // Dalliance
        76188 to "1HwmReqhvTkTMQRcPJq7TLtb4kW.png", // Golden Axe
        82297 to "twY23S1PgU9S65Wh7Xeqc2h0Zvv.png", // The Five Star Weekend
        53647 to "2ns0b8dB0wV6Aebe0wI0LBzkoNN.png", // Wednesday
        43687 to "lifhjTPgcScJaEA6SpWJUKNJ1kt.png", // Squid Game
        42966 to "hCktfeE5wbaakqp8y98Ui5ZAats.png", // Bridgerton
        77060 to "yoIDGAhWvPFxPknG3pgQSdvnVAr.png", // Nobody Wants This
        75632 to "zqsePGDhPnk5KpSjlZFpmZyy8h6.png", // The Pitt
        51394 to "2SwIOcXZ4you4EjPCAw7IucCsgX.png", // The White Lotus
        44778 to "t8PZ4J0Zjox7wd3gIy8IFSXSNUG.png", // House of the Dragon
        54914 to "Lp4TuZ5nm0plfWPI3jRB7ERCOk.png", // Hacks
        52341 to "2vyPbpZVYkYAzU8QqyGi6EmawB5.png", // Andor
        63234 to "lbEFxoej0HfCIorpkKNn1ogJbvU.png", // Daredevil: Born Again
        48108 to "iYxjFCbqSJyDDmAytShRw2n5ZgF.png", // Percy Jackson and the Olympians
        38963 to "umNJYUzgeOilCwFzp341ysKBgL8.png", // The Mandalorian
        54198 to "8ZTz2Wj0lE5WoSOqRG8GarhhnY4.png", // The Bear
        48830 to "yEa3q1MtRbd27Ui64LVaH1f4FSQ.png", // Only Murders in the Building
        37336 to "zjKGNDF2Kr6RQvONfiM0t2tm92T.png", // Shōgun
        75030 to "ybiNhb3KAsgOd0ou272yFEdio86.png", // Paradise
        15299 to "xawDgfkBQaocXR9CljBMjkYpXNK.png", // The Boys
        43031 to "2YkCVT6opPxKh2ogEqxVrCiFgsr.png", // Reacher
        49041 to "8lCvG53e4wjPDAkt6KUFbMDYKxX.png", // Fallout
        33352 to "2uDS3VQIDlNnwq3Mp8BsC7igftU.png", // The Lord of the Rings: The Rings of Power
        44933 to "yyS1tALk7t3YdTNMOvR5gsnXINA.png", // Severance
        44458 to "iNudegFbTBAGBB529k7wGVivqE4.png", // Ted Lasso
        45039 to "peE0Gab2MWjXPlAYhakxjHciNPr.png", // Slow Horses
        75605 to "uE9J0hEuMZ5lnwVjKWyG6XVpFso.png", // The Studio
        53777 to "13lJnfO3oExZ2ECzAR6WDyShzlV.png", // Landman
        59344 to "7g9jqV0n3cgd5evDZoqo5Hc5sHs.png", // Tulsa King
        48090 to "oTwnbowXtY08Ck9XnmaYoG7V5tI.png", // Star Trek: Strange New Worlds
        78665 to "8vN1JzNMVIVuyhEyGkKwr0NhMPh.png", // Dexter: Resurrection
        526 to "17TS3YFxsAFbhyt3pp2TmAa6LR0.png", // The Office
        54128 to "uIfy2R8dfH7fTf7F2AyNigcn9C6.png", // Poker Face
        42289 to "uCJ7DNe0scpr3ee9YFUKOOyFSGN.png", // Love Island
        64977 to "zuFnfprVt0r5qE2IBWN8ewhqhsb.png", // The Day of the Jackal
        59698 to "rEWhjQE3KVU6neMkCJ45tz6yh59.png", // Spy×Family
    )

    /** The show's title logo, or null to set its name in type. */
    fun logo(tvmazeId: Int): String? = logos[tvmazeId]?.let { "https://image.tmdb.org/t/p/w500/$it" }

    // Backdrops with no text on them (TMDB's "no language" images, checked by eye), TVmaze id → TMDB image path.
    private val backdrops = mapOf(
        81110 to "yOna5ZGXrvWO2HdOCKcahx2sCDN.jpg", // Harlan Coben's I Will Find You
        79399 to "70RZX7kNjA0Qjn6HigB1oMvFJ8R.jpg", // East of Eden
        82345 to "fBieUo3SdItUrXZE16YxbpjwXIe.jpg", // Little House on the Prairie
        77059 to "fBVNJL85Q60njpKPD6YaLi6tEsX.jpg", // The Hawk
        44776 to "6gqezQJ2mkm4jreWwLyOZy2Vf6i.jpg", // Lanterns
        83360 to "nW7oUFiJrIWwll4mZ8tYays7XRm.jpg", // Stuart Fails to Save the Universe
        86587 to "muPKymUvvBqjLCyGSacxKgjrhxN.jpg", // War
        93792 to "7pwybpJ2VSqqWgxZHTYqzRz01qH.jpg", // Youth
        64950 to "uoXXbN2PrOxH6UKyP7kYwmGpAAP.jpg", // VisionQuest
        83159 to "mGFtH5pUXDd4hSXUfl8HnHlRWJV.jpg", // Coven Academy
        92003 to "l75H7ymGd96jPWdIjvOXW0qvEM4.jpg", // Adventure Time: Side Quests
        93304 to "cXrXcd6sf8SFyGkrjDA4PnwyTay.jpg", // Locker Diaries: Descendants
        87753 to "iAOPgUlh7inOebFrUFHAjnFRCGS.jpg", // Furious
        68193 to "AhzF2bkYYybcCT4PnqpoagRGBWJ.jpg", // The Shards
        92751 to "ycLQYTgWfBq53fIkws0qIQOp62g.jpg", // The Drop: A Snowfall Saga
        63873 to "kVf3qP9DHfiW7H9ZzFmE1XrNpJ4.jpg", // Phony
        82707 to "uYOYLFQ4q7asuhdiKXCqGaeAQUH.jpg", // Neagley
        91854 to "jwCXxvbLno5GrGDA9KvQ22uLziM.jpg", // Ride or Die
        77011 to "truh4rtTUdRVYGeT0n17dxvdv41.jpg", // Elle
        82911 to "nOWU1NSYQEOXRXcdfZbtJshwRGK.jpg", // Sterling Point
        81228 to "mKrhRPB7rMvy0bIU1l1NYhgh1eI.jpg", // Lucky
        78183 to "kgaQnDdDaPqvGN7i8LLz1aWJdjP.jpg", // Last Seen
        68625 to "aLE7R8QZPpTiWZS48PS22i2OWbl.jpg", // Brothers
        83073 to "t2KFGH07Al4XuIZLHRlF1Hj9IJB.jpg", // Avatar: Seven Havens
        93997 to "Agx8RmlBBDJrlH9MyUOd5JYYZPS.jpg", // Route 187
        93506 to "8Ye9jv4Ibo5xK6GgWihsec8M94A.jpg", // Dalliance
        76188 to "zOcEo3oQf9KDPPxZueNhReN6TBr.jpg", // Golden Axe
        82297 to "lq2aJdPlyOo11aBwojmLvGjlJS1.jpg", // The Five Star Weekend
        53647 to "iHSwvRVsRyxpX7FE7GbviaDvgGZ.jpg", // Wednesday
        43687 to "5aE1kxWg6RhgQxJTXTxifv4uq7P.jpg", // Squid Game
        42966 to "6umsRLI7t0ydFwCl0JNEIO0q2LH.jpg", // Bridgerton
        77060 to "q8BwtVM3t0SOtB1nZsXpOKZp8IM.jpg", // Nobody Wants This
        75632 to "z3BkMbCy5ajZPMyKEUwsPHuz2cV.jpg", // The Pitt
        51394 to "qVBIAcZkK5j6WRq7JehJcOMbdgb.jpg", // The White Lotus
        44778 to "577eXC8wFQT0eUrJcgznSiFPRmk.jpg", // House of the Dragon
        54914 to "bbAR4qKxjnjyKAt4YMrL725Mtfw.jpg", // Hacks
        52341 to "quCeAmVQHfsdcYkicbxZWVauCVb.jpg", // Andor
        63234 to "qrTAc0ZtQ859Qu5O8cixJzNJpQs.jpg", // Daredevil: Born Again
        48108 to "sAxx25ijwYQ8xsT56wu2IzvIRss.jpg", // Percy Jackson and the Olympians
        38963 to "9zcbqSxdsRMZWHYtyCd1nXPr2xq.jpg", // The Mandalorian
        54198 to "aJtG4txtmiRHwAAqENQHZvBs6kY.jpg", // The Bear
        48830 to "WCnEPf4ZNPjszndmrFlDxZ5Uvd.jpg", // Only Murders in the Building
        37336 to "bwSmgmd90hCWwqOKQYTEraeOZhJ.jpg", // Shōgun
        15299 to "bq28ajZaoMyzEIm6REelqyqtEDZ.jpg", // The Boys
        43031 to "pF0qkRsrHkdYadPWY9AMeFZfcwk.jpg", // Reacher
        49041 to "coaPCIqQBPUZsOnJcWZxhaORcDT.jpg", // Fallout
        33352 to "o2wg8QiSCQrhj91tBfxunE3O5Ba.jpg", // The Lord of the Rings: The Rings of Power
        44933 to "ixgFmf1X59PUZam2qbAfskx2gQr.jpg", // Severance
        44458 to "nE94ejEbzNCU48bW1oju0dqBONz.jpg", // Ted Lasso
        75030 to "5QsLvWh8J1mXl1W05wNJknMmhzR.jpg", // Paradise
        45039 to "gPQppUnLx9vqPDLz3WwZ2HJsNbt.jpg", // Slow Horses
        75605 to "qqqo3P0txcP5ES3ZQ7YR4SmUwam.jpg", // The Studio
        53777 to "mh2UczqEXJJVgqohbyZbHTuxwhL.jpg", // Landman
        59344 to "mNHRGO1gFpR2CYZdANe72kcKq7G.jpg", // Tulsa King
        48090 to "fZJs6xgU9dqIg9phIXjZAZOInzy.jpg", // Star Trek: Strange New Worlds
        78665 to "vcpj6rRDS93ImHCLPY8bwBuFGjR.jpg", // Dexter: Resurrection
        526 to "9vMzIrMIncPmtalcxh1MOrgcE6C.jpg", // The Office
        54128 to "ccOzCmrglAjRGtqv5ClTaBEsWt8.jpg", // Poker Face
        42289 to "m0TiLZ79RR19Zz0AZruQSBeH39x.jpg", // Love Island
        64977 to "enVrO8TRkdT8dmYXTfI4sEjR5Kp.jpg", // The Day of the Jackal
        59698 to "lysUnU6V0VfcthDbviuVlIqgHOR.jpg", // Spy×Family
    )

    /** A text-free, screen-sized backdrop for a curated show, or null to fall back to TVmaze's art. */
    fun backdrop(tvmazeId: Int): String? = backdrops[tvmazeId]?.let { "https://image.tmdb.org/t/p/w1280/$it" }

    fun of(pkg: String): Brand? = brands[pkg]
    fun provider(pkg: String): Provider? = providers[pkg]
}

version = 1

cloudstream {
    description = "Free Side+ content - Sidecast, BTS, Side+ Saturdays, Inside, 1v100"
    language = "en"
    authors = listOf("imad")
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
    )
    iconUrl = "https://www.free-sideplus.com/favicon.ico"
    isCrossPlatform = false
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}

package com.lagradost.cloudstream3.animeproviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.get
import com.lagradost.cloudstream3.network.text
import com.lagradost.cloudstream3.network.url
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.Jsoup
import java.util.*

class GogoanimeProvider : MainAPI() {
    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Special")) TvType.ONA
            else if (t.contains("Movie")) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

        val qualityRegex = Regex("(\\d+)P")
    }

    override val mainUrl: String
        get() = "https://gogoanime.vc"
    override val name: String
        get() = "GogoAnime"
    override val hasQuickSearch: Boolean
        get() = false
    override val hasMainPage: Boolean
        get() = true

    override val supportedTypes: Set<TvType>
        get() = setOf(
            TvType.AnimeMovie,
            TvType.Anime,
            TvType.ONA
        )

    override fun getMainPage(): HomePageResponse {
        val headers = mapOf(
            "authority" to "ajax.gogo-load.com",
            "sec-ch-ua" to "\"Google Chrome\";v=\"89\", \"Chromium\";v=\"89\", \";Not A Brand\";v=\"99\"",
            "accept" to "text/html, */*; q=0.01",
            "dnt" to "1",
            "sec-ch-ua-mobile" to "?0",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/89.0.4389.90 Safari/537.36",
            "origin" to mainUrl,
            "sec-fetch-site" to "cross-site",
            "sec-fetch-mode" to "cors",
            "sec-fetch-dest" to "empty",
            "referer" to "$mainUrl/"
        )
        val parseRegex =
            Regex("""<li>\s*\n.*\n.*<a\s*href=["'](.*?-episode-(\d+))["']\s*title=["'](.*?)["']>\n.*?img src="(.*?)"""")

        val urls = listOf(
            Pair("1", "Recent Release - Sub"),
            Pair("2", "Recent Release - Dub"),
            Pair("3", "Recent Release - Chinese"),
        )

        val items = ArrayList<HomePageList>()
        for (i in urls) {
            try {
                val params = mapOf("page" to "1", "type" to i.first)
                val html = get(
                    "https://ajax.gogo-load.com/ajax/page-recent-release.html",
                    headers = headers,
                    params = params
                ).text
                items.add(HomePageList(i.second, (parseRegex.findAll(html).map {
                    val (link, epNum, title, poster) = it.destructured
                    val isSub = listOf(1, 3).contains(i.first.toInt())
                    AnimeSearchResponse(
                        title,
                        link,
                        this.name,
                        TvType.Anime,
                        poster,
                        null,
                        if (isSub) EnumSet.of(DubStatus.Subbed) else EnumSet.of(
                            DubStatus.Dubbed
                        ),
                        null,
                        if (!isSub) epNum.toIntOrNull() else null,
                        if (isSub) epNum.toIntOrNull() else null,
                    )
                }).toList()))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    override fun search(query: String): ArrayList<SearchResponse> {
        val link = "$mainUrl/search.html?keyword=$query"
        val html = get(link).text
        val doc = Jsoup.parse(html)

        val episodes = doc.select(""".last_episodes li""").map {
            AnimeSearchResponse(
                it.selectFirst(".name").text().replace(" (Dub)", ""),
                fixUrl(it.selectFirst(".name > a").attr("href")),
                this.name,
                TvType.Anime,
                it.selectFirst("img").attr("src"),
                it.selectFirst(".released")?.text()?.split(":")?.getOrNull(1)?.trim()?.toIntOrNull(),
                if (it.selectFirst(".name").text().contains("Dub")) EnumSet.of(DubStatus.Dubbed) else EnumSet.of(
                    DubStatus.Subbed
                ),
            )
        }

        return ArrayList(episodes)
    }

    private fun getProperAnimeLink(uri: String): String {
        if (uri.contains("-episode")) {
            val split = uri.split("/")
            val slug = split[split.size - 1].split("-episode")[0]
            return "$mainUrl/category/$slug"
        }
        return uri
    }

    override fun load(url: String): LoadResponse {
        val link = getProperAnimeLink(url)
        val episodeloadApi = "https://ajax.gogo-load.com/ajax/load-list-episode"
        val html = get(link).text
        val doc = Jsoup.parse(html)

        val animeBody = doc.selectFirst(".anime_info_body_bg")
        val title = animeBody.selectFirst("h1").text()
        val poster = animeBody.selectFirst("img").attr("src")
        var description: String? = null
        val genre = ArrayList<String>()
        var year: Int? = null
        var status: String? = null
        var nativeName: String? = null
        var type: String? = null

        animeBody.select("p.type").forEach { pType ->
            when (pType.selectFirst("span").text().trim()) {
                "Plot Summary:" -> {
                    description = pType.text().replace("Plot Summary:", "").trim()
                }
                "Genre:" -> {
                    genre.addAll(pType.select("a").map {
                        it.attr("title")
                    })
                }
                "Released:" -> {
                    year = pType.text().replace("Released:", "").trim().toIntOrNull()
                }
                "Status:" -> {
                    status = pType.text().replace("Status:", "").trim()
                }
                "Other name:" -> {
                    nativeName = pType.text().replace("Other name:", "").trim()
                }
                "Type:" -> {
                    type = pType.text().replace("type:", "").trim()
                }
            }
        }

        val animeId = doc.selectFirst("#movie_id").attr("value")
        val params = mapOf("ep_start" to "0", "ep_end" to "2000", "id" to animeId)
        val responseHTML = get(episodeloadApi, params = params).text
        val epiDoc = Jsoup.parse(responseHTML)
        val episodes = epiDoc.select("a").map {
            AnimeEpisode(
                fixUrl(it.attr("href").trim()),
                "Episode " + it.selectFirst(".name").text().replace("EP", "").trim()
            )
        }.reversed()
        return AnimeLoadResponse(
            title,
            nativeName,
            title,
            link,
            this.name,
            getType(type.toString()),
            poster,
            year,
            null,
            episodes,
            getStatus(status.toString()),
            description,
            ArrayList(genre),
            null,
            null,
            null,
        )
    }

    private val garbages = listOf(
        "URASDGHUSRFSJGYfdsffsderFStewthsfSFtrfte",
        "AdeqwrwedffryretgsdFrsftrsvfsfsr",
        "werFrefdsfrersfdsrfer36343534",
        "AawehyfcghysfdsDGDYdgdsf",
        "wstdgdsgtert",
        "Adrefsd",
        "sdf"
    )

    private fun decryptRedirect(uri: String): String {
        val isGogoCdn = Regex("""download\.php\?url=([^?&/]+)""").find(uri)
        if (isGogoCdn?.destructured == null) return uri

        var encryptedUrl = isGogoCdn.destructured.component1()
        garbages.forEach {
            encryptedUrl = encryptedUrl.replace(it, "")
        }

        return base64Decode(encryptedUrl + "=".repeat(encryptedUrl.length % 4))
    }

    private fun extractVideos(uri: String): List<ExtractorLink> {
        val html = get(uri).text
        val doc = Jsoup.parse(html)

        val iframe = "https:" + doc.selectFirst("div.play-video > iframe").attr("src")
        val link = iframe.replace("streaming.php", "download")

        val page = get(link, referer=iframe, cacheTime = 0)
        val pageDoc = Jsoup.parse(page.text)

        val qualities = ArrayList<ExtractorLink>()

        pageDoc.select(".dowload > a").pmap {
            if (it.hasAttr("download")) {
                val qual = if (it.text()
                        .contains("HDP")
                ) "1080" else qualityRegex.find(it.text())?.destructured?.component1().toString()
                qualities.add(
                    ExtractorLink(
                        "Gogoanime",
                        if (qual == "null") "Gogoanime" else "Gogoanime - " + qual + "p",
                        decryptRedirect(it.attr("href")),
                        page.url,
                        getQualityFromName(qual),
                        it.attr("href").contains(".m3u8"),
                        mapOf("Referer" to it.attr("href"))
                    )
                )
            } else {
                val url = it.attr("href")
                for (api in extractorApis) {
                    if (url.startsWith(api.mainUrl)) {
                        qualities.addAll(api.getSafeUrl(url) ?: listOf())
                        break
                    }
                }
            }
        }
        println("GOGO: ${qualities.size}, $qualities")
        return qualities
    }

    override fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        for (source in extractVideos(data)) {
            callback.invoke(source)
        }
        return true
    }
}

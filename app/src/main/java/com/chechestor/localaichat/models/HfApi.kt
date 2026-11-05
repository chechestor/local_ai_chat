package com.chechestor.localaichat.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Что ввёл пользователь на экране «Модель с Hugging Face» (F010 п. 3). */
sealed interface HfQuery {
    data class Repo(val repo: String) : HfQuery
    data class Search(val text: String) : HfQuery
    data object Empty : HfQuery

    companion object {
        private val REPO_URL = Regex("^https?://(www\\.)?(huggingface\\.co|hf\\.co)/([^/?#]+)/([^/?#]+)([/?#].*)?$", RegexOption.IGNORE_CASE)

        fun parse(input: String): HfQuery {
            val text = input.trim()
            if (text.isEmpty()) return Empty
            REPO_URL.matchEntire(text)?.let { m ->
                val repo = "${m.groupValues[3]}/${m.groupValues[4]}"
                // Ссылки на разделы сайта (models, datasets, spaces…) — не репозиторий модели.
                if (HfNames.isRepoId(repo) && m.groupValues[3].lowercase() !in SITE_SECTIONS) return Repo(repo)
                return Search(text)
            }
            return if (HfNames.isRepoId(text)) Repo(text) else Search(text)
        }

        private val SITE_SECTIONS = setOf("models", "datasets", "spaces", "docs", "api", "blog", "settings", "login", "join")
    }
}

/** Репозиторий из поиска. */
data class HfRepoHit(val repo: String, val downloads: Long)

/** Файл в корне репозитория. [sha256] — `lfs.oid` (null, если файл не в LFS). */
data class HfFile(val path: String, val size: Long, val sha256: String?)

/** Ответ на запрос сведений о репозитории (F010 п. 4). */
sealed interface HfRepoResult {
    data class Ok(val repo: String, val commit: String, val files: List<HfFile>) : HfRepoResult
    data object NotFound : HfRepoResult
    data object Gated : HfRepoResult
    data object NetworkError : HfRepoResult
}

sealed interface HfSearchResult {
    data class Ok(val hits: List<HfRepoHit>) : HfSearchResult
    data object NetworkError : HfSearchResult
}

/** Выбор GGUF-файла в репозитории (F010 п. 5). */
sealed interface FileChoice {
    data class Suggested(val file: HfFile) : FileChoice
    data class PickOne(val files: List<HfFile>) : FileChoice
    data object None : FileChoice
}

/**
 * API Hugging Face без входа и токенов (F010 п. 4, п. 10): только `huggingface.co`, только https.
 */
object HfApi {
    private const val BASE = "https://huggingface.co"
    private const val MAX_REDIRECTS = 3
    private val SPLIT = Regex("-\\d{5}-of-\\d{5}\\.gguf$", RegexOption.IGNORE_CASE)

    /** Подходящие файлы: одиночные `.gguf` в корне, не `mmproj`, не разбитые на части, с SHA-256 из LFS. */
    fun suitable(files: List<HfFile>): List<HfFile> = files.filter { f ->
        val name = f.path
        !name.contains('/') &&
            name.endsWith(".gguf", ignoreCase = true) &&
            !name.lowercase().startsWith("mmproj") &&
            !SPLIT.containsMatchIn(name) &&
            f.sha256 != null && f.sha256.length == 64 && f.size > 0
    }

    fun chooseFile(files: List<HfFile>): FileChoice {
        val ok = suitable(files)
        if (ok.isEmpty()) return FileChoice.None
        val q4 = ok.filter { it.path.contains("Q4_K_M", ignoreCase = true) }
        if (q4.isNotEmpty()) {
            return FileChoice.Suggested(q4.firstOrNull { !it.path.contains("UD-", ignoreCase = true) } ?: q4.first())
        }
        return FileChoice.PickOne(ok.sortedBy { it.size })
    }

    suspend fun search(text: String): HfSearchResult = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(text, "UTF-8")
        runCatching {
            val (code, body) = get("$BASE/api/models?search=$q&filter=gguf&sort=downloads&direction=-1&limit=10")
            if (code != 200) return@runCatching HfSearchResult.NetworkError
            val arr = JSONArray(body)
            val hits = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id", o.optString("modelId"))
                if (!HfNames.isRepoId(id)) null else HfRepoHit(id, o.optLong("downloads", 0))
            }
            HfSearchResult.Ok(hits)
        }.getOrElse { HfSearchResult.NetworkError }
    }

    suspend fun repo(repo: String): HfRepoResult = withContext(Dispatchers.IO) {
        if (!HfNames.isRepoId(repo)) return@withContext HfRepoResult.NotFound
        runCatching {
            val (code, body) = get("$BASE/api/models/$repo")
            // Без входа Hugging Face отвечает 401 и на несуществующий репозиторий.
            if (code == 401 || code == 404) return@runCatching HfRepoResult.NotFound
            if (code != 200) return@runCatching HfRepoResult.NetworkError
            val info = JSONObject(body)
            val gated = info.opt("gated")
            if ((gated != null && gated != false && gated != JSONObject.NULL) || info.optBoolean("private", false)) {
                return@runCatching HfRepoResult.Gated
            }
            val sha = info.optString("sha")
            val realRepo = info.optString("id", repo).takeIf { HfNames.isRepoId(it) } ?: repo
            if (sha.length != 40) return@runCatching HfRepoResult.NetworkError
            val (treeCode, treeBody) = get("$BASE/api/models/$realRepo/tree/$sha")
            if (treeCode == 401 || treeCode == 404) return@runCatching HfRepoResult.NotFound
            if (treeCode != 200) return@runCatching HfRepoResult.NetworkError
            val arr = JSONArray(treeBody)
            val files = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                if (o.optString("type") != "file") return@mapNotNull null
                val lfs = o.optJSONObject("lfs")
                HfFile(
                    path = o.optString("path"),
                    size = lfs?.optLong("size", -1)?.takeIf { it > 0 } ?: o.optLong("size", -1),
                    sha256 = lfs?.optString("oid")?.takeIf { it.length == 64 },
                )
            }
            HfRepoResult.Ok(realRepo, sha, files)
        }.getOrElse { HfRepoResult.NetworkError }
    }

    /** GET с ручными редиректами только внутри `huggingface.co` (переименованные репозитории). */
    private fun get(url: String): Pair<Int, String> {
        var current = URL(url)
        repeat(MAX_REDIRECTS + 1) {
            if (current.protocol != "https" || current.host.lowercase() != "huggingface.co") {
                throw IOException("Недопустимый адрес: ${current.host}")
            }
            val conn = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "LocalAIChat-Android")
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: throw IOException("Редирект без адреса")
                    current = URL(current, location)
                    return@repeat
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                return code to body
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Слишком много перенаправлений")
    }
}

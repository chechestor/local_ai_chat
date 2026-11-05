package com.chechestor.localaichat.models

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP для скачивания моделей (F008): только https, только хосты источника,
 * редиректы вручную — разрешены лишь с huggingface.co на CDN Hugging Face.
 */
object ModelHttp {
    private const val MAX_REDIRECTS = 5

    class ForbiddenHostException(host: String) : IOException("Переход на недопустимый адрес: $host")

    /** Можно ли идти на [host], если загрузка начиналась с [originHost]. */
    fun isAllowedHost(originHost: String, host: String): Boolean {
        val h = host.lowercase()
        return when (originHost.lowercase()) {
            "huggingface.co" -> h == "huggingface.co" || h.endsWith(".huggingface.co") || h == "hf.co" || h.endsWith(".hf.co")
            else -> h == originHost.lowercase()
        }
    }

    /** Полный размер файла из заголовка `Content-Range: bytes 100-999/1000`; null, если не разобрать. */
    fun totalFromContentRange(header: String?): Long? =
        header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()

    /**
     * Открывает загрузку [url] с позиции [offset] (докачка через Range).
     * Возвращает соединение с кодом 200 (с начала) или 206 (с [offset]).
     */
    fun open(url: String, offset: Long): HttpURLConnection {
        val origin = URL(url)
        require(origin.protocol == "https") { "Только https" }
        var current = origin
        repeat(MAX_REDIRECTS + 1) {
            if (current.protocol != "https" || !isAllowedHost(origin.host, current.host)) {
                throw ForbiddenHostException(current.host)
            }
            val conn = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "LocalAIChat-Android")
                setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: throw IOException("Редирект без адреса")
                conn.disconnect()
                current = URL(current, location)
                return@repeat
            }
            if (code == 200 || code == 206) return conn
            if (code == 416) { // уже скачано целиком — сервер не отдаёт ничего после конца файла
                return conn
            }
            conn.disconnect()
            throw IOException("Сервер ответил кодом $code")
        }
        throw IOException("Слишком много перенаправлений")
    }
}

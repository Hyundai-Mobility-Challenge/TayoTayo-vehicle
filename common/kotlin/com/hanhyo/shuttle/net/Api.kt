package com.hanhyo.shuttle.net

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

class ApiFailure(val status: Int, message: String) : IllegalStateException(message)

/** Blocking client; callers use Dispatchers.IO. Never log bearer tokens or bodies. */
object Api {
    fun base(value: String, debug: Boolean): String {
        val result = value.trim().trimEnd('/')
        val uri = URI(result)
        require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.orEmpty().isEmpty()) { "서버 주소만 입력해 주세요. 예: https://shuttle.example.com" }
        val ip = uri.host.split('.').mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
        val privateIp = ip.size == 4 && (ip[0] == 10 || (ip[0] == 192 && ip[1] == 168) ||
            (ip[0] == 172 && ip[1] in 16..31) || uri.host == "127.0.0.1")
        require(uri.scheme == "https" || (debug && uri.scheme == "http" && (privateIp || uri.host == "localhost"))) { "HTTPS 서버 주소가 필요합니다. 개발 앱에서는 내부망 HTTP도 사용할 수 있습니다." }
        return result
    }
    fun request(
        base: String,
        path: String,
        token: String = "",
        data: JSONObject? = null,
        method: String? = null,
        credentialHeader: String = "Authorization",
    ): JSONObject {
        val connection = URI(base + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 7000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = false
            val requestMethod = method ?: if (data != null) "POST" else "GET"
            connection.requestMethod = requestMethod
            if (token.isNotEmpty()) connection.setRequestProperty(credentialHeader,
                if (credentialHeader.equals("Authorization", ignoreCase = true)) "Bearer $token" else token)
            if (data != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val json = stream?.bufferedReader()?.use { JSONObject(it.readText()) } ?: JSONObject()
            if (status !in 200..299) throw ApiFailure(status, json.optString("error", "서버 연결 실패 ($status)"))
            return json
        } finally { connection.disconnect() }
    }
}

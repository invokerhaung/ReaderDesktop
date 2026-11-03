package io.legado.app.help.http

import okhttp3.Headers
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

class StrResponse {
    var raw: Response
        private set
    var body: String? = null
        private set
    var errorBody: ResponseBody? = null
        private set

    constructor(rawResponse: Response, body: String?) {
        this.raw = rawResponse
        this.body = body
    }

    constructor(url: String, body: String?) {
        val request = runCatching { Request.Builder().url(url).build() }
            .getOrElse { Request.Builder().url("http://localhost/").build() }
        raw = Response.Builder()
            .code(200)
            .message("OK")
            .protocol(Protocol.HTTP_1_1)
            .request(request)
            .build()
        this.body = body
    }

    constructor(rawResponse: Response, errorBody: ResponseBody?) {
        this.raw = rawResponse
        this.errorBody = errorBody
    }

    val url: String
        get() = raw.networkResponse?.request?.url?.toString() ?: raw.request.url.toString()

    fun headers(): Headers = raw.headers

    fun isSuccessful(): Boolean = raw.isSuccessful

    fun code(): Int = raw.code
}

package com.readymadeit.meander.bridge

data class HkpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
)

data class HkpResponse(
    val statusCode: Int,
    val mimeType: String = "application/json",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    companion object {
        fun json(statusCode: Int, body: String): HkpResponse =
            HkpResponse(
                statusCode = statusCode,
                mimeType = "application/json",
                body = body.toByteArray(Charsets.UTF_8),
            )
    }
}

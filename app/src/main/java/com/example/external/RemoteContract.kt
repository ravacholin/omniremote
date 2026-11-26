package com.example.external

import com.example.data.model.RemoteKey
import org.json.JSONArray
import com.squareup.moshi.JsonReader
import okio.Buffer
import java.io.IOException
import org.json.JSONObject
import java.security.MessageDigest

internal const val API_VERSION = 1

internal enum class Command(val parameters: List<String> = emptyList()) {
    GET_STATE, POWER_ON, POWER_OFF, KEY(listOf("key")), TEXT(listOf("text")),
    SET_VOLUME(listOf("level")), SET_MUTE(listOf("muted")), OPEN_APP(listOf("appId")),
    OPEN_BRIGHTNESS, OPEN_BRIGHTNESS_ADB, OPEN_BLUETOOTH_AUDIO,
    SLEEP_FOR(listOf("minutes")), SLEEP_AT(listOf("hour", "minute")), SLEEP_CANCEL, SLEEP_EXTEND,
    MAC_OPEN_LINK(listOf("url")), MAC_MEDIA_LIST, MAC_MEDIA_REVEAL(listOf("sourceId")), MAC_MEDIA_STATE(listOf("sourceId")),
    MAC_MEDIA_KEY(listOf("sourceId", "key"))
}

internal data class RemoteRequest(val deviceId: Long, val command: Command, val args: List<Any>) {
    fun fingerprint(): String = digest(JSONArray(listOf(API_VERSION, deviceId, command.name) + args)
        .toString().toByteArray(Charsets.UTF_8))

    fun key(): RemoteKey = RemoteKey.valueOf(args.last() as String)
    fun text(): String = args.first() as String
    fun number(index: Int = 0): Int = (args[index] as Long).toInt()
    fun appId(): Long = args.first() as Long
    fun muted(): Boolean = args.first() as Boolean

    companion object {
        fun parse(version: Int, deviceId: Long, name: String?, json: String?): RemoteRequest {
            require(version == API_VERSION && deviceId > 0 && name != null && name.length in 1..40)
            require(json != null && json.length in 2..8192)
            val command = Command.valueOf(name)
            val args = readArguments(json, command.parameters)
            return RemoteRequest(deviceId, command, command.parameters.map { param ->
                val value = args.getValue(param)
                when (param) {
                    "key" -> RemoteKey.valueOf(boundedString(value, 32)).name
                    "text" -> boundedString(value, 1024)
                    "url" -> boundedString(value, 4096)
                    "sourceId" -> boundedString(value, 128)
                    "appId" -> integer(value, 1L..Long.MAX_VALUE)
                    "level" -> integer(value, 0L..100)
                    "minutes" -> integer(value, 1L..1440)
                    "hour" -> integer(value, 0L..23)
                    "minute" -> integer(value, 0L..59)
                    "muted" -> value as? Boolean ?: throw IllegalArgumentException("Invalid boolean")
                    else -> error("Unknown parameter")
                }
            })
        }

        private fun readArguments(json: String, parameters: List<String>): Map<String, Any> {
            val arguments = mutableMapOf<String, Any>()
            try {
                JsonReader.of(Buffer().writeUtf8(json)).use { reader ->
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val field = reader.nextName()
                        require(field in parameters && field !in arguments)
                        arguments[field] = when (reader.peek()) {
                            JsonReader.Token.STRING -> reader.nextString()
                            JsonReader.Token.NUMBER -> reader.nextString().toLongOrNull()
                                ?: throw IllegalArgumentException("Invalid integer")
                            JsonReader.Token.BOOLEAN -> reader.nextBoolean()
                            else -> throw IllegalArgumentException("Invalid parameter type")
                        }
                    }
                    reader.endObject()
                    require(reader.peek() == JsonReader.Token.END_DOCUMENT && arguments.size == parameters.size)
                }
            } catch (e: IOException) {
                throw IllegalArgumentException("Invalid JSON", e)
            } catch (e: com.squareup.moshi.JsonDataException) {
                throw IllegalArgumentException("Invalid JSON", e)
            }
            return arguments
        }

        private fun boundedString(value: Any, max: Int): String =
            (value as? String)?.takeIf { it.length in 1..max && it.indexOf('\u0000') < 0 }
                ?: throw IllegalArgumentException("Invalid string")

        private fun integer(value: Any, range: LongRange): Long {
            require(value is Int || value is Long)
            return (value as Number).toLong().also { require(it in range) }
        }
    }
}

internal data class RemoteReply(val status: String, val code: String, val data: JSONObject? = null) {
    fun encode(executionId: String? = null): String = JSONObject().apply {
        put("apiVersion", API_VERSION)
        put("status", status)
        put("code", code)
        if (executionId != null) put("executionId", executionId)
        if (data != null) put("data", data)
    }.toString()

    companion object {
        val pending = RemoteReply("PENDING", "ACCEPTED")
        val sent = RemoteReply("SENT", "DISPATCHED")
        val unsupported = RemoteReply("UNSUPPORTED", "NOT_SUPPORTED")
        fun verified(data: JSONObject? = null) = RemoteReply("VERIFIED", "OK", data)
        fun rejected(code: String) = RemoteReply("REJECTED", code)
        fun uncertain(code: String) = RemoteReply("UNCERTAIN", code)
    }
}

internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02X".format(it.toInt() and 0xFF) }

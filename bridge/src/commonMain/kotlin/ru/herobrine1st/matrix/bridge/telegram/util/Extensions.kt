package ru.herobrine1st.matrix.bridge.telegram.util

import com.github.kotlintelegrambot.network.Response
import com.github.kotlintelegrambot.types.TelegramBotResult
import com.google.gson.Gson
import com.google.gson.JsonIOException
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonToken

fun <T : Any> Pair<retrofit2.Response<Response<T>?>?, Exception?>.toResult(): TelegramBotResult<T> {
    second?.let { return TelegramBotResult.Error.Unknown(it) }
    val apiResponse = first!!

    // Null if malformed
    val responseBody = if (apiResponse.isSuccessful) {
        apiResponse.body()
    } else {
        // Adapter from retrofit-converter-gson
        apiResponse.errorBody()?.use { body ->
            val gson = Gson()
            val jsonReader = gson.newJsonReader(body.charStream())
            val adapter: TypeAdapter<Response<T>> = gson.getAdapter(object : TypeToken<Response<T>>() {})
            val result = adapter.read(jsonReader)
            if (jsonReader.peek() != JsonToken.END_DOCUMENT) {
                return TelegramBotResult.Error.Unknown(JsonIOException("JSON document was not fully consumed."))
            }
            result
        }
    }

    return responseBody
        ?.takeIf {
            when {
                // Take if successful
                it.ok -> true
                // Return early on error
                else -> return@toResult TelegramBotResult.Error.TelegramApi(
                    // Don't take if malformed
                    errorCode = it.errorCode ?: return@takeIf false,
                    description = it.errorDescription ?: return@takeIf false,
                )
            }
        }
        ?.result?.let {
            TelegramBotResult.Success(it)
        }
        // Handle malformed responses
        ?: TelegramBotResult.Error.InvalidResponse(
            apiResponse.code(),
            apiResponse.message(),
            apiResponse.body(),
        )
}
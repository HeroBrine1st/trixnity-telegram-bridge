package ru.herobrine1st.matrix.bridge.telegram.util

import com.github.kotlintelegrambot.network.Response
import com.github.kotlintelegrambot.types.TelegramBotResult
import com.google.gson.Gson
import com.google.gson.JsonIOException
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonToken
import java.io.IOException

fun <T : Any> Pair<retrofit2.Response<Response<T>?>?, Exception?>.toResult(): TelegramBotResult<T> {
    return when (val res = toResult<Response<T>>()) {
        is TelegramBotResult.Error -> res
        is TelegramBotResult.Success -> TelegramBotResult.Success(res.value.result!!)
    }
}

@JvmName("toGenericResult")
fun <T : Any> Pair<retrofit2.Response<T?>?, Exception?>.toResult(): TelegramBotResult<T> {
    second?.let { return TelegramBotResult.Error.Unknown(it) }
    val apiResponse = first!!

    // SAFETY: Internally isSuccessful=true means body() is not null and isSuccessful=false means errorBody() is not null
    // No guarantees are given though
    // So, an exception is thrown in case this contract is not fulfilled.
    if (!apiResponse.isSuccessful) {
        // Adapter from retrofit-converter-gson
        apiResponse.errorBody()!!.use { body ->
            val gson = Gson()
            val jsonReader = gson.newJsonReader(body.charStream())
            val adapter: TypeAdapter<Response<Nothing>> = gson.getAdapter(object : TypeToken<Response<Nothing>>() {})
            val result = adapter.read(jsonReader)
            if (jsonReader.peek() != JsonToken.END_DOCUMENT) {
                return TelegramBotResult.Error.Unknown(JsonIOException("JSON document was not fully consumed."))
            }
            return TelegramBotResult.Error.TelegramApi(
                errorCode = result.errorCode!!,
                description = result.errorDescription!!,
            )
        }
    }

    return TelegramBotResult.Success(apiResponse.body()!!)
}

fun <T : Any> TelegramBotResult<T>.getOrThrow(): T {
    when (this) {
        is TelegramBotResult.Success -> return this.value

        is TelegramBotResult.Error.HttpError -> throw IOException("HTTP error $httpCode: $description")
        is TelegramBotResult.Error.TelegramApi -> throw TelegramApiException(errorCode, description)
        is TelegramBotResult.Error.InvalidResponse -> throw IllegalStateException("Got invalid response ($this)")
        is TelegramBotResult.Error.Unknown -> throw this.exception
    }
}
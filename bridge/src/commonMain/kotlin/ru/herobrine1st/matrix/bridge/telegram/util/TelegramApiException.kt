package ru.herobrine1st.matrix.bridge.telegram.util

import java.io.IOException

class TelegramApiException(val errorCode: Int, override val message: String) : IOException()
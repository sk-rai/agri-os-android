package com.agrios.app.ui.auth

/** UI convenience only: the server must still verify every code. No fixed OTP or bypass. */
internal object DevelopmentOtp {
    fun valid(value: String): Boolean = value.length == 6 && value.all { it in '0'..'9' }
    fun autofill(debug: Boolean, supplied: String?): String? =
        supplied?.takeIf { debug && valid(it) }
}

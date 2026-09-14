package com.webdavcast.app

import android.util.Base64

/** Basic 认证。 */
object BasicAuth {
    fun header(user: String, pass: String): String =
        "Basic " + Base64.encodeToString("$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}

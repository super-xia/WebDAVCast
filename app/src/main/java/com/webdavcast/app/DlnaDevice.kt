package com.webdavcast.app

/** DLNA 媒体渲染器(电视)。 */
data class DlnaDevice(
    val udn: String,
    val friendlyName: String,
    val location: String,
    val controlUrl: String,
)

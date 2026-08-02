package com.warpy.app.model

enum class VpnState(val label: String) {
    Stopped("stopped"),
    Starting("starting"),
    Validating("validating"),
    Connected("connected"),
    Recovering("recovering"),
    Stopping("stopping"),
    Error("error")
}

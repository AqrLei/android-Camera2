package com.example.android.camera2raw.permission

/**
 * @author  aqrLei on 2018/8/16
 */
abstract class PermissionImpl {

    abstract fun hasAllPermissionsGranted(): Boolean

    abstract fun requestPermissions()

    abstract fun shouldShowRationale(): Boolean

    abstract fun showMissingPermissionError()
}
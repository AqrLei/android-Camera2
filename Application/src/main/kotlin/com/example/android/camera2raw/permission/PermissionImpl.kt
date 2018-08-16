package com.example.android.camera2raw.permission

import android.support.v4.app.DialogFragment

/**
 * @author  aqrLei on 2018/8/16
 */
abstract class PermissionImpl {

    abstract fun hasAllPermissionsGranted(): Boolean

    abstract fun requestPermissions()

    abstract fun shouldShowRationale(): Boolean

    abstract fun showMissingPermissionError()
}
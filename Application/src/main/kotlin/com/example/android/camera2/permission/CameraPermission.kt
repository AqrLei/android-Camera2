package com.example.android.camera2.permission

import android.Manifest
import android.content.pm.PackageManager
import android.support.v4.app.ActivityCompat
import android.support.v4.app.Fragment
import android.widget.Toast
import com.example.android.camera2.R

/**
 * @author  aqrLei on 2018/8/16
 */
class CameraPermission(private val fragment: Fragment) : PermissionImpl() {
    companion object {
        const val REQUEST_CAMERA_PERMISSIONS = 1
        private val CAMERA_PERMISSIONS = arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    override fun hasAllPermissionsGranted(): Boolean {
        fragment.context?.let { context ->
            CAMERA_PERMISSIONS.forEach {
                if (ActivityCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED) {
                    return false
                }
            }
        }
        return true
    }

    override fun requestPermissions() {
        if (shouldShowRationale()) {
            PermissionConfirmationDialog.newInstance()
                    .show(fragment.childFragmentManager, "dialog", CAMERA_PERMISSIONS, REQUEST_CAMERA_PERMISSIONS)
        } else {
            fragment.requestPermissions(CAMERA_PERMISSIONS, REQUEST_CAMERA_PERMISSIONS)
        }
    }

    override fun shouldShowRationale(): Boolean {
        for (permission in CAMERA_PERMISSIONS) {
            if (fragment.shouldShowRequestPermissionRationale(permission)) {
                return true
            }
        }
        return false
    }

    override fun showMissingPermissionError() {
        //TODO to app setting layout
        fragment.activity?.let {
            Toast.makeText(it, R.string.request_permission, Toast.LENGTH_SHORT).show()
            it.finish()
        }
    }
}
package com.example.android.camera2raw

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.support.v4.app.DialogFragment

/**
 * @author  aqrLei on 2018/8/14
 */
class PermissionConfirmationDialog : DialogFragment() {
    companion object {
        fun newInstance() = PermissionConfirmationDialog()

    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val parent = parentFragment
        return AlertDialog.Builder(activity)
                .setMessage(R.string.request_permission)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    parent?.requestPermissions(CameraUtils.CAMERA_PERMISSIONS, CameraUtils.REQUEST_CAMERA_PERMISSIONS)

                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    parent?.activity?.finish()

                }
                .create()
    }
}
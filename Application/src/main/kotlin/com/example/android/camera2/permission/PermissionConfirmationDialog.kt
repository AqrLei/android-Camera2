package com.example.android.camera2.permission

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.support.v4.app.DialogFragment
import android.support.v4.app.FragmentManager
import com.example.android.camera2.R


/**
 * @author  aqrLei on 2018/8/14
 */
class PermissionConfirmationDialog : DialogFragment() {
    companion object {
        fun newInstance() = PermissionConfirmationDialog()
    }

    private lateinit var permissions: Array<String>
    private var reqCode: Int = 0
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val parent = parentFragment
        return AlertDialog.Builder(activity)
                .setMessage(R.string.request_permission)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    parent?.requestPermissions(permissions, reqCode)

                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    parent?.activity?.finish()

                }
                .create()
    }

    fun show(manager: FragmentManager?, tag: String?, permissions: Array<String>, reqCode: Int) {
        this.permissions = permissions
        this.reqCode = reqCode
        super.show(manager, tag)
    }
}
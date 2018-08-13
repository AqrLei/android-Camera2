package com.example.android.camera2raw

import android.hardware.SensorManager
import android.os.Bundle
import android.support.v4.app.Fragment
import android.view.LayoutInflater
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import kotlinx.android.synthetic.main.fragment_camera2_basic.*

/**
 * @author  aqrLei on 2018/8/13
 */
class Camera2Fragment : Fragment(), View.OnClickListener {
    companion object {
        fun newInstance() = Camera2Fragment()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_camera2_basic,container,false)
    }

    private lateinit var mOrientationListener: OrientationEventListener
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        picture.setOnClickListener(this)
        info.setOnClickListener(this)
        mOrientationListener =object :OrientationEventListener(activity,SensorManager.SENSOR_DELAY_NORMAL){
            override fun onOrientationChanged(orientation: Int) {
                TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
            }
        }

    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onClick(v: View?) {

    }

}
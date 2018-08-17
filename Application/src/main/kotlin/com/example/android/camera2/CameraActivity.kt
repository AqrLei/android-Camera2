package com.example.android.camera2

import android.os.Bundle
import android.support.v7.app.AppCompatActivity

/**
 * @author  aqrLei on 2018/8/14
 */
class CameraActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        if (null == savedInstanceState) {
            supportFragmentManager.beginTransaction()
                    .replace(R.id.container, Camera2Fragment.newInstance())
                    .commit()
        }
    }
}
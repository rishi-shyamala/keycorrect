package dev.keycorrect.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Asks for RECORD_AUDIO and finishes: a keyboard cannot show the permission dialog, so voice typing
 * opens this transparent activity (in its own task) when the permission is missing. Dictation
 * starts the next time the user long-presses the comma.
 */
class MicPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            finish()
        } else if (savedInstanceState == null) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }

    companion object {
        private const val REQUEST_CODE = 1

        /** Opens the request from the keyboard service. */
        fun launch(context: Context) {
            context.startActivity(
                Intent(context, MicPermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

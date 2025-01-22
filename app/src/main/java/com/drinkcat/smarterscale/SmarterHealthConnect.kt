package com.drinkcat.smarterscale

import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.units.Mass
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.ZonedDateTime

class SmarterHealthConnect(private val context: ComponentActivity) {
    private final val TAG = "SmarterHealthConnect"

    private val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    private val permissions = setOf(
        HealthPermission.getWritePermission(WeightRecord::class),
    )

    private var init = false
    private lateinit var requestPermissions: ActivityResultLauncher<Set<String>>

    fun init() {
        val availabilityStatus = HealthConnectClient.getSdkStatus(context)
        if (availabilityStatus != HealthConnectClient.SDK_AVAILABLE) {
            Log.e(TAG, "Health connect not available!")
            Toast.makeText(context, "Health connect not available!", Toast.LENGTH_LONG).show()
            return
        }

        val requestPermissionActivityContract = PermissionController.createRequestPermissionResultContract()

        requestPermissions = context.registerForActivityResult(requestPermissionActivityContract) { granted ->
            if (granted.containsAll(permissions)) {
                Log.d(TAG, "Health connect permissions granted!")
                //Toast.makeText(context, "Health connect permissions granted!", Toast.LENGTH_LONG).show()
                context.lifecycle.coroutineScope.launch { writeWeightInputCallback() }
            } else {
                Log.d(TAG, "Health connect permissions denied!")
                Toast.makeText(context, "Health connect permissions denied!", Toast.LENGTH_LONG).show()
            }
        }

        init = true;
    }

    private suspend fun checkOrRequestPermissions(): Boolean {
        if (!init)
            return false

        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        if (granted.containsAll(permissions)) {
            Log.d(TAG, "Health connect permissions all good!")
            return true
        } else {
            requestPermissions.launch(permissions)
            return false
        }
    }

    /* Saved weight for callback */
    private var mWeightInput: Double? = null
    /**
     * Writes [WeightRecord] to Health Connect.
     */
    private suspend fun writeWeightInputCallback() {
        if (mWeightInput == null)
            return;

        val time = ZonedDateTime.now().withNano(0)
        val weightRecord = WeightRecord(
            weight = Mass.kilograms(mWeightInput!!),
            time = time.toInstant(),
            zoneOffset = time.offset
        )
        val records = listOf(weightRecord)
        try {
            healthConnectClient.insertRecords(records)
            Toast.makeText(context, "Successfully recorded: " + mWeightInput + "!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Error inserting record: " + e.toString(), Toast.LENGTH_LONG).show()
        }

        mWeightInput = null;
    }

    /**
     * Writes [WeightRecord] to Health Connect.
     */
    suspend fun writeWeightInput(weightInput: Double) {
        if (!init)
            return

        mWeightInput = weightInput
        if (checkOrRequestPermissions()) {
            writeWeightInputCallback()
        } /* else: ActivityResult will launch the callback. */
    }
}
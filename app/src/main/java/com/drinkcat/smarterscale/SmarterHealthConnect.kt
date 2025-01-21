package com.drinkcat.smarterscale

import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.units.Mass
import kotlinx.coroutines.runBlocking
import java.time.ZonedDateTime

class SmarterHealthConnect(private val context: ComponentActivity) {
    private final val TAG = "SmarterHealthConnect"

    private val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun checkPermissions() {
        val availabilityStatus = HealthConnectClient.getSdkStatus(context)
        if (availabilityStatus != HealthConnectClient.SDK_AVAILABLE) {
            Log.e(TAG, "Health connect not available!")
            Toast.makeText(context, "Health connect not available!", Toast.LENGTH_LONG).show()
            return
        }

        val permissions = setOf(
            HealthPermission.getWritePermission(WeightRecord::class),
        )

        val requestPermissionActivityContract = PermissionController.createRequestPermissionResultContract()

        val requestPermissions = context.registerForActivityResult(requestPermissionActivityContract) { granted ->
            if (granted.containsAll(permissions)) {
                Log.d(TAG, "Health connect permissions granted!")
                Toast.makeText(context, "Health connect permissions granted!", Toast.LENGTH_LONG).show()
            } else {
                Log.d(TAG, "Health connect permissions denied!")
                Toast.makeText(context, "Health connect permissions denied!", Toast.LENGTH_LONG).show()
            }
        }

        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        if (granted.containsAll(permissions)) {
            Log.d(TAG, "Health connect permissions all good!")
        } else {
            requestPermissions.launch(permissions)
        }
    }

    /**
     * Writes [WeightRecord] to Health Connect.
     */
    suspend fun writeWeightInput(weightInput: Double) {
        val time = ZonedDateTime.now().withNano(0)
        val weightRecord = WeightRecord(
            weight = Mass.kilograms(weightInput),
            time = time.toInstant(),
            zoneOffset = time.offset
        )
        val records = listOf(weightRecord)
        try {
            healthConnectClient.insertRecords(records)
            Toast.makeText(context, "Successfully recorded!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Error inserting record: " + e.toString(), Toast.LENGTH_LONG).show()
        }
    }
}
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
import java.time.ZonedDateTime

enum class WeightUnit {
    KG, LB;

    override fun toString(): String {
        return when(this) {
            KG -> "kg"
            LB -> "lb"
        }
    }

    companion object {
        fun fromString(s: String): WeightUnit {
            return when(s) {
                "kg" -> KG
                "lb" -> LB
                else -> throw IllegalArgumentException("Invalid weight string $s.")
            }
        }
    }
}

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
    private var mWeightInput: Mass? = null
    /**
     * Writes [WeightRecord] to Health Connect.
     */
    private suspend fun writeWeightInputCallback() {
        if (mWeightInput == null)
            return;

        val time = ZonedDateTime.now().withNano(0)
        val weightRecord = WeightRecord(
            weight = mWeightInput!!,
            time = time.toInstant(),
            zoneOffset = time.offset
        )
        val records = listOf(weightRecord)
        try {
            healthConnectClient.insertRecords(records)
            Log.d(TAG, "Successfully recorded weight!")
            Toast.makeText(context, "Successfully recorded weight!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.d(TAG, "Error inserting record: $e")
            Toast.makeText(context, "Error inserting record: $e", Toast.LENGTH_LONG).show()
        }

        mWeightInput = null;
    }

    /**
     * Writes [WeightRecord] to Health Connect.
     */
    suspend fun writeWeightInput(weightInput: Double, unit: WeightUnit) {
        if (!init)
            return

        mWeightInput = when(unit) {
            WeightUnit.KG -> Mass.kilograms(weightInput)
            WeightUnit.LB -> Mass.pounds(weightInput)
        }
        if (checkOrRequestPermissions()) {
            writeWeightInputCallback()
        } /* else: ActivityResult will launch the callback. */
    }
}
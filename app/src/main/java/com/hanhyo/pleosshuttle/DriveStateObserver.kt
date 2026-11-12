package com.hanhyo.pleosshuttle

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.hanhyo.pleosshuttle.domain.Gear
import java.util.concurrent.Executors

/** Read-only vehicle state, active only during a trip tracking service. */
class DriveStateObserver(context:Context) {
    private val handler=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var car:Car?=null
    private var manager:CarPropertyManager?=null
    private var ux:CarUxRestrictionsManager?=null
    private var generation=0
    private var closed=false
    private var reading=false
    var current=DriveObservation();private set
    init {
        try {
            car=Car.createCar(context,handler,Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { vehicle,ready ->
                handler.post {
                    if (!closed) {
                        generation++; current=DriveObservation();manager=null;ux=null
                        if (ready) runCatching {
                            manager=vehicle.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                            ux=vehicle.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                        }
                    }
                }
            }
        } catch (_:Exception) { current=DriveObservation() }
        handler.post(poll)
    }
    private val poll:Runnable get() = object:Runnable {
        override fun run() {
            if (closed) return
            val props=manager;val uxManager=ux;val serial=generation
            if (!reading && props!=null && uxManager!=null) {
                reading=true
                worker.execute {
                    val started=System.currentTimeMillis()
                    val result=runCatching {
                        val speed=props.getFloatProperty(VehiclePropertyIds.PERF_VEHICLE_SPEED,0).toDouble()
                        val gear=when(props.getIntProperty(VehiclePropertyIds.GEAR_SELECTION,0)) {
                            VehicleGear.GEAR_PARK -> Gear.PARK
                            VehicleGear.GEAR_DRIVE -> Gear.DRIVE
                            VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
                            VehicleGear.GEAR_REVERSE -> Gear.REVERSE
                            else -> Gear.UNKNOWN
                        }
                        DriveObservation(speed,gear,uxManager.currentCarUxRestrictions?.isRequiresDistractionOptimization,started)
                    }.getOrDefault(DriveObservation())
                    handler.post { reading=false;if (!closed && serial==generation && manager===props) current=result }
                }
            }
            handler.postDelayed(this,1000)
        }
    }
    fun close() { closed=true;generation++;current=DriveObservation();handler.removeCallbacksAndMessages(null);car?.disconnect();worker.shutdownNow() }
}

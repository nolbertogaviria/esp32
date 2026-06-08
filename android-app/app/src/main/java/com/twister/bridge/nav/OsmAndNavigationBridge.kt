package com.twister.bridge.nav

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.twister.bridge.service.TwisterForegroundService
import net.osmand.aidl.navigation.ADirectionInfo

/**
 * Conecta con OsmAnd vía AIDL para recibir actualizaciones de navegación en tiempo real.
 *
 * Usa [IBinder.transact] con el código 66 (posición de registerForNavigationUpdates en
 * IOsmAndAidlInterface, contando desde 1) para evitar copiar los ~70 AIDL stubs del
 * interfaz completo. Sólo se compila IOsmAndAidlCallback (el callback que recibimos).
 *
 * Mapa de TurnType OsmAnd → Twister:
 *   C(0)→STRAIGHT(0), TL/TSLL/TSHL/KL(1-3,9)→LEFT(1), TR/TSLR/TSHR/KR(6-8,10)→RIGHT(2),
 *   TU/TRU(4,5)→UTURN(3), EXIT_L(11)→EXIT_LEFT(4), EXIT_R(12)→EXIT_RIGHT(5),
 *   roundabout(<0)→RIGHT(2) por defecto.
 */
class OsmAndNavigationBridge(private val context: Context) {

    companion object {
        private const val TAG = "OsmAndBridge"

        private const val PKG_FREE = "net.osmand"
        private const val PKG_PLUS = "net.osmand.plus"

        /** Acción del servicio AIDL de OsmAnd. */
        private const val AIDL_SERVICE_ACTION = "net.osmand.aidl.OsmandAidlServiceV2"

        /** Descriptor del interfaz (necesario para writeInterfaceToken). */
        private const val DESCRIPTOR = "net.osmand.aidl.IOsmAndAidlInterface"

        /** Descriptor/códigos del callback de OsmAnd (IOsmAndAidlCallback). */
        private const val CALLBACK_DESCRIPTOR = "net.osmand.aidl.IOsmAndAidlCallback"
        private const val CB_UPDATE_NAVIGATION_INFO = 5

        /**
         * Código de transacción de registerForNavigationUpdates.
         * Es el método #66 en IOsmAndAidlInterface (contando addMapMarker=1 … copyFile=65).
         */
        private const val TRANSACTION_REGISTER_NAV = 66

        // OsmAnd TurnType integers (TurnType.java)
        private const val TT_C      = 0
        private const val TT_TL     = 1
        private const val TT_TSLL   = 2
        private const val TT_TSHL   = 3
        private const val TT_TU     = 4
        private const val TT_TRU    = 5
        private const val TT_TSHR   = 6
        private const val TT_TSLR   = 7
        private const val TT_TR     = 8
        private const val TT_KL     = 9
        private const val TT_KR     = 10
        private const val TT_EXIT_L = 11
        private const val TT_EXIT_R = 12

        /**
         * Convierte el entero turnType de OsmAnd a la dirección Twister.
         * Twister: STRAIGHT=0, LEFT=1, RIGHT=2, UTURN=3, EXIT_LEFT=4, EXIT_RIGHT=5.
         */
        fun osmandTurnToTwister(turnType: Int): Int = when (turnType) {
            TT_C                              -> 0  // STRAIGHT
            TT_TL, TT_TSLL, TT_TSHL, TT_KL  -> 1  // LEFT
            TT_TR, TT_TSLR, TT_TSHR, TT_KR  -> 2  // RIGHT
            TT_TU, TT_TRU                    -> 3  // UTURN
            TT_EXIT_L                        -> 4  // EXIT_LEFT
            TT_EXIT_R                        -> 5  // EXIT_RIGHT
            else -> if (turnType < 0) 2 else 0     // rotonda(neg)→RIGHT, desconocido→STRAIGHT
        }

        /** true cuando la conexión AIDL está activa. Leído por TwisterNotificationListener. */
        @Volatile var active = false
    }

    val isConnected: Boolean get() = osmandBinder != null

    private var osmandBinder: IBinder? = null
    private var navCallbackId: Long = -1L
    private var boundPackage: String? = null

    private val callbackImpl = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply?.writeString(CALLBACK_DESCRIPTOR)
                return true
            }
            if (code in IBinder.FIRST_CALL_TRANSACTION..IBinder.LAST_CALL_TRANSACTION) {
                data.enforceInterface(CALLBACK_DESCRIPTOR)
            }
            if (code == CB_UPDATE_NAVIGATION_INFO) {
                val directionInfo = if (data.readInt() != 0) ADirectionInfo.CREATOR.createFromParcel(data) else null
                handleNavigationUpdate(directionInfo)
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private fun handleNavigationUpdate(directionInfo: ADirectionInfo?) {
        directionInfo ?: return
        val twisterDir = osmandTurnToTwister(directionInfo.turnType)
        val distanceM  = directionInfo.distanceTo
        val dirName    = when (twisterDir) {
            0 -> "STRAIGHT"; 1 -> "LEFT"; 2 -> "RIGHT"; 3 -> "UTURN"
            4 -> "EXIT_LEFT"; 5 -> "EXIT_RIGHT"; else -> "?"
        }
        Log.d(TAG, "navUpdate: osmTurn=${directionInfo.turnType} -> dir=$twisterDir($dirName) dist=${distanceM}m")
        OsmAndInstructionLog.append(
            context, "AIDL",
            "turnType=${directionInfo.turnType} distanceTo=${directionInfo.distanceTo}m" +
            " isLeftSide=${directionInfo.isLeftSide} → $twisterDir($dirName)"
        )
        val intent = Intent(TwisterForegroundService.ACTION_FORWARD_TURN)
            .setPackage(context.packageName)
            .putExtra(TwisterForegroundService.EXTRA_TURN_DIR,  twisterDir)
            .putExtra(TwisterForegroundService.EXTRA_TURN_DIST, distanceM)
            .putExtra(TwisterForegroundService.EXTRA_TURN_ROAD, "")
        context.sendBroadcast(intent)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.i(TAG, "OsmAnd conectado: ${name.packageName}")
            Log.i(TAG, "Log de instrucciones → ${OsmAndInstructionLog.path(context)}")
            osmandBinder = service
            val ok = subscribeToNavUpdates(subscribe = true)
            active = ok
            if (!ok) Log.w(TAG, "Suscripción AIDL falló — usando fallback de notificaciones")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, "OsmAnd desconectado")
            osmandBinder = null
            navCallbackId = -1L
            active = false
        }
    }

    /** Intenta enlazarse con OsmAnd free (net.osmand) y después con OsmAnd+ (net.osmand.plus). */
    fun connect() {
        if (osmandBinder != null) return
        for (pkg in listOf(PKG_FREE, PKG_PLUS)) {
            val intent = Intent(AIDL_SERVICE_ACTION).setPackage(pkg)
            try {
                val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                if (bound) {
                    boundPackage = pkg
                    Log.i(TAG, "bindService ok → $pkg")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "bindService fallo para $pkg: ${e.message}")
            }
        }
        Log.w(TAG, "OsmAnd no instalado o no disponible")
    }

    /** Cancela la suscripción y desenlaza el servicio. */
    fun disconnect() {
        if (osmandBinder != null) {
            subscribeToNavUpdates(subscribe = false)
        }
        try { context.unbindService(serviceConnection) } catch (_: Exception) {}
        osmandBinder = null
        navCallbackId = -1L
        boundPackage = null
        active = false
    }

    /**
     * Llama a registerForNavigationUpdates via transact directo (código 66).
     *
     * Formato del Parcel (según AIDL generado para IOsmAndAidlInterface):
     *   writeInterfaceToken(DESCRIPTOR)
     *   writeInt(1)                         ← marcador non-null para ANavigationUpdateParams
     *   writeLong(callbackId)               ← -1 para nueva suscripción; id previo para cancelar
     *   writeByte(subscribeToUpdates)       ← 1=suscribir, 0=cancelar
     *   writeStrongBinder(callback)
     * Respuesta: readException() + readLong() ← nuevo callbackId devuelto por OsmAnd
     *
     * @return true si la suscripción fue confirmada (callbackId >= 0), false en caso de error
     */
    private fun subscribeToNavUpdates(subscribe: Boolean): Boolean {
        val binder = osmandBinder ?: return false
        val data  = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            // ANavigationUpdateParams (Parcelable): marcador non-null + campos
            data.writeInt(1)
            data.writeLong(if (subscribe) -1L else navCallbackId)
            data.writeByte(if (subscribe) 1 else 0)
            // Callback binder
            data.writeStrongBinder(callbackImpl)
            binder.transact(TRANSACTION_REGISTER_NAV, data, reply, 0)
            reply.readException()
            if (subscribe) {
                navCallbackId = reply.readLong()
                Log.i(TAG, "Suscripción de navegación ok, callbackId=$navCallbackId")
                navCallbackId >= 0
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "transact registerForNavigationUpdates falló: ${e.message}")
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}

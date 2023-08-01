package github.umer0586.sensorserver.websocketserver

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.*
import android.util.Log
import github.umer0586.sensorserver.customextensions.getSensorFromStringType
import github.umer0586.sensorserver.util.JsonUtil
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.*

data class ServerInfo(val ipAddress: String, val port: Int)
class GPS


class SensorWebSocketServer(private val context: Context, address: InetSocketAddress) :
    WebSocketServer(address), SensorEventListener, LocationListener
{

    var samplingRate = 200000 //default value normal rate

    private var handlerThread: HandlerThread = HandlerThread("Handler Thread")
    private lateinit var handler: Handler

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    //To track the list of sensors registered
    private val registeredSensors = mutableListOf<Sensor>()

    //Callbacks
    var onStartListener: ((ServerInfo) -> Unit)? = null
    var onStopListener: (() -> Unit)? = null
    var onErrorListener: ((Exception?) -> Unit)? = null
    var connectionsChangeListener: ((List<WebSocket>) -> Unit)? = null

    private var serverStartUpFailed = false

    var isRunning = false
        private set

    companion object
    {

        private val TAG: String = SensorWebSocketServer::class.java.getName()
        private const val CONNECTION_PATH_SINGLE_SENSOR = "/sensor/connect"
        private const val CONNECTION_PATH_MULTIPLE_SENSORS = "/sensors/connect"
        private const val CONNECTION_PATH_GPS = "/gps"
        private val response = mutableMapOf<String,Any>()

        //websocket close codes ranging 4000 - 4999 are for application's custom messages
        const val CLOSE_CODE_SENSOR_NOT_FOUND = 4001
        const val CLOSE_CODE_UNSUPPORTED_REQUEST = 4002
        const val CLOSE_CODE_PARAMETER_MISSING = 4003
        const val CLOSE_CODE_SERVER_STOPPED = 4004
        const val CLOSE_CODE_CONNECTION_CLOSED_BY_APP_USER = 4005
        const val CLOSE_CODE_INVALID_JSON_ARRAY = 4006
        const val CLOSE_CODE_TOO_FEW_SENSORS = 4007
        const val CLOSE_CODE_NO_SENSOR_SPECIFIED = 4008
        const val CLOSE_CODE_PERMISSION_DENIED = 4009

    }

    override fun onOpen(clientWebsocket: WebSocket, handshake: ClientHandshake)
    {
        Log.i(TAG, "New connection" + clientWebsocket.remoteSocketAddress + " Resource descriptor : " + clientWebsocket.resourceDescriptor)

        //Parse uri so that we can read parameters from query
        val requestUri = Uri.parse(clientWebsocket.resourceDescriptor)

        requestUri.let { uri ->

            uri.path?.lowercase().let { path ->

                when (path)
                {
                    CONNECTION_PATH_SINGLE_SENSOR -> handleSingleSensorRequest(uri, clientWebsocket)
                    CONNECTION_PATH_MULTIPLE_SENSORS -> handleMultiSensorRequest(uri, clientWebsocket)

                    // TODO : handleGPSRequest(websocket) never gets called when app has no location permission
                    CONNECTION_PATH_GPS -> handleGPSRequest(clientWebsocket)
                    else -> clientWebsocket.close(CLOSE_CODE_UNSUPPORTED_REQUEST, "unsupported request")

                }

            }
        }


    }

    /**
     * Helper method to handle multiple sensor request on single websocket connection
     * this method is used in onOpen() method
     */
    private fun handleMultiSensorRequest(uri: Uri, clientWebsocket: WebSocket)
    {
        if (uri.getQueryParameter("types") == null)
        {
            clientWebsocket.close(CLOSE_CODE_PARAMETER_MISSING, "<Types> parameter required")
            return
        }
        val requestedSensorTypes = JsonUtil.readJSONArray(uri.getQueryParameter("types"))

        when {
            requestedSensorTypes == null -> {
                clientWebsocket.close( CLOSE_CODE_INVALID_JSON_ARRAY,"Syntax error : " + uri.getQueryParameter("types") + " is not valid JSON array" )
                return
            }
            requestedSensorTypes.size == 1 -> {
                clientWebsocket.close( CLOSE_CODE_TOO_FEW_SENSORS,"At least two sensor types must be specified" )
                return
            }
            requestedSensorTypes.isEmpty() -> {
                clientWebsocket.close(CLOSE_CODE_NO_SENSOR_SPECIFIED, " No sensor specified")
                return
            }

        }

        Log.i(TAG, "requested sensors : $requestedSensorTypes")

        val requestedSensorList = mutableListOf<Sensor>()

        requestedSensorTypes?.forEach { requestedSensorType ->

            val sensor = sensorManager.getSensorFromStringType(requestedSensorType)
            if (sensor == null)
            {
                clientWebsocket.close(CLOSE_CODE_SENSOR_NOT_FOUND,"sensor of type $requestedSensorType not found" )
                requestedSensorList.clear()
                return
            }
            requestedSensorList.add(sensor)
        }

        registerMultipleSensors(requestedSensorList, clientWebsocket)
    }

    /**
     * Helper method to handle single sensor request on single websocket connection
     * this method is used in onOpen() method
     */
    private fun handleSingleSensorRequest(uri: Uri, clientWebsocket: WebSocket)
    {
        var paramType = uri.getQueryParameter("type")

        when {

            //if type param doesn't exit in the query
            paramType == null -> {

                clientWebsocket.close(CLOSE_CODE_PARAMETER_MISSING, "<type> param required")
                return
            }

            paramType.isEmpty() -> {

                clientWebsocket.close(CLOSE_CODE_NO_SENSOR_SPECIFIED, "No sensor specified")
                return

            }

            else ->{
                paramType = paramType.lowercase(Locale.getDefault())
            }

        }



        // sensorManager.getSensorFromStringType(String) returns null when invalid sensor type is passed or when sensor type is not supported by the device
        val requestedSensor = sensorManager.getSensorFromStringType(paramType)

        //If client has requested invalid or unsupported sensor
        // then close client Websocket connection and return ( i-e do not proceed further)
        if (requestedSensor == null)
        {
            clientWebsocket.close(CLOSE_CODE_SENSOR_NOT_FOUND,"Sensor of type " + uri.getQueryParameter("type") + " not found" )
            return
        }

        //At this point paramType is valid (e.g android.sensor.light etc..)
        registerSensor(requestedSensor, clientWebsocket)
        notifyConnectionsChanged()
    }

    // Helper method used in handleMultiSensorRequest()
    private fun registerMultipleSensors(sensors: List<Sensor>, clientWebsocket: WebSocket)
    {

        // For new requesting client, attach a tag of requested sensor type with client
        clientWebsocket.setAttachment(sensors)

        for (sensor in sensors)
        {

            /*
            If this WebSocket Server has already registered itself for some type of sensor (e.g android.sensor.light),
            then we don't have to registered this Server for the same sensor again
            */
            if (registeredSensors.contains(sensor))
            {

                // Log the sensor type and that it is already registered
                Log.i(TAG, "Sensor ${sensor.stringType} already registered, skipping registration")

                // Update registry
                registeredSensors.add(sensor)
                notifyConnectionsChanged()

                // No need to call sensorManager.registerListener()
                return
            }


            // Register the requested sensor
            // Sensor events will be reported to the main thread if a handler is not provided
            sensorManager.registerListener(this, sensor, samplingRate, handler)

            // Update registry
            registeredSensors.add(sensor)
            notifyConnectionsChanged()

        }
    }


    // Helper method used in  handleSingleSensorRequest()
    private fun registerSensor(requestedSensor: Sensor, clientWebsocket: WebSocket) {

        // Attach info with newly connected client
        // so that this Servers knows which client has requested which type of sensor
        clientWebsocket.setAttachment(requestedSensor)

        // if this WebSocket Server has already registered itself for some type of sensor (e.g android.sensor.light)
        // then we don't have to registered this Server for the same sensor again
        if (registeredSensors.contains(requestedSensor))
        {

            // Log the sensor type and that it is already registered
            Log.i(TAG, "Sensor ${requestedSensor.stringType} already registered, skipping registration")

            // Update registry
            registeredSensors.add(requestedSensor)
            notifyConnectionsChanged()

            // No need to call sensorManager.registerListener()
            return
        }

            // Register the requested sensor
            // Sensor events will be reported to the main thread if a handler is not provided
            sensorManager.registerListener(this, requestedSensor, samplingRate, handler)

            // TODO:
            // Android official docs say (https://developer.android.com/reference/android/hardware/SensorManager)
            // "Note: Don't use this method (registerListener) with a one shot trigger sensor such as Sensor#TYPE_SIGNIFICANT_MOTION.
            // Use requestTriggerSensor(android.hardware.TriggerEventListener, android.hardware.Sensor) instead."

            // Update registry
            registeredSensors.add(requestedSensor)
            notifyConnectionsChanged()

    }



    @SuppressLint("MissingPermission")
    private fun handleGPSRequest(clientWebsocket: WebSocket)
    {
        if (!hasLocationPermission())
        {
            clientWebsocket.close(
                CLOSE_CODE_PERMISSION_DENIED,
                "App has No permission to access location. Go to your device's installed apps settings and allow location permission to Sensor Server app"
            )
            return
        }


        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            0,
            0f,
            this,
            handlerThread.looper
        )

        clientWebsocket.setAttachment( GPS() )



        notifyConnectionsChanged()
    }

    private fun getGPSConnectionCount() : Int
    {
        return connections.filter{
            it.getAttachment<Any>() is GPS
        }.size
    }

    override fun onLocationChanged(location: Location)
    {
        if (getGPSConnectionCount() > 0)
        {
            Log.w( TAG, "onLocationChanged() :  " + "Location update received when no client with connected with GPS" )
        }
        for (websocket in connections)
        {
            if (websocket.getAttachment<Any>() is GPS)
            {
                response.clear()
                response["longitude"] = location.longitude
                response["latitude"] = location.latitude
                response["altitude"] = location.altitude
                response["bearing"] = location.bearing
                response["accuracy"] = location.accuracy
                response["speed"] = location.speed
                response["time"] = location.time


                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                {
                    response["speedAccuracyMetersPerSecond"] = location.speedAccuracyMetersPerSecond
                    response["bearingAccuracyDegrees"] = location.bearingAccuracyDegrees
                    response["elapsedRealtimeNanos"] = location.elapsedRealtimeNanos
                    response["verticalAccuracyMeters"] = location.verticalAccuracyMeters
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                {
                    response["elapsedRealtimeAgeMillis"] = location.elapsedRealtimeAgeMillis
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                {
                    response["elapsedRealtimeUncertaintyNanos"] = location.elapsedRealtimeUncertaintyNanos
                }

                websocket.send( JsonUtil.toJSON(response) )
            }
        }
    }

    override fun onProviderDisabled(provider: String)
    {
        Log.i(TAG, "onProviderDisabled() $provider")
    }

    override fun onProviderEnabled(provider: String)
    {
        Log.i(TAG, "onProviderEnabled() $provider")
    }

    private fun hasLocationPermission(): Boolean
    {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        else true

        //prior to android marshmallow dangerous permission are prompt at install time
    }

    override fun onClose(clientWebsocket: WebSocket, code: Int, reason: String, remote: Boolean)
    {
        Log.i(TAG,"Connection closed ${clientWebsocket.remoteSocketAddress}  with exit code  $code  additional info: $reason")

        if (clientWebsocket.getAttachment<Any>() is Sensor)
        {
            // Get sensor type of recently closed client
            val sensor = clientWebsocket.getAttachment<Sensor>()
            unregisterSensor(sensor)
        }
        else if (clientWebsocket.getAttachment<Any>() is ArrayList<*>)
        {
            val sensors = clientWebsocket.getAttachment<List<Sensor>>()
            for (sensor in sensors)
                unregisterSensor(sensor)
        }
        else if (clientWebsocket.getAttachment<Any>() is GPS)
        {

            // unregister this server for location updates from GPS ...
            // when there are no clients associated with GPS
            if (getGPSConnectionCount() == 0)
                locationManager.removeUpdates( this )
        }
        notifyConnectionsChanged()
    }

    // This method is used in OnClose()
    private fun unregisterSensor(sensor: Sensor)
    {

        // When client has closed connection, how many clients receiving same sensor data from this server
        val sensorConnectionCount = getSensorConnectionCount(sensor).toLong()
        Log.i(TAG, "Sensor : " + sensor.stringType + " Connections : " + sensorConnectionCount)

        /*
            Suppose we have 3 clients each receiving light sensor data \
            if we unregister this server for light sensor when only one client is disconnected \
            then 2 other connected client won't receive light sensor data anymore

        */

        //  Unregister sensor if and only if one client is using it
        if (sensorConnectionCount == 1L)
            sensorManager.unregisterListener(this, sensor)

        registeredSensors.remove(sensor)
        Log.i(TAG, "Total Connections : " + getConnectionCount())

        notifyConnectionsChanged()
    }

    override fun onMessage(conn: WebSocket, message: String)
    {
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer)
    {
    }
    // following doc taken from original source
    /**
     * Called when errors occurs. If an error causes the websocket connection to fail [.onClose] will be called additionally.<br></br>
     * This method will be called primarily because of IO or protocol errors.<br></br>
     * If the given exception is an RuntimeException that probably means that you encountered a bug.<br></br>
     *
     * @param conn Can be null if there error does not belong to one specific websocket. For example if the servers port could not be bound.
     * @param ex The exception causing this error
     */
    override fun onError(conn: WebSocket?, ex: Exception)
    {
        // error occurred on websocket conn (we don't notify anything to the user about this for now)
        if (conn != null)
            Log.e(TAG, "an error occurred on connection " + conn.remoteSocketAddress)

        // if conn is null than we have error related to server
        if (conn == null)
        {
            /*
                seeing the implementation of onError(conn,ex), this method
                always invokes stop() whether server is running or not,
                So onError() would invoke stop() when some exception like BindException occurs (because of port already in use)
            */

            //    if (serverErrorListener != null) serverErrorListener!!.onServerError(ex) // listener must filter exception by itself

            onErrorListener?.invoke(ex)

            // we will use this in stop() method to check if there was an exception during server startup
            serverStartUpFailed = true

        }
        ex.printStackTrace()
    }

    override fun onStart()
    {

        onStartListener?.invoke(ServerInfo(address.hostName, port))

        isRunning = true
        Log.i(TAG, "server started successfully $address")
        Log.i(TAG, "sampling rate $samplingRate")
    }

    @kotlin.Throws(InterruptedException::class)
    override fun stop()
    {
        closeAllConnections()

        locationManager.removeUpdates(this)

        super.stop()
        Log.d(TAG, "stop() called")

        // if (serverStopListener != null && !serverStartUpFailed) serverStopListener!!.onServerStopped()

        onStopListener?.run {

            if (!serverStartUpFailed)
                invoke()
        }


        if (handlerThread.isAlive)
            handlerThread.quitSafely()

        isRunning = false
    }

    /*
        1. calling webSocketServerObj.run() starts server.
        2. WebSocketServer do not run on a separate thread by default,
           so we need to make sure that we run server on separate thread
     */
    override fun run()
    {
        Thread { super.run() }.start()

        // see https://stackoverflow.com/questions/23209804/android-sensor-registerlistener-in-a-separate-thread

        handlerThread.start()
        handler = Handler(handlerThread.looper)
    }

    override fun onSensorChanged(sensorEvent: SensorEvent)
    {
        // Log.i(TAG, "onSensorChanged: Thread " + Thread.currentThread().getName());
        // Log.i(TAG, "onSensorChanged: Sensor " + sensorEvent.sensor.getStringType());
        if (getConnectionCount() == 0)
            Log.e( TAG," Sensor event reported when no client in connected" )

        response.clear()

        // Loop through each connected client
        for (webSocket in connections)
        {
            // Send data as per sensor type requested by client
            if (webSocket.getAttachment<Any>() is Sensor)
            {
                val clientAssociatedSensor = webSocket.getAttachment<Sensor>()

                if (clientAssociatedSensor != null) if (clientAssociatedSensor.type == sensorEvent.sensor.type && !webSocket.isClosing)
                {
                    response["values"] = sensorEvent.values
                    response["timestamp"] = sensorEvent.timestamp
                    response["accuracy"] = sensorEvent.accuracy
                    webSocket.send(JsonUtil.toJSON(response))
                }
            }
            else if (webSocket.getAttachment<Any>() is ArrayList<*>)
            {
                val clientAssociatedSensors = webSocket.getAttachment<List<Sensor>>()

                for (clientAssociatedSensor in clientAssociatedSensors)
                {
                    if (clientAssociatedSensor.type == sensorEvent.sensor.type && !webSocket.isClosing)
                    {
                        response["values"] = sensorEvent.values
                        response["timestamp"] = sensorEvent.timestamp
                        response["accuracy"] = sensorEvent.accuracy
                        response["type"] = sensorEvent.sensor.stringType
                        webSocket.send(JsonUtil.toJSON(response))
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, i: Int)
    {
    }

    private fun getSensorConnectionCount(sensor: Sensor): Int
    {
        var count = 0
        for (registeredSensor in registeredSensors)
            if (registeredSensor.type == sensor.type)
                count++

        return count
    }

    private fun notifyConnectionsChanged()
    {
        Log.d(TAG, "notifyConnectionsChanged() : " + connections.size)
        connectionsChangeListener?.invoke(ArrayList(connections))
    }

    fun getConnectionCount(): Int
    {
        return connections.size
    }

    fun closeAllConnections()
    {

        connections.forEach { webSocket ->
            webSocket.close(CLOSE_CODE_SERVER_STOPPED, "Server stopped")
        }

    }


}
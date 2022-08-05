/**
 *  MIT License
 *  Copyright 2022 Jonathan Bradshaw (jb@nrgup.net)
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */
import groovy.transform.Field
import hubitat.helper.HexUtils
import com.hubitat.app.ChildDeviceWrapper
import com.hubitat.app.DeviceWrapper
import com.hubitat.app.exception.UnknownDeviceTypeException
import java.util.concurrent.ConcurrentHashMap

metadata {
    definition(name: 'ESPHome Protobuf POC', namespace: 'ESPHome', author: 'Jonathan Bradshaw') {
        capability 'Actuator'
        capability 'Initialize'
        capability 'Refresh'

        attribute 'state', 'enum', [
            'connecting',
            'connected',
            'disconnecting',
            'disconnected'
        ]

        command 'connect'
        command 'disconnect'
    }

    preferences {
        input name: 'ipAddress',
                type: 'text',
                title: 'Device IP Address',
                required: true

        input name: 'portNumber',
                type: 'number',
                title: 'Port Number',
                range: 1..65535,
                defaultValue: '6053',
                required: true

        input name: 'password',
                type: 'text',
                title: 'Device Password (if required)',
                required: false

        input name: 'pingInterval',
                type: 'enum',
                title: 'Device Ping Interval',
                required: true,
                defaultValue: 30,
                options: [
                    15: '15 Seconds',
                    30: '30 Seconds',
                    60: 'Every minute',
                    120: 'Every 2 minutes'
                ]

        input name: 'logEnable',
                type: 'bool',
                title: 'Enable Debug Logging',
                required: false,
                defaultValue: false

        input name: 'logTextEnable',
              type: 'bool',
              title: 'Enable descriptionText logging',
              required: false,
              defaultValue: true
    }
}

/**
 * Hubitat Driver Implementation
 */
public void connect() {
    LOG.info "connecting to ESPHome native API at ${ipAddress}:${portNumber}"
    String state = device.currentValue('state')
    if (state != 'disconnected') {
        closeSocket()
    }
    openSocket()
}

public void disconnect() {
    String state = device.currentValue('state')
    sendEvent name: 'state', value: 'disconnecting'
    if (state == 'connected') {
        LOG.info 'disconnecting from ESPHome device'
        espDisconnectRequest()
        runIn(5, 'closeSocket')
    } else {
        closeSocket()
    }
}

// Called when the device is started.
public void initialize() {
    LOG.info "${device} driver initializing"

    unschedule()        // Remove all scheduled functions
    disconnect()        // Disconnect any existing connection

    // Schedule log disable for 30 minutes
    if (logEnable) {
        runIn(1800, 'logsOff')
    }
}

// Called when the device is first created.
public void installed() {
    LOG.info "${device} driver installed"
}

// Called to disable logging after timeout
public void logsOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
    LOG.info "${device} debug logging disabled"
}

public void parse(Map state) {
    Map entity = getEntity(state.key) + state
    LOG.info "ESPHome received state: ${entity}"
}

public void refresh() {
    LOG.info 'refreshing device entities'
    espListEntitiesRequest()
}

// Socket status updates
public void socketStatus(String message) {
    if (message.contains('error')) {
        log.error "${device} socket ${message}"
        closeSocket()
    } else {
        log.info "${device} socket ${message}"
    }
}

// Called when the device is removed.
void uninstalled() {
    disconnect()
    LOG.info "${device} driver uninstalled"
}

// Called when the settings are updated.
void updated() {
    LOG.info "${device} driver configuration updated"
    LOG.debug settings
    initialize()
}

/**
 * ESPHome API Message Implementation
 * https://github.com/esphome/aioesphomeapi/blob/main/aioesphomeapi/api.proto
 */
@Field static final ConcurrentHashMap<String, Map> entities = new ConcurrentHashMap<>()

private Map getEntity(long key) {
    Map deviceEntities = entities.computeIfAbsent(device.id) { k -> new HashMap() };
    return deviceEntities.get(key) ?: [:]
}

private synchronized void setEntity(Map entity) {
    LOG.debug "setEntity: ${entity}"
    Map deviceEntities = entities.computeIfAbsent(device.id) { k -> new HashMap() };
    deviceEntities.put(entity.key, entity)
}

private void parseMessage(ByteArrayInputStream stream, long length) {
    long msgType = readVarInt(stream, true)
    if (msgType < 1 || msgType > 65) {
        LOG.warn "ESPHome message type ${msgType} out of range, skipping"
        return
    }
    LOG.debug "ESPHome extracting message type ${msgType} (length ${length})"
    Map tags = length == 0 ? [:] : decodeProtobufMessage(stream, length)
    switch (msgType) {
        case 2:
            // Confirmation of successful connection request.
            // Can only be sent by the server and only at the beginning of the connection
            espHelloResponse(tags)
            break
        case 4:
            // Confirmation of successful connection. After this the connection is available for all traffic.
            // Can only be sent by the server and only at the beginning of the connection
            espConnectResponse()
            break
        case 5: // Device requests to close connection
            disconnect()
            break
        case 6: // Device confirms our disconnect request
            // Both parties are required to close the connection after this message has been received.
            closeSocket()
            break
        case 7: // Ping Request (from device)
            espPingResponse()
            break
        case 8: // Ping Response (from device)
            unschedule('timeout')
            break
        case 10: // Device Info Response
            espDeviceInfoResponse(tags)
            break
        case 12: // List Entities Binary Sensor Response
            setEntity espListEntitiesBinarySensorResponse(tags)
            break
        case 13: // List Entities Cover Response
            setEntity espListEntitiesCoverResponse(tags)
            break
        case 14: // List Entities Fan Response
            setEntity espListEntitiesFanResponse(tags)
            break
        case 15: // List Entities Light Response
            setEntity espListEntitiesLightResponse(tags)
            break
        case 16: // List Entities Sensor Response
            setEntity espListEntitiesSensorResponse(tags)
            break
        case 18: // List Entities Text Sensor Response
            setEntity espListEntitiesTextSensorResponse(tags)
            break
        case 19: // List Entities Done Response
            espListEntitiesDoneResponse()
            break
        case 21: // Binary Sensor State Response
            parse espBinarySensorStateResponse(tags)
            break
        case 22: // Cover State Response
            parse espCoverStateResponse(tags)
            break
        case 23: // Fan State Response
            parse espFanStateResponse(tags)
            break
        case 24: // Light State Response
            parse espLightStateResponse(tags)
            break
        case 25: // Sensor State Response
            parse espSensorStateResponse(tags)
            break
        case 26: // Switch State Response
            parse espSwitchStateResponse(tags)
            break
        case 27: // Text Sensor State Response
            parse espTextSensorStateResponse(tags)
            break
        case 29: // Subscribe Logs Response
            espSubscribeLogsResponse()
            break
        case 36: // Get Time Request
            espGetTimeRequest()
            break
        case 49: // List Entities Number Response
            espListEntitiesNumberResponse(tags)
            break
        case 43: // List Entities Camera Response
            espListEntitiesCameraResponse(tags)
            break
        case 44: // Camera Image Response
            espCameraImageResponse(tags)
            break
        case 50: // Number State Response
            parse espNumberStateResponse(tags)
            break
        case 55: // List Entities Siren Response
            espListEntitiesSirenResponse(tags)
            break
        case 56: // Siren State Response
            parse espSirenStateResponse(tags)
            break
        case 58: // List Entities Lock Response
            setEntity espListEntitiesLockResponse(tags)
            break
        case 59: // Lock State Response
            parse espLockStateResponse(tags)
            break
        case 61: // List Entities Button Response
            setEntity espListEntitiesButtonResponse(tags)
            break
        case 63: // List Entities Media Player Response
            setEntity espListEntitiesMediaPlayerResponse(tags)
            break
        case 64: // Media Player State Response
            parse espMediaPlayerStateResponse(tags)
        default:
            LOG.warn "ESPHome message type ${msgType} not suppported"
            break
    }
}

private Map espBinarySensorStateResponse(Map tags) {
    LOG.trace '[R] Binary Sensor State Response'
    return [
        key: getLong(tags, 1),
        state: getBoolean(tags, 2),
        hasState: getBoolean(tags, 3, true)
    ]
}

private void espButtonCommandRequest(Long key) {
    LOG.trace '[W] Button Command Request'
    sendMessage(62, [ 1: (int) key ])
}

private void espCameraImageRequest(Boolean single, Boolean stream) {
    LOG.trace '[W] Camera Image Request'
    sendMessage(45, [
        1: single,
        2: stream
    ])
}

private Map espCameraImageResponse(Map tags) {
    LOG.trace '[R] Camera Image Response'
    return [
        key: getLong(tags, 1),
        image: tags[2],
        done: getBoolean(tags, 3)
    ]
}

private void espConnectRequest(String password) {
    // Message sent at the beginning of each connection to authenticate the client
    // Can only be sent by the client and only at the beginning of the connection
    LOG.trace '[S] Connect Request'
    sendMessage(3, [ 1: password ])
}

private void espConnectResponse() {
    // todo: check for invalid password
    LOG.trace '[R] Connect Response'
    sendEvent name: 'state', value: 'connected'

    // Step 3: Send Device Info Request
    espDeviceInfoRequest()
}

private void espCoverCommandRequest(Long key, Float position, Float tilt, Boolean stop) {
    LOG.trace '[S] Cover Command Request'
    sendMessage(30, [
        1: (int) key,
        4: position != null,
        5: position,
        6: tilt != null,
        7: tilt,
        8: stop
    ])
}

private Map espCoverStateResponse(Map tags) {
    LOG.trace '[R] Cover State Response'
    return [
        key: getLong(tags, 1),
        legacyState: getInt(tags, 2), // legacy: state has been removed in 1.13
        position: getFloat(tags, 3),
        tilt: getFloat(tags, 4),
        currentOperation: getInt(tags, 5)
    ]
}

private void espDeviceInfoRequest() {
    LOG.trace '[S] Device Info Request'
    sendMessage(9)
}

private void espDeviceInfoResponse(Map tags) {
    LOG.trace '[R] Device Info Response'
    if (tags.containsKey(2)) {
        device.name = getString(tags, 2)
    }
    if (tags.containsKey(3)) {
        device.updateDataValue 'MAC Address', getString(tags, 3)
    }
    if (tags.containsKey(4)) {
        device.updateDataValue 'ESPHome Version', getString(tags, 4)
    }
    if (tags.containsKey(5)) {
        device.updateDataValue 'Compile Time', getString(tags, 5)
    }
    if (tags.containsKey(6)) {
        device.updateDataValue 'Board Model', getString(tags, 6)
    }
    if (tags.containsKey(8)) {
        device.updateDataValue 'Project Name', getString(tags, 8)
    }
    if (tags.containsKey(9)) {
        device.updateDataValue 'Project Version', getString(tags, 9)
    }
    if (tags.containsKey(10)) {
        device.updateDataValue 'Web Server', "http://${ipAddress}:${tags[10]}"
    }

    // Step 4: Get device entities
    espListEntitiesRequest()
}

private void espDisconnectRequest() {
    // Request to close the connection.
    // Can be sent by both the client and server
    LOG.trace '[S] Disconnect Request'
    sendMessage(5)
}

private void espFanCommandRequest(Long key, Boolean state, Boolean oscillating, Integer direction, Integer speedLevel) {
    LOG.trace '[S] Fan Command Request'
    sendMessage(31, [
        1: (int) key,
        2: state != null,
        3: state,
        6: oscillating != null,
        7: oscillating,
        8: direction != null,
        9: direction,
        10: speedLevel != null,
        11: speedLevel
    ])
}

private Map espFanStateResponse(Map tags) {
    LOG.trace '[R] Fan State Response'
    return [
        key: getLong(tags, 1),
        state: getBoolean(tags, 2),
        oscillating: getBoolean(tags, 3),
        speed: getInt(tags, 4), // deprecated
        direction: getInt(tags, 5),
        speedLevel: getInt(tags, 6)
    ]
}

private void espGetTimeRequest() {
    LOG.trace '[S] Get Time Request'
    sendMessage(37, [ 1: (int) (new Date().getTime() / 1000) ])
}

private void espHelloRequest() {
    // Step 1: Send the HelloRequest message
    // Can only be sent by the client and only at the beginning of the connection
    LOG.trace '[S] Hello Request'
    String client = "Hubitat ${location.hub.name}"
    sendMessage(1, [ 1: client ])
}

private void espHelloResponse(Map tags) {
    // Confirmation of successful connection request.
    // Can only be sent by the server and only at the beginning of the connection
    LOG.trace '[R] Hello Response'
    if (tags.containsKey(1) && tags.containsKey(2)) {
        String version = tags[1] + '.' + tags[2]
        LOG.info "ESPHome API version: ${version}"
        device.updateDataValue 'API Version', version
        if (tags[1] > 1) {
            LOG.error 'ESPHome API version > 1 not supported - disconnecting'
            closeSocket()
            return
        }
    }

    String info = getString(tags, 3)
    if (info) {
        LOG.info "ESPHome server info: ${info}"
        device.updateDataValue 'Server Info', info
    }

    String name = getString(tags, 4)
    if (name) {
        LOG.info "ESPHome device name: ${name}"
        device.name = name
    }

    // Step 2: Send the ConnectRequest message
    espConnectRequest(settings.password)
}

private void espLightCommandRequest(Long key, Boolean state, Float masterBrightness, Integer colorMode, Float colorBrightness,
        Float red, Float green, Float blue, Float white, Float colorTemperature, Float coldWhite, Float warmWhite, 
        Integer transitionLength, Boolean flashLength, String effect, Boolean effectSpeed) {
    LOG.trace '[S] Light Command Request'
    sendMessage(32, [
        1: (int) key,
        2: state != null,
        3: state,
        4: masterBrightness != null,
        5: masterBrightness,
        6: red != null && blue != null && green != null,
        7: red,
        8: green,
        9: blue,
        10: white != null,
        11: white,
        12: colorTemperature != null,
        13: colorTemperature,
        14: transitionLength != null,
        15: transitionLength,
        16: flashLength != null,
        17: flashLength,
        18: effect != null,
        19: effect,
        20: colorBrightness != null,
        21: colorBrightness,
        22: colorMode != null,
        23: colorMode
    ])
}

private Map espLightStateResponse(Map tags) {
    LOG.trace '[R] Light State Response'
    return [
        key: getLong(tags, 1),
        state: getBoolean(tags, 2),
        brightness: getFloat(tags, 3),
        colorMode: getInt(tags, 11),
        colorBrightness: getFloat(tags, 10),
        red: getFloat(tags, 4),
        green: getFloat(tags, 5),
        blue: getFloat(tags, 6),
        white: getFloat(tags, 7),
        colorTemperature: getFloat(tags, 8),
        coldWhite: getFloat(tags, 12),
        warmWhite: getFloat(tags, 13),
        effect: getString(tags, 9)
    ]
}

private void espListEntitiesRequest() {
    LOG.trace '[S] List Entities Request'
    sendMessage(11)
}

private Map espListEntitiesBinarySensorResponse(Map tags) {
    LOG.trace '[R] List Entities Binary Sensor Response'
    return parseEntity(tags) + [
        isStatusBinarySensor: getBoolean(tags, 6),
        disabledByDefault: getBoolean(tags, 7),
        icon: getString(tags, 8),
        entityCategory: getInt(tags, 9)
    ]
}

private Map espListEntitiesButtonResponse(Map tags) {
    LOG.trace '[R] List Entities Button Response'
    return parseEntity(tags) + [
        icon: getString(tags, 5),
        disabledByDefault: getBoolean(tags, 6),
        entityCategory: getInt(tags, 7),
        deviceClass: getString(tags, 8)
    ]
}

private Map espListEntitiesCameraResponse(Map tags) {
    LOG.trace '[R] List Entities Camera Response'
    return parseEntity(tags) + [
        disabledByDefault: getBoolean(tags, 5),
        icon: getString(tags, 6),
        entityCategory: getInt(tags, 7)
    ]
}

private Map espListEntitiesCoverResponse(Map tags) {
    LOG.trace '[R] List Entities Cover Response'
    return parseEntity(tags) + [
        assumedState: getBoolean(tags, 5),
        supportsPosition: getBoolean(tags, 6),
        supportsTilt: getBoolean(tags, 7),
        deviceClass: getString(tags, 8),
        disabledByDefault: getBoolean(tags, 9),
        icon: getString(tags, 10),
        entityCategory: getInt(tags, 11)
    ]
}

private Map espListEntitiesLockResponse(Map tags) {
    LOG.trace '[R] List Entities Lock Response'
    return parseEntity(tags) + [
        icon: getString(tags, 5),
        disabledByDefault: getBoolean(tags, 6),
        entityCategory: getInt(tags, 7),
        assumedState: getBoolean(tags, 8),
        supportsOpen: getBoolean(tags, 9),
        requiresCode: getBoolean(tags, 10),
        codeFormat: getString(tags, 11)
    ]
}

private Map espListEntitiesFanResponse(Map tags) {
    LOG.trace '[R] List Entities Fan Response'
    return parseEntity(tags) + [
        supportsOscillation: getBoolean(tags, 5),
        supportsSpeed: getBoolean(tags, 6),
        supportsDirection: getBoolean(tags, 7),
        supportedSpeedLevels: getInt(tags, 8),
        disabledByDefault: getBoolean(tags, 9),
        icon: getString(tags, 10),
        entityCategory: getInt(tags, 11)
    ]
}

private Map espListEntitiesLightResponse(Map tags) {
    LOG.trace '[R] List Entities Light Response'
    return parseEntity(tags) + [
        supportedColorModes: tags[12],
        minMireds: getInt(tags, 9),
        maxMireds: getInt(tags, 10),
        effects: getString(tags, 11),
        disabledByDefault: getBoolean(tags, 13),
        icon: getString(tags, 14),
        entityCategory: getInt(tags, 15)
    ]
}

private Map espListEntitiesMediaPlayerResponse(Map tags) {
    LOG.trace '[R] List Entities Media Player Response'
    return parseEntity(tags) + [
        icon: getString(tags, 5),
        disabledByDefault: getBoolean(tags, 6),
        entityCategory: getInt(tags, 7)
    ]
}

private Map espListEntitiesNumberResponse(Map tags) {
    LOG.trace '[R] List Entities Number Response'
    return parseEntity(tags) + [
        icon: getString(tags, 5),
        minValue: getFloat(tags, 6),
        maxValue: getFloat(tags, 7),
        step: getFloat(tags, 8),
        disabledByDefault: getBoolean(tags, 9),
        entityCategory: getInt(tags, 10),
        unitOfMeasurement: getString(tags, 11),
        numberMode: getInt(tags, 12)
    ]
}

private Map espListEntitiesSensorResponse(Map tags) {
    LOG.trace '[R] List Entities Sensor Response'
    return parseEntity(tags) + [
        icon: getString(tags, 5),
        unitOfMeasurement: getString(tags, 6),
        accuracyDecimals: getInt(tags, 7),
        forceUpdate: getBoolean(tags, 8),
        deviceClass: getString(tags, 9),
        sensorStateClass: getInt(tags, 10),
        lastResetType: getInt(tags, 11),
        disabledByDefault: getBoolean(tags, 12),
        entityCategory: getInt(tags, 13)
    ]
}

private Map espListEntitiesSirenResponse(Map tags) {
    LOG.trace '[R] List Entities Siren Response'
    return parseEntity(tags) + [
        icon: getString(tags, 5),
        disabledByDefault: getBoolean(tags, 6),
        // TODO repeated string: tones: getString(tags, 7),
        supportsDuration: getBoolean(tags, 8),
        supportsVolume: getBoolean(tags, 9),
        entityCategory: getInt(tags, 10)
    ]
}

private Map espListEntitiesTextSensorResponse(Map tags) {
    LOG.trace '[R] List Entities Text Sensor Response'
    return parseEntity(tags) + [
        icon: getString(tags, 5),
        disabledByDefault: getBoolean(tags, 6),
        entityCategory: getInt(tags, 7)
    ]
}

private void espListEntitiesDoneResponse() {
    LOG.trace '[R] List Entities Done Response'
    LOG.debug entities.get(device.id) ?: 'No entities found'
    schedulePing()
    espSubscribeLogsRequest(settings.logEnable ? LOG_LEVEL_DEBUG : LOG_LEVEL_INFO)
    espSubscribeStatesRequest()
}

private void espLockCommandRequest(Long key, Integer lockCommand, String code) {
    LOG.trace '[S] Lock Command Request'
    sendMessage(60, [
        1: (int) key,
        2: lockCommand,
        3: code != null,
        4: code
    ])
}

private Map espLockStateResponse(Map tags) {
    LOG.trace '[R] Lock State Response'
    return [
        key: getLong(tags, 1),
        state: getInt(tags, 2),
    ]
}

private void espMediaPlayerCommandRequest(Long key, Integer mediaPlayerCommand, Float volume, String mediaUrl) {
    LOG.trace '[S] Media Player Command Request'
    sendMessage(65, [
        1: (int) key,
        2: mediaPlayerCommand != null,
        3: mediaPlayerCommand,
        4: volume != null,
        5: volume,
        6: mediaUrl != null,
        7: mediaUrl
    ])
}

private Map espMediaPlayerStateResponse(Map tags) {
    return [
        key: getLong(tags, 1),
        state: getInt(tags, 2),
        volume: getFloat(tags, 3),
        muted: getBoolean(tags, 4)
    ]
}

private void espNumberCommandRequest(Long key, Float state) {
    LOG.trace '[S] Number Command Request'
    sendMessage(51, [ 1: (int) key, 2: state ])
}

private Map espNumberStateResponse(Map tags) {
    LOG.trace '[R] Number State Response'
    return [
        key: getLong(tags, 1),
        state: getFloat(tags, 2),
        hasState: getBoolean(tags, 3, true)
    ]
}

private void espPingRequest() {
    // Ping request can be sent by either party
    LOG.trace '[S] Ping Request'
    sendMessage(7)
    schedulePing()
}

private void espPingResponse() {
    // Ping response can be sent by either party
    LOG.trace '[S] Ping Response'
    sendMessage(8)
}

private Map espSensorStateResponse(Map tags) {
    LOG.trace '[R] Sensor State Response'
    return [
        key: getLong(tags, 1),
        state: getFloat(tags, 2),
        hasState: getBoolean(tags, 3, true)
    ]
}

private void espSirenCommandRequest(Long key, Boolean state, String tone, Integer duration, Float volume) {
    LOG.trace '[S] Siren Command Request'
    sendMessage(57, [
        1: (int) key,
        2: state != null,
        3: state,
        4: tone != null,
        5: tone,
        6: duration != null,
        7: duration,
        8: volume != null,
        9: volume
    ])
}

private Map espSirenStateResponse(Map tags) {
    LOG.trace '[R] Siren State Response'
    return [
        key: getLong(tags, 1),
        state: getInt(tags, 2)
    ]
}

private void espSwitchCommandRequest(Long key, Boolean state) {
    LOG.trace '[S] Fan Command Request'
    sendMessage(33, [
        1: (int) key,
        2: state
    ])
}

private Map espSwitchStateResponse(Map tags) {
    LOG.trace '[R] Switch State Response'
    return [
        key: getLong(tags, 1),
        state: getBoolean(tags, 2)
    ]
}

private void espSubscribeLogsRequest(Integer logLevel, Boolean dumpConfig) {
    LOG.trace '[S] Subscribe Logs Request'
    sendMessage(28, [
        1: logLevel,
        2: dumpConfig
    ])
}

private void espSubscribeLogsResponse(Map tags) {
    LOG.trace '[R] Subscribe Logs Response'
    String message = getString(tags, 3)
    switch (getInt(tags, 1)) {
        case LOG_LEVEL_ERROR:
            LOG.error message
            break
        case LOG_LEVEL_WARN:
            LOG.warn message
            break
        case LOG_LEVEL_INFO:
            LOG.info message
            break
        case LOG_LEVEL_VERY_VERBOSE:
            LOG.trace message
        default:
            LOG.debug message
            break
    }
}

private void espSubscribeStatesRequest() {
    LOG.trace '[S] Subscribe States Request'
    sendMessage(20)
}

private Map espTextSensorStateResponse(Map tags) {
    LOG.trace '[R] Text Sensor State Response'
    return [
        key: getLong(tags, 1),
        state: getString(tags, 2),
        hasState: getBoolean(tags, 3, true)
    ]
}


/**
 * ESPHome Native API Plaintext Socket IO Implementation
 */
private synchronized void closeSocket() {
    unschedule('closeSocket')
    unschedule('apiPingRequest')
    LOG.info "ESPHome closing socket to ${ipAddress}:${portNumber}"
    sendEvent name: 'state', value: 'disconnected'
    interfaces.rawSocket.disconnect()
}

private synchronized void openSocket() {
    sendEvent name: 'state', value: 'connecting'
    try {
        interfaces.rawSocket.connect(settings.ipAddress, (int) settings.portNumber, byteInterface: true)
    } catch (e) {
        LOG.exception "ESPHome error opening socket", e
        sendEvent name: 'state', value: 'disconnected'
        return
    }
    pauseExecution(100)
    espHelloRequest()
}

private boolean isConnected() {
    return device.currentValue('state') == 'connected'
}

private static Map parseEntity(Map tags) {
    return [
        objectId: getString(tags, 1),
        key: getLong(tags, 2),
        name: getString(tags, 3),
        uniqueId: getString(tags, 4)
    ]
}

// parse received protobuf messages
public void parse(String hexString) {
    LOG.debug "ESPHome << received raw payload: ${hexString}"
    ByteArrayInputStream stream = new ByteArrayInputStream(HexUtils.hexStringToByteArray(hexString))
    if (stream.available() < 3) {
        LOG.error "payload length too small (${stream.available()})"
        return
    }

    int count = 1
    int b
    while ((b = stream.read()) != -1) {
        if (b == 0x00) {
            long length = readVarInt(stream, true)
            if (length != -1) {
                LOG.debug "ESPHome extracting protobuf message [count: ${count++}, length: ${length}]"
                parseMessage(stream, length)
            }
        } else if (b == 0x01) {
            LOG.error 'Driver does not support ESPHome native API encryption'
            return
        } else {
            LOG.warn "ESPHome expecting delimiter 0x00 but got 0x${Integer.toHexString(b)} instead"
            return
        }
    }
}

private synchronized void schedulePing() {
    LOG.trace "ESPHome scheduling next device ping in ${settings.pingInterval} seconds"
    runIn(Integer.parseInt(settings.pingInterval), 'apiPingRequest')
}

private synchronized void sendMessage(int type, Map tags = [:]) {
    ByteArrayOutputStream payload = new ByteArrayOutputStream()
    int length = tags ? encodeProtobufMessage(payload, tags) : 0
    ByteArrayOutputStream stream = new ByteArrayOutputStream()
    stream.write(0x00)
    writeVarInt(stream, length)
    writeVarInt(stream, type)
    payload.writeTo(stream)
    String output = HexUtils.byteArrayToHexString(stream.toByteArray())
    LOG.debug "ESPHome >> sending msg type ${type} with tags ${tags} as: ${output}"
    interfaces.rawSocket.sendMessage(output)
}


/**
 * Minimal Protobuf Implementation for use with ESPHome
 */
@Field static final int WIRETYPE_VARINT = 0
@Field static final int WIRETYPE_FIXED64 = 1
@Field static final int WIRETYPE_LENGTH_DELIMITED = 2
@Field static final int WIRETYPE_FIXED32 = 5
@Field static final int VARINT_MAX_BYTES = 10

private Map decodeProtobufMessage(ByteArrayInputStream stream, long available) {
    Map tags = [:]
    while (available > 0) {
        long tagAndType = readVarInt(stream, true)
        if (tagAndType == -1) {
            LOG.warn 'ESPHome unexpected EOF decoding protobuf message'
            break
        }
        available -= getVarIntSize(tagAndType)
        int wireType = ((int) tagAndType) & 0x07
        int tag = (int) (tagAndType >>> 3)
        switch (wireType) {
            case WIRETYPE_VARINT:
                long v = readVarInt(stream, false)
                available -= getVarIntSize(v)
                tags[tag] = v
                break
            case WIRETYPE_FIXED32:
            case WIRETYPE_FIXED64:
                long v = 0
                int shift = 0
                int count = (wireType == WIRETYPE_FIXED32) ? 4 : 8
                available -= count
                while (count-- > 0) {
                    long l = stream.read()
                    v |= l << shift
                    shift += 8
                }
                tags[tag] = v
                break
            case WIRETYPE_LENGTH_DELIMITED:
                int total = (int) readVarInt(stream, false)
                available -= getVarIntSize(total)
                available -= total
                byte[] data = new byte[total]
                int pos = 0
                while (pos < total) {
                    count = stream.read(data, pos, total - pos)
                    if (count <= 0) {
                        break
                    }
                    pos += count
                }
                tags[tag] = data
                break
            default:
                LOG.warn("Protobuf unknown wire type ${wireType}")
                break
        }
        LOG.debug "Protobuf decode [tag: ${tag}, wireType: ${wireType}, data: ${tags[tag]}]"
    }
    return tags
}

private int encodeProtobufMessage(ByteArrayOutputStream stream, Map tags) {
    int bytes = 0
    for (entry in tags) {
        if (entry.value) {
            int fieldNumber = entry.key
            int wireType = entry.value instanceof String ? 2 : 0
            int tag = (fieldNumber << 3) | wireType
            LOG.debug "Protobuf encode [fieldNumber: ${fieldNumber}, wireType: ${wireType}, value: ${entry.value}]"
            bytes += writeVarInt(stream, tag)
            switch (wireType) {
                case WIRETYPE_VARINT:
                    bytes += writeVarInt(stream, entry.value)
                    break
                case WIRETYPE_FIXED32:
                    int v = entry.value
                    for (int b = 0; b < 4; b++) {
                        stream.write((int) (v & 0x0ff))
                        bytes++
                        v >>= 8
                    }
                    break
                case WIRETYPE_FIXED64:
                    long v = entry.value
                    for (int b = 0; b < 8; b++) {
                        stream.write((int) (v & 0x0ff))
                        bytes++
                        v >>= 8
                    }
                    break
                case WIRETYPE_LENGTH_DELIMITED:
                    byte[] v = entry.value instanceof String ? entry.value.getBytes('UTF-8') : entry.value
                    bytes += writeVarInt(stream, v.size())
                    stream.write(v)
                    bytes += v.size()
                    break
            }
        }
    }
    return bytes
}

private static boolean getBoolean(Map tags, int index, boolean invert = false) {
    return tags[index] ? !invert : invert
}

private static double getDouble(Map tags, int index, double defaultValue = 0.0) {
    return tags[index] ? Double.intBitsToDouble(tags[index]) : defaultValue
}

private static float getFloat(Map tags, int index, float defaultValue = 0.0) {
    return tags[index] ? Float.intBitsToFloat((int) tags[index]) : defaultValue
}

private static int getInt(Map tags, int index, int defaultValue = 0) {
    return tags[index] ? (int) tags[index] : defaultValue
}

private static long getLong(Map tags, int index, long defaultValue = 0) {
    return tags[index] ? tags[index] : defaultValue
}

private static String getString(Map tags, int index, String defaultValue = '') {
    return tags[index] ? new String(tags[index], 'UTF-8') : defaultValue
}

private static int getVarIntSize(long i) {
    if (i < 0) {
      return VARINT_MAX_BYTES
    }
    int size = 1
    while (i >= 128) {
        size++
        i >>= 7
    }
    return size
}

private static long readVarInt(ByteArrayInputStream stream, boolean permitEOF) {
    long result = 0
    int shift = 0
    // max 10 byte wire format for 64 bit integer (7 bit data per byte)
    for (int i = 0; i < VARINT_MAX_BYTES; i++) {
        int b = stream.read()
        if (b == -1) {
            if (i == 0 && permitEOF) {
                return -1
            } else {
                return 0
            }
        }
        result |= ((long) (b & 0x07f)) << shift
        if ((b & 0x80) == 0) {
            break // get out early
        }
        shift += 7
    }
    return result
}

private static int writeVarInt(ByteArrayOutputStream stream, long value) {
    int count = 0
    for (int i = 0; i < VARINT_MAX_BYTES; i++) {
        int toWrite = (int) (value & 0x7f)
        value >>>= 7
        count++
        if (value == 0) {
            stream.write(toWrite)
            break;
        } else {
            stream.write(toWrite | 0x080)
        }
    }
    return count
}

private static long zigZagDecode(long v) {
    return (v >>> 1) ^ -(v & 1)
}

private static long zigZagEncode(long v) {
    return ((v << 1) ^ -(v >>> 63))
}

/**
 * Driver Helper Methods
 */
private ChildDeviceWrapper getChildDevice(String name, String dni, String driver, String namespace = 'hubitat') {
    ChildDeviceWrapper dw = getChildDevice(dni)
    if (dw == null) {
        LOG.info "Creating device ${name} using ${driver} driver"
        try {
            dw = addChildDevice(namespace, driver, dni,
                [
                    name: name,
                    label: name,
                ]
            )
        } catch (UnknownDeviceTypeException e) {
            LOG.exception("${name} device creation failed", e)
        }
    }
    return dw
}

@Field private final Map LOG = [
    debug: { s -> if (settings.logEnable) { log.debug(s) } },
    trace: { s -> if (settings.logEnable) { log.trace(s) } },
    info: { s -> log.info(s) },
    warn: { s -> log.warn(s) },
    error: { s -> log.error(s) },
    exception: { message, exception ->
        List<StackTraceElement> relevantEntries = exception.stackTrace.findAll { entry -> entry.className.startsWith('user_app') }
        Integer line = relevantEntries[0]?.lineNumber
        String method = relevantEntries[0]?.methodName
        log.error("${message}: ${exception} at line ${line} (${method})")
        if (settings.logEnable) {
            log.debug("App exception stack trace:\n${relevantEntries.join('\n')}")
        }
    }
].asImmutable()

/**
 * ESPHome Protobuf Enumerations
 * https://github.com/esphome/aioesphomeapi/blob/main/aioesphomeapi/api.proto
 */
@Field static final int ENTITY_CATEGORY_NONE = 0
@Field static final int ENTITY_CATEGORY_CONFIG = 1
@Field static final int ENTITY_CATEGORY_DIAGNOSTIC = 2

@Field static final int COVER_OPERATION_IDLE = 0
@Field static final int COVER_OPERATION_IS_OPENING = 1
@Field static final int COVER_OPERATION_IS_CLOSING = 2

@Field static final int FAN_SPEED_LOW = 0
@Field static final int FAN_SPEED_MEDIUM = 1
@Field static final int FAN_SPEED_HIGH = 2

@Field static final int FAN_DIRECTION_FORWARD = 0
@Field static final int FAN_DIRECTION_REVERSE = 1

@Field static final int STATE_CLASS_NONE = 0
@Field static final int STATE_CLASS_MEASUREMENT = 1
@Field static final int STATE_CLASS_TOTAL_INCREASING = 2
@Field static final int STATE_CLASS_TOTAL = 3

@Field static final int LAST_RESET_NONE = 0
@Field static final int LAST_RESET_NEVER = 1
@Field static final int LAST_RESET_AUTO = 2

@Field static final int CLIMATE_MODE_OFF = 0
@Field static final int CLIMATE_MODE_HEAT_COOL = 1
@Field static final int CLIMATE_MODE_COOL = 2
@Field static final int CLIMATE_MODE_HEAT = 3
@Field static final int CLIMATE_MODE_FAN_ONLY = 4
@Field static final int CLIMATE_MODE_DRY = 5
@Field static final int CLIMATE_MODE_AUTO = 6

@Field static final int CLIMATE_FAN_ON = 0
@Field static final int CLIMATE_FAN_OFF = 1
@Field static final int CLIMATE_FAN_AUTO = 2
@Field static final int CLIMATE_FAN_LOW = 3
@Field static final int CLIMATE_FAN_MEDIUM = 4
@Field static final int CLIMATE_FAN_HIGH = 5
@Field static final int CLIMATE_FAN_MIDDLE = 6
@Field static final int CLIMATE_FAN_FOCUS = 7
@Field static final int CLIMATE_FAN_DIFFUSE = 8

@Field static final int CLIMATE_SWING_OFF = 0
@Field static final int CLIMATE_SWING_BOTH = 1
@Field static final int CLIMATE_SWING_VERTICAL = 2
@Field static final int CLIMATE_SWING_HORIZONTAL = 3

@Field static final int CLIMATE_ACTION_OFF = 0
@Field static final int CLIMATE_ACTION_COOLING = 2
@Field static final int CLIMATE_ACTION_HEATING = 3
@Field static final int CLIMATE_ACTION_IDLE = 4
@Field static final int CLIMATE_ACTION_DRYING = 5
@Field static final int CLIMATE_ACTION_FAN = 6

@Field static final int CLIMATE_PRESET_NONE = 0
@Field static final int CLIMATE_PRESET_HOME = 1
@Field static final int CLIMATE_PRESET_AWAY = 2
@Field static final int CLIMATE_PRESET_BOOST = 3
@Field static final int CLIMATE_PRESET_COMFORT = 4
@Field static final int CLIMATE_PRESET_ECO = 5
@Field static final int CLIMATE_PRESET_SLEEP = 6
@Field static final int CLIMATE_PRESET_ACTIVITY = 7

@Field static final int LOCK_STATE_NONE = 0
@Field static final int LOCK_STATE_LOCKED = 1
@Field static final int LOCK_STATE_UNLOCKED = 2
@Field static final int LOCK_STATE_JAMMED = 3
@Field static final int LOCK_STATE_LOCKING = 4
@Field static final int LOCK_STATE_UNLOCKING = 5

@Field static final int LOCK_UNLOCK = 0
@Field static final int LOCK_LOCK = 1
@Field static final int LOCK_OPEN = 2

@Field static final int MEDIA_PLAYER_STATE_NONE = 0
@Field static final int MEDIA_PLAYER_STATE_IDLE = 1
@Field static final int MEDIA_PLAYER_STATE_PLAYING = 2
@Field static final int MEDIA_PLAYER_STATE_PAUSED = 3

@Field static final int MEDIA_PLAYER_COMMAND_PLAY = 0
@Field static final int MEDIA_PLAYER_COMMAND_PAUSE = 1
@Field static final int MEDIA_PLAYER_COMMAND_STOP = 2
@Field static final int MEDIA_PLAYER_COMMAND_MUTE = 3
@Field static final int MEDIA_PLAYER_COMMAND_UNMUTE = 4

@Field static final int LOG_LEVEL_NONE = 0
@Field static final int LOG_LEVEL_ERROR = 1
@Field static final int LOG_LEVEL_WARN = 2
@Field static final int LOG_LEVEL_INFO = 3
@Field static final int LOG_LEVEL_CONFIG = 4
@Field static final int LOG_LEVEL_DEBUG = 5
@Field static final int LOG_LEVEL_VERBOSE = 6
@Field static final int LOG_LEVEL_VERY_VERBOSE = 7
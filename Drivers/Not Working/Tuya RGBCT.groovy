/*
 * Many thanks to:
 *      The OpenHAB add-on code by Wim Vissers at https://github.com/wvissers/openhab2-addons-tuya
 *      The tuyapi project at https://github.com/codetheweb/tuyapi
 */

import groovy.json.JsonOutput
import groovy.transform.Field
import hubitat.helper.HexUtils
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.regex.Matcher
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

//@Field static ConcurrentLinkedQueue commandQueue = new ConcurrentLinkedQueue()
//@Field static Semaphore mutex = new Semaphore(1)

metadata {
    definition(
        name: 'Tuya Light',
        namespace: 'nrgup',
        author: 'Jonathan Bradshaw'
    ) {
        capability 'Actuator'
        capability 'Color Control'
        capability 'ColorMode'
        capability 'ColorTemperature'
        capability 'Switch'
        capability 'SwitchLevel'
        capability 'Refresh'
    }
}

preferences {
    section {
        input name: 'ipAddress',
              type: 'text',
              title: 'Device IP',
              required: true

        input name: 'devId',
              type: 'text',
              title: 'Device ID',
              required: true

        input name: 'localKey',
              type: 'text',
              title: 'Local Key',
              required: true

        input name: 'warmColorTemp',
              type: 'number',
              title: 'Warm Color Temperature',
              required: true,
              default: 2700

        input name: 'coldColorTemp',
              type: 'number',
              title: 'Cold Color Temperature',
              required: true,
              default: 4700
    }

    section {
        input name: 'logEnable',
              type: 'bool',
              title: 'Enable debug logging',
              required: false,
              defaultValue: true
    }
}

// Called when the device is started.
void initialize() {
    unschedule()
    log.info "${device.displayName} driver initializing"
    if (settings.ipAddress) {
        connect(settings.ipAddress)
        runIn(1, 'refresh')
    }
}

// Called when the device is first created.
void installed() {
    log.info "${device.displayName} driver installed"
}

// Called when the device is removed.
void uninstalled() {
    disconnect()
    log.info "${device.displayName} driver uninstalled"
}

// Called when the settings are updated.
void updated() {
    log.info "${device.displayName} driver configuration updated"
    log.debug settings
    initialize()

    if (logEnable) { runIn(1800, 'logsOff') }
}

void on() {
    log.info "Turning ${device.displayName} on"
    String payload = control(devId, [ '1': true ])
    byte[] output = encode('CONTROL', payload, localKey, nextSequence())
    if (logEnable) { log.debug "SEND: ${ipAddress} #${state.sequenceNo}: ${payload}" }
    send(output)
    runInMillis(750, 'refresh')
}

void off() {
    log.info "Turning ${device.displayName} off"
    String payload = control(devId, [ '1': false ])
    byte[] output = encode('CONTROL', payload, localKey, nextSequence())
    if (logEnable) { log.debug "SEND: ${ipAddress} #${state.sequenceNo}: ${payload}" }
    send(output)
    runInMillis(750, 'refresh')
}

// Set the brightness level and optional duration
void setLevel(BigDecimal level, BigDecimal duration = 0) {
    log.info "Setting ${device.displayName} brightness to ${level}%"
    String payload = control(devId, [ '3': (level * 2.55).toInteger() ])
    byte[] output = encode('CONTROL', payload, localKey, nextSequence())
    if (logEnable) { log.debug "SEND: ${ipAddress} #${state.sequenceNo}: ${payload}" }
    send(output)
    runInMillis(750, 'refresh')
}

// request query for device data
void refresh() {
    String payload = query(devId)
    byte[] output = encode('DP_QUERY', payload, localKey, nextSequence())
    if (logEnable) { log.debug "SEND: ${ipAddress} #${state.sequenceNo}: ${payload}" }
    send(output)
}

// Parse incoming messages
void parse(String message) {
    byte[] buffer = HexUtils.hexStringToByteArray(message)
    Map result = decode(buffer, localKey)
    if (result.error) {
        log.error result
        return
    }

    if (result.text?.startsWith('{') && result.text?.endsWith('}')) {
        Map json = parseJson(result.text)
        if (logEnable) { log.debug 'RECV: ' + json }
        if (json.dps.containsKey('1')) {
            sendEvent(newEvent('switch', json.dps['1'] ? 'on' : 'off'))
        }
        if (json.dps.containsKey('2')) {
            sendEvent(newEvent('colorMode', json.dps['2'] == 'colour' ? 'RGB' : 'CT'))
        }
        if (json.dps.containsKey('3')) {
            int value = Math.round((int)json.dps['3'] / 2.55)
            sendEvent(newEvent('level', value, '%'))
        }
        if (json.dps.containsKey('4')) {
            int range = settings.coldColorTemp - settings.warmColorTemp
            int value = settings.warmColorTemp + Math.round(range * (json.dps['4'] / 100))
            sendEvent(newEvent('colorTemperature', value, 'K'))
        }
        if (json.dps.containsKey('5')) {
            String value = json.dps['5']
            boolean found = false
            int h, s, b
            switch (json.dps['5'].length()) {
                case 12: // HSB
                    Matcher match = value =~ /^([0-9a-f]{4})([0-9a-f]{4})([0-9a-f]{4})$/
                    if (match.find()) {
                        found = true
                        h = Math.round(Integer.parseInt(match.group(1), 16) / 16)
                        s = Math.round(Integer.parseInt(match.group(2), 16) / 10)
                        b = Math.round(Integer.parseInt(match.group(3), 16) / 10)
                    }
                    break
                case 14: // HEXHSB
                    Matcher match = value =~ /^.{6}([0-9a-f]{4})([0-9a-f]{2})([0-9a-f]{2})$/
                    if (match.find()) {
                        found = true
                        h = Math.round(Integer.parseInt(match.group(1), 16) / 3.6)
                        s = Math.round(Integer.parseInt(match.group(2), 16) / 2.55)
                        b = Math.round(Integer.parseInt(match.group(3), 16) / 2.55)
                    }
                    break
            }
            if (found) {
                sendEvent(newEvent('hue', h))
                sendEvent(newEvent('colorName', getGenericName([h, s, b])))
                sendEvent(newEvent('saturation', s))
                sendEvent(newEvent('level', b))
            }
        }
    } else if (result.text) {
        log.info result
    }
}

void socketStatus(String message) {
    log.info message
    if (message.contains('Stream closed')) {
        runIn(5, 'initialize')
    }
}

private static byte[] encode(String command, String input, String localKey, long sequenceNo = 0, String ver = '3.3') {
    byte[] payload = null

    if (ver == '3.3') {
        payload = encrypt(input.getBytes('UTF-8'), localKey)
        // Check if we need an extended header, only for certain CommandTypes
        if (command != 'DP_QUERY') {
            // Add 3.3 header
            byte[] buffer = new byte[payload.length + 15]
            fill(buffer, (byte) 0x00, 0, 15)
            copy(buffer, '3.3', 0)
            copy(buffer, payload, 15)
            payload = buffer
        }
    } else {
        // Todo other versions
        payload = input
    }

    // Allocate buffer with room for payload + 24 bytes for
    // prefix, sequence, command, length, crc, and suffix
    byte[] buffer = new byte[payload.length + 24]

    // Add prefix, command and length.
    putUInt32(buffer, 0, 0x000055AA)
    putUInt32(buffer, 8, commandByte(command))
    putUInt32(buffer, 12, payload.length + 8)

    // Optionally add sequence number.
    if (sequenceNo >= 0) {
        putUInt32(buffer, 4, sequenceNo)
    }

    // Add payload, crc and suffix
    copy(buffer, payload, 16)
    byte[] crcbuf = new byte[payload.length + 16]
    copy(crcbuf, buffer, 0, payload.length + 16)
    putUInt32(buffer, payload.length + 16, crc32(crcbuf))
    putUInt32(buffer, payload.length + 20, 0x0000AA55)

    return buffer
}

private static Map decode(byte[] buffer, String localKey) {
    Map result = [:]

    if (buffer.length < 24) {
        result.error = 'Packet too short (less than 24 bytes). Length: ' + buffer.length
        return result
    }

    long prefix = getUInt32(buffer, 0)
    if (prefix != 0x000055AA) {
        result.error = 'Prefix does not match: ' + String.format('%x', prefix)
        return result
    }

    // Check for extra data
    int leftover = 0

    // Leftover points to beginning of next packet, if any.
    int suffixLocation = indexOfUInt32(buffer, 0x0000AA55)
    leftover = suffixLocation + 4

    // Get sequence number
    result.sequenceNumber = getUInt32(buffer, 4)

    // Get command byte
    result.commandByte = getUInt32(buffer, 8)

    // Get payload size
    result.payloadSize = getUInt32(buffer, 12)

    // Check for payload
    if (leftover - 8 < result.payloadSize) {
        result.error = 'Packet missing payload: payload has length: ' + result.payloadSize
        return result
    }

    // Check CRC
    long expectedCrc = getUInt32(buffer, (int) (16 + result.payloadSize - 8))
    long computedCrc = crc32(copy(buffer, 0, (int) result.payloadSize + 8))
    if (computedCrc != expectedCrc) {
        result.error = 'CRC error. Expected: ' + expectedCrc + ', computed: ' + computedCrc
        return result
    }

    // Get the return code, 0 = success
    // This field is only present in messages from the devices
    // Absent in messages sent to device
    result.returnCode = getUInt32(buffer, 16) & 0xFFFFFF00

    // Get the payload
    // Adjust for messages lacking a return code
    byte[] payload
    boolean correct = false
    if (result.returnCode != 0) {
        payload = copy(buffer, 16, (int) (result.payloadSize - 8))
    } else if (result.commandByte == 8) { // STATUS
        correct = true
        payload = copy(buffer, 16 + 3, (int) (result.payloadSize - 11))
    } else {
        payload = copy(buffer, 16 + 4, (int) (result.payloadSize - 12))
    }

    try {
        byte[] data = decrypt(payload, localKey)
        result.text = correct ? new String(data, 16, data.length - 16) : new String(data, 'UTF-8')
    } catch (e) {
        result.error = e
    }

    return result
}

private static long getUInt32(byte[] buffer, int start) {
    long result = 0
    for (int i = start; i < start + 4; i++) {
        result *= 256
        result += (buffer[i] & 0xff)
    }

    return result
}

private static int indexOfUInt32(byte[] buffer, long marker) {
    long mrk = marker
    byte[] m = new byte[4]
    for (int i = 3; i >= 0; i--) {
        m[i] = (byte) (mrk & 0xFF)
        mrk /= 256
    }

    int j = 0
    for (int p = 0; p < buffer.length; p++) {
        if (buffer[p] == m[j]) {
            if (j == 3) {
                return p - 3
            }
            j++
        } else {
            j = 0
        }
    }

    return -1
}

private static void putUInt32(byte[] buffer, int start, long value) {
    long lv = value
    for (int i = 3; i >= 0; i--) {
        buffer[start + i] = (byte) (((lv & 0xFFFFFFFF) % 0x100) & 0xFF)
        lv /= 0x100
    }
}

private static byte[] copy(byte[] buffer, String source, int from) {
    return copy(buffer, source.getBytes('UTF-8'), from)
}

private static byte[] copy(byte[] buffer, byte[] source, int from) {
    for (int i = 0; i < source.length; i++) {
        buffer[i + from] = source[i]
    }
    return buffer
}

private static byte[] copy(byte[] source, int from, int length) {
    byte[] buffer = new byte[length]
    for (int i = 0; i < length; i++) {
        buffer[i] = source[i + from]
    }
    return buffer
}

private static byte[] copy(byte[] buffer, byte[] source, int from, int length) {
    for (int i = 0; i < length; i++) {
        buffer[i + from] = source[i]
    }
    return buffer
}

private static byte[] fill(byte[] buffer, byte fill, int from, int length) {
    for (int i = from; i < from + length; i++) {
        buffer[i] = fill
    }
    return buffer
}

private static byte commandByte(String command) {
    switch (command) {
        case 'CONTROL': return 7
        case 'STATUS': return 8
        case 'HEART_BEAT': return 9
        case 'DP_QUERY': return 10
        case 'CONTROL_NEW': return 13
        case 'DP_QUERY_NEW': return 16
        case 'SCENE_EXECUTE': return 17
    }
}

private static byte[] encrypt (byte[] payload, String secret) {
    SecretKeySpec key = new SecretKeySpec(secret.getBytes('UTF-8'), 'AES')
    Cipher cipher = Cipher.getInstance('AES/ECB/PKCS5Padding')
    cipher.init(Cipher.ENCRYPT_MODE, key)
    return cipher.doFinal(payload)
}

private static byte[] decrypt (byte[] payload, String secret) {
    SecretKeySpec key = new SecretKeySpec(secret.getBytes('UTF-8'), 'AES')
    Cipher cipher = Cipher.getInstance('AES/ECB/PKCS5Padding')
    cipher.init(Cipher.DECRYPT_MODE, key)
    return cipher.doFinal(payload)
}

@Field static final long[] crc32Table = [ 0x00000000, 0x77073096, 0xEE0E612C, 0x990951BA, 0x076DC419, 0x706AF48F,
    0xE963A535, 0x9E6495A3, 0x0EDB8832, 0x79DCB8A4, 0xE0D5E91E, 0x97D2D988, 0x09B64C2B, 0x7EB17CBD, 0xE7B82D07,
    0x90BF1D91, 0x1DB71064, 0x6AB020F2, 0xF3B97148, 0x84BE41DE, 0x1ADAD47D, 0x6DDDE4EB, 0xF4D4B551, 0x83D385C7,
    0x136C9856, 0x646BA8C0, 0xFD62F97A, 0x8A65C9EC, 0x14015C4F, 0x63066CD9, 0xFA0F3D63, 0x8D080DF5, 0x3B6E20C8,
    0x4C69105E, 0xD56041E4, 0xA2677172, 0x3C03E4D1, 0x4B04D447, 0xD20D85FD, 0xA50AB56B, 0x35B5A8FA, 0x42B2986C,
    0xDBBBC9D6, 0xACBCF940, 0x32D86CE3, 0x45DF5C75, 0xDCD60DCF, 0xABD13D59, 0x26D930AC, 0x51DE003A, 0xC8D75180,
    0xBFD06116, 0x21B4F4B5, 0x56B3C423, 0xCFBA9599, 0xB8BDA50F, 0x2802B89E, 0x5F058808, 0xC60CD9B2, 0xB10BE924,
    0x2F6F7C87, 0x58684C11, 0xC1611DAB, 0xB6662D3D, 0x76DC4190, 0x01DB7106, 0x98D220BC, 0xEFD5102A, 0x71B18589,
    0x06B6B51F, 0x9FBFE4A5, 0xE8B8D433, 0x7807C9A2, 0x0F00F934, 0x9609A88E, 0xE10E9818, 0x7F6A0DBB, 0x086D3D2D,
    0x91646C97, 0xE6635C01, 0x6B6B51F4, 0x1C6C6162, 0x856530D8, 0xF262004E, 0x6C0695ED, 0x1B01A57B, 0x8208F4C1,
    0xF50FC457, 0x65B0D9C6, 0x12B7E950, 0x8BBEB8EA, 0xFCB9887C, 0x62DD1DDF, 0x15DA2D49, 0x8CD37CF3, 0xFBD44C65,
    0x4DB26158, 0x3AB551CE, 0xA3BC0074, 0xD4BB30E2, 0x4ADFA541, 0x3DD895D7, 0xA4D1C46D, 0xD3D6F4FB, 0x4369E96A,
    0x346ED9FC, 0xAD678846, 0xDA60B8D0, 0x44042D73, 0x33031DE5, 0xAA0A4C5F, 0xDD0D7CC9, 0x5005713C, 0x270241AA,
    0xBE0B1010, 0xC90C2086, 0x5768B525, 0x206F85B3, 0xB966D409, 0xCE61E49F, 0x5EDEF90E, 0x29D9C998, 0xB0D09822,
    0xC7D7A8B4, 0x59B33D17, 0x2EB40D81, 0xB7BD5C3B, 0xC0BA6CAD, 0xEDB88320, 0x9ABFB3B6, 0x03B6E20C, 0x74B1D29A,
    0xEAD54739, 0x9DD277AF, 0x04DB2615, 0x73DC1683, 0xE3630B12, 0x94643B84, 0x0D6D6A3E, 0x7A6A5AA8, 0xE40ECF0B,
    0x9309FF9D, 0x0A00AE27, 0x7D079EB1, 0xF00F9344, 0x8708A3D2, 0x1E01F268, 0x6906C2FE, 0xF762575D, 0x806567CB,
    0x196C3671, 0x6E6B06E7, 0xFED41B76, 0x89D32BE0, 0x10DA7A5A, 0x67DD4ACC, 0xF9B9DF6F, 0x8EBEEFF9, 0x17B7BE43,
    0x60B08ED5, 0xD6D6A3E8, 0xA1D1937E, 0x38D8C2C4, 0x4FDFF252, 0xD1BB67F1, 0xA6BC5767, 0x3FB506DD, 0x48B2364B,
    0xD80D2BDA, 0xAF0A1B4C, 0x36034AF6, 0x41047A60, 0xDF60EFC3, 0xA867DF55, 0x316E8EEF, 0x4669BE79, 0xCB61B38C,
    0xBC66831A, 0x256FD2A0, 0x5268E236, 0xCC0C7795, 0xBB0B4703, 0x220216B9, 0x5505262F, 0xC5BA3BBE, 0xB2BD0B28,
    0x2BB45A92, 0x5CB36A04, 0xC2D7FFA7, 0xB5D0CF31, 0x2CD99E8B, 0x5BDEAE1D, 0x9B64C2B0, 0xEC63F226, 0x756AA39C,
    0x026D930A, 0x9C0906A9, 0xEB0E363F, 0x72076785, 0x05005713, 0x95BF4A82, 0xE2B87A14, 0x7BB12BAE, 0x0CB61B38,
    0x92D28E9B, 0xE5D5BE0D, 0x7CDCEFB7, 0x0BDBDF21, 0x86D3D2D4, 0xF1D4E242, 0x68DDB3F8, 0x1FDA836E, 0x81BE16CD,
    0xF6B9265B, 0x6FB077E1, 0x18B74777, 0x88085AE6, 0xFF0F6A70, 0x66063BCA, 0x11010B5C, 0x8F659EFF, 0xF862AE69,
    0x616BFFD3, 0x166CCF45, 0xA00AE278, 0xD70DD2EE, 0x4E048354, 0x3903B3C2, 0xA7672661, 0xD06016F7, 0x4969474D,
    0x3E6E77DB, 0xAED16A4A, 0xD9D65ADC, 0x40DF0B66, 0x37D83BF0, 0xA9BCAE53, 0xDEBB9EC5, 0x47B2CF7F, 0x30B5FFE9,
    0xBDBDF21C, 0xCABAC28A, 0x53B39330, 0x24B4A3A6, 0xBAD03605, 0xCDD70693, 0x54DE5729, 0x23D967BF, 0xB3667A2E,
    0xC4614AB8, 0x5D681B02, 0x2A6F2B94, 0xB40BBE37, 0xC30C8EA1, 0x5A05DF1B, 0x2D02EF8D ]

private static long crc32(byte[] buffer) {
    long crc = 0xFFFFFFFFL
    for (byte b : buffer) {
        crc = ((crc >>> 8) & 0xFFFFFFFFL) ^ (crc32Table[(int) ((crc ^ b) & 0xff)] & 0xFFFFFFFFL)
    }
    return ((crc & 0xFFFFFFFFL) ^ 0xFFFFFFFFL) & 0xFFFFFFFFL // return 0xFFFFFFFFL
}

// Convert hsv to a generic color name
private static String getGenericName(List<Integer> hsv) {
    String colorName

    if (!hsv[0] && !hsv[1]) {
        colorName = 'White'
    } else {
        switch (hsv[0] * 3.6 as int) {
            case 0..15: colorName = 'Red'
                break
            case 16..45: colorName = 'Orange'
                break
            case 46..75: colorName = 'Yellow'
                break
            case 76..105: colorName = 'Chartreuse'
                break
            case 106..135: colorName = 'Green'
                break
            case 136..165: colorName = 'Spring'
                break
            case 166..195: colorName = 'Cyan'
                break
            case 196..225: colorName = 'Azure'
                break
            case 226..255: colorName = 'Blue'
                break
            case 256..285: colorName = 'Violet'
                break
            case 286..315: colorName = 'Magenta'
                break
            case 316..345: colorName = 'Rose'
                break
            case 346..360: colorName = 'Red'
                break
        }
    }

    return colorName
}

private long nextSequence() {
    state.sequenceNo = state.sequenceNo ? (long)state.sequenceNo + 1 : 1
    return state.sequenceNo
}

private Map newEvent(String name, Object value, String unit = null) {
    return [
        name: name,
        value: value,
        unit: unit,
        descriptionText: "${device.displayName} ${name} is ${value}${unit ?: ''}"
    ]
}

private String query(String devId) {
    return JsonOutput.toJson([
      gwId: devId,
      devId: devId,
      t: Math.round(now() / 1000).toString(),
      //dps: [:],
      //uid: devId
    ])
}

private String control(String devId, Map dps) {
    return JsonOutput.toJson([
      gwId: devId,
      devId: devId,
      t: Math.round(now() / 1000).toString(),
      dps: dps,
      uid: ''
    ])
}

private void connect(String ipAddress) {
    interfaces.rawSocket.connect(ipAddress, 6668, byteInterface: true, readDelay: 500)
}

private void disconnect() {
    unschedule()
    interfaces.rawSocket.disconnect()
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void heartbeat() {
    byte[] output = encode('HEART_BEAT', '', localKey)
    if (logEnable) { log.debug 'Sending heartbeat packet...' }
    send(output)
}

private void send(byte[] output) {
    String msg = HexUtils.byteArrayToHexString(output)

    try {
        interfaces.rawSocket.sendMessage(msg)
        runIn(15, 'heartbeat')
    } catch (e) {
        log.error "Error $e"
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
    log.info "debug logging disabled for ${device.displayName}"
}

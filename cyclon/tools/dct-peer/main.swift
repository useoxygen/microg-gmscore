// SPDX-FileCopyrightText: 2026 Cyclon
// SPDX-License-Identifier: Apache-2.0
import Foundation
import CoreBluetooth

// Observe only the explicitly requested synthetic session. Never print other nearby devices.
let arguments = CommandLine.arguments
func value(_ option: String) -> String? {
    guard let index = arguments.firstIndex(of: option), index + 1 < arguments.count else { return nil }
    return arguments[index + 1]
}
guard let expectedSession = value("--session"), expectedSession.utf8.count == 6 else {
    fputs("usage: DctPeer --session abc234 [--connect]\n", stderr)
    exit(2)
}
let shouldConnect = arguments.contains("--connect")

final class Peer: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    let expectedSession: String
    let shouldConnect: Bool
    var manager: CBCentralManager!
    var peripheral: CBPeripheral?
    var psm: UInt16 = 0
    var status: Int32 = 2
    var done = false
    init(session: String, connect: Bool) {
        expectedSession = session
        shouldConnect = connect
        super.init()
        manager = CBCentralManager(delegate: self, queue: .main)
    }
    func finish(_ message: String, _ result: Int32) {
        guard !done else { return }
        print(message)
        done = true
        status = result
        manager.stopScan()
        if let device = peripheral { manager.cancelPeripheralConnection(device) }
    }
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn { central.scanForPeripherals(withServices: nil) }
        else if central.state != .unknown && central.state != .resetting {
            finish("BLOCKED Bluetooth state=\(central.state.rawValue)", 2)
        }
    }
    func centralManager(_ central: CBCentralManager, didDiscover device: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard peripheral == nil,
              let services = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data],
              let data = services[CBUUID(string: "FC73")], data.first == 0x20 else { return }
        let bytes = [UInt8](data)
        var position = 1
        var fields: [UInt8: [UInt8]] = [:]
        while position < bytes.count {
            let header = bytes[position]; position += 1
            let extended = header & 0x80 != 0
            let length = Int(extended ? header & 0x7f : header >> 4)
            var type = header & 0xf
            if extended {
                guard position < bytes.count else { return }
                type = bytes[position]; position += 1
            }
            guard type != 0, fields[type] == nil, length <= bytes.count - position else { return }
            fields[type] = Array(bytes[position..<(position + length)]); position += length
        }
        guard let session = fields[1], String(bytes: session, encoding: .utf8) == expectedSession,
              fields[2] == [1], fields[6] == [2], let encodedPSM = fields[4], encodedPSM.count == 2 else { return }
        psm = UInt16(encodedPSM[0]) | UInt16(encodedPSM[1]) << 8
        guard psm != 0 else { return }
        print("PASS FC73 target advertisement, session matched, PSM=\(psm), bytes=\(bytes.count)")
        if shouldConnect {
            peripheral = device
            device.delegate = self
            central.stopScan()
            central.connect(device)
        } else { finish("PASS advertisement observation only", 0) }
    }
    func centralManager(_ central: CBCentralManager, didConnect device: CBPeripheral) {
        device.openL2CAPChannel(CBL2CAPPSM(psm))
    }
    func centralManager(_ central: CBCentralManager, didFailToConnect device: CBPeripheral, error: Error?) {
        finish("BLOCKED BLE connection: \(String(describing: error))", 2)
    }
    func peripheral(_ peripheral: CBPeripheral, didOpen channel: CBL2CAPChannel?, error: Error?) {
        guard let channel = channel, error == nil else {
            finish("BLOCKED L2CAP channel: \(String(describing: error))", 2); return
        }
        channel.inputStream.close()
        channel.outputStream.close()
        finish("PASS L2CAP CoC open/close; IPv6/PAKE/control are not exercised", 0)
    }
}
let peer = Peer(session: expectedSession, connect: shouldConnect)
let deadline = Date().addingTimeInterval(25)
while !peer.done && Date() < deadline { RunLoop.main.run(until: Date().addingTimeInterval(0.1)) }
if !peer.done { peer.finish("BLOCKED no matching DCT session before deadline", 2) }
exit(peer.status)

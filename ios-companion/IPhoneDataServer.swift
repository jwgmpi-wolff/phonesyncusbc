import Foundation
import Network
import Darwin

/// iOS Companion App - Exposes iPhone databases over WiFi to Android device
/// Install via Xcode sideload or jailbreak package manager
/// Runs in background, serves databases on local network

class IPhoneDataServer: NSObject {
    private var listener: NWListener?
    private let queue = DispatchQueue(label: "com.jerrywolff.phonesyncusbc.server")
    private var port: NWEndpoint.Port = 8765
    
    let serverPort = 8765
    let serverVersion = "1.0.0"
    
    override init() {
        super.init()
    }
    
    /// Start HTTP server on local WiFi
    func startServer() {
        do {
            let parameters = NWParameters.tcp
            listener = try NWListener(using: parameters, on: port)
            
            listener?.service = NWListener.Service(
                name: "PhoneSyncCompanion",
                type: "_phonesync._tcp",
                domain: "local"
            )
            
            listener?.newConnectionHandler = { [weak self] connection in
                self?.handleConnection(connection)
            }
            
            listener?.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready:
                    print("✓ iPhone Data Server running on port \(self?.serverPort ?? 8765)")
                case .failed(let error):
                    print("✗ Server error: \(error)")
                default:
                    break
                }
            }
            
            listener?.start(queue: queue)
        } catch {
            print("Failed to start server: \(error)")
        }
    }
    
    private func handleConnection(_ connection: NWConnection) {
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.handleHTTPRequest(connection)
            case .failed:
                connection.cancel()
            default:
                break
            }
        }
        connection.start(queue: queue)
    }
    
    private func handleHTTPRequest(_ connection: NWConnection) {
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: 4096)
        defer { buffer.deallocate() }
        
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] data, _, _, error in
            guard let data = data, let requestStr = String(data: data, encoding: .utf8) else {
                connection.cancel()
                return
            }
            
            let response = self?.processRequest(requestStr) ?? "404 Not Found"
            let httpResponse = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: \(response.count)\r\n\r\n\(response)"
            
            connection.send(content: httpResponse.data(using: .utf8), completion: .idempotent)
            connection.cancel()
        }
    }
    
    private func processRequest(_ request: String) -> String {
        if request.contains("GET /api/status") {
            return statusResponse()
        } else if request.contains("GET /api/messages") {
            return messagesResponse()
        } else if request.contains("GET /api/contacts") {
            return contactsResponse()
        } else if request.contains("GET /api/notes") {
            return notesResponse()
        } else if request.contains("GET /api/callhistory") {
            return callHistoryResponse()
        } else if request.contains("GET /api/files") {
            return sharedFilesResponse()
        }
        return "{\"error\": \"unknown endpoint\"}"
    }
    
    private func statusResponse() -> String {
        return """
        {
            "status": "online",
            "version": "\(serverVersion)",
            "device": "\(UIDevice.current.name)",
            "available": ["messages", "contacts", "notes", "callhistory", "files"]
        }
        """
    }
    
    private func messagesResponse() -> String {
        guard let smsDb = getMessageDatabase() else {
            return "{\"error\": \"SMS database not accessible\"}"
        }
        
        var messages: [[String: Any]] = []
        
        // Parse sms.db for message data
        // Query: SELECT ROWID, address, date, text, flags FROM message ORDER BY date DESC LIMIT 1000
        
        return """
        {
            "type": "messages",
            "count": \(messages.count),
            "data": \(encodeJSON(messages))
        }
        """
    }
    
    private func contactsResponse() -> String {
        // Access iPhone Contacts via CNContactStore
        var contacts: [[String: Any]] = []
        
        // Parse contacts database or use Contacts framework
        
        return """
        {
            "type": "contacts",
            "count": \(contacts.count),
            "data": \(encodeJSON(contacts))
        }
        """
    }
    
    private func notesResponse() -> String {
        // Access Notes via EventKit or SQLite database
        var notes: [[String: Any]] = []
        
        return """
        {
            "type": "notes",
            "count": \(notes.count),
            "data": \(encodeJSON(notes))
        }
        """
    }
    
    private func callHistoryResponse() -> String {
        var calls: [[String: Any]] = []
        
        return """
        {
            "type": "callhistory",
            "count": \(calls.count),
            "data": \(encodeJSON(calls))
        }
        """
    }
    
    private func getMessageDatabase() -> String? {
        // Path to SMS database: /var/mobile/Library/SMS/sms.db
        let smsPath = NSHomeDirectory() + "/Library/SMS/sms.db"
        return FileManager.default.fileExists(atPath: smsPath) ? smsPath : nil
    }
    
    private func encodeJSON(_ object: Any) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: object),
              let json = String(data: data, encoding: .utf8) else {
            return "[]"
        }
        return json
    }
}

    private func sharedFilesResponse() -> String {
        let fileManager = FileManager.default
        let root = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]
        var files: [[String: Any]] = []
        let enumerator = fileManager.enumerator(
            at: root,
            includingPropertiesForKeys: keys,
            options: [.skipsHiddenFiles],
        )

        while let url = enumerator?.nextObject() as? URL {
            guard let values = try? url.resourceValues(forKeys: Set(keys)),
                  values.isRegularFile == true else { continue }
            let relativePath = url.path.replacingOccurrences(of: root.path + "/", with: "")
            files.append([
                "path": relativePath,
                "size": values.fileSize ?? 0,
                "modified": Int64((values.contentModificationDate?.timeIntervalSince1970 ?? 0) * 1000),
            ])
        }

        return "{\"type\":\"files\",\"count\":\(files.count),\"data\":\(encodeJSON(files))}"
    }

// MARK: - App Delegate
import UIKit

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {
    let server = IPhoneDataServer()
    
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        server.startServer()
        
        return true
    }
}

// MARK: - Scene Delegate
class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    
    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = (scene as? UIWindowScene) else { return }
        
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = UIHostingController(rootView: ContentView())
        self.window = window
        window.makeKeyAndVisible()
    }
}

// MARK: - SwiftUI UI
import SwiftUI

struct ContentView: View {
    @State private var isServerRunning = true
    @State private var serverIP = getLocalIP()
    @State private var connectedDevices: [String] = []
    
    var body: some View {
        VStack(spacing: 20) {
            Text("PhoneSync Companion")
                .font(.title2)
                .fontWeight(.bold)
            
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Circle()
                        .fill(isServerRunning ? Color.green : Color.red)
                        .frame(width: 12, height: 12)
                    Text(isServerRunning ? "Server Online" : "Server Offline")
                }
                
                if let ip = serverIP {
                    Text("IP: \(ip):8765")
                        .font(.caption)
                        .foregroundColor(.gray)
                }
            }
            .padding()
            .background(Color(.systemGray6))
            .cornerRadius(8)
            
            Text("Available Databases")
                .font(.headline)
                .frame(maxWidth: .infinity, alignment: .leading)
            
            VStack(alignment: .leading, spacing: 8) {
                DatabaseItem(name: "Messages", icon: "💬")
                DatabaseItem(name: "Contacts", icon: "👤")
                DatabaseItem(name: "Notes", icon: "📝")
                DatabaseItem(name: "Call History", icon: "☎️")
            }
            
            Spacer()
            
            Text("Android device can now connect via WiFi to recover data")
                .font(.caption)
                .foregroundColor(.gray)
                .multilineTextAlignment(.center)
        }
        .padding()
    }
}

struct DatabaseItem: View {
    let name: String
    let icon: String
    
    var body: some View {
        HStack {
            Text(icon)
                .font(.title3)
            Text(name)
                .font(.body)
            Spacer()
            Image(systemName: "checkmark.circle.fill")
                .foregroundColor(.green)
        }
        .padding(8)
        .background(Color(.systemGray6))
        .cornerRadius(6)
    }
}

func getLocalIP() -> String? {
    var address: String?
    var ifaddr: UnsafeMutablePointer<ifaddrs>? = nil
    if getifaddrs(&ifaddr) == 0 {
        var ptr = ifaddr
        while ptr != nil {
            defer { ptr = ptr?.pointee.ifa_next }
            
            let interface = ptr?.pointee
            let addrFamily = interface?.ifa_addr.pointee.sa_family
            
            if addrFamily == sa_family_t(AF_INET) {
                if let name = interface?.ifa_name,
                   String(cString: name) == "en0" {
                    var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    getnameinfo(interface?.ifa_addr,
                               socklen_t((interface?.ifa_addr.pointee.sa_len)!),
                               &hostname,
                               socklen_t(hostname.count),
                               nil,
                               0,
                               NI_NUMERICHOST)
                    address = String(cString: hostname)
                }
            }
        }
        freeifaddrs(ifaddr)
    }
    return address
}

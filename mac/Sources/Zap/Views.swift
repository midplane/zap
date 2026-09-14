import SwiftUI
import AppKit
import CoreImage.CIFilterBuiltins
import ServiceManagement

private extension Color {
    static let zapSecondary = Color(nsColor: NSColor(name: nil) { appearance in
        appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
            ? NSColor(white: 0.75, alpha: 1)
            : NSColor(white: 0.34, alpha: 1)
    })
}

struct HistoryView: View {
    @ObservedObject var model: Model
    @FocusState private var searchFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            searchBar
            filterBar
            Divider()
            if model.filtered.isEmpty {
                emptyState
            } else {
                clipList
            }
            if let error = model.error {
                errorBanner(error)
            }
            Divider()
            statusBar
        }
        .frame(minWidth: 580, minHeight: 420)
        .background(.background)
        .onAppear { searchFocused = true }
        .onChange(of: model.query) { _, _ in model.selection = model.filtered.first?.id }
        .onChange(of: model.filter) { _, _ in model.selection = model.filtered.first?.id }
        .sheet(isPresented: $model.settingsOpen) { SettingsView(model: model) }
        .sheet(item: $model.preview) { clip in PreviewView(clip: clip, model: model) }
    }

    private var searchBar: some View {
        HStack(spacing: 12) {
            Image(systemName: "magnifyingglass").foregroundStyle(Color.zapSecondary)
            TextField("", text: $model.query)
                .textFieldStyle(.plain)
                .font(.title3)
                .focused($searchFocused)
                .accessibilityLabel("Search clipboard history")
                .overlay(alignment: .leading) {
                    if model.query.isEmpty {
                        Text("Search clipboard history")
                            .font(.title3)
                            .foregroundStyle(Color.zapSecondary)
                            .allowsHitTesting(false)
                            .accessibilityHidden(true)
                    }
                }
            Button { model.settingsOpen = true } label: {
                Image(systemName: "gearshape").padding(6).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help("Settings")
            .accessibilityLabel("Settings")
        }
        .padding(20)
    }

    private var filterBar: some View {
        HStack {
            Picker("Content", selection: $model.filter) {
                ForEach(ContentFilter.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .frame(width: 220)
            Spacer()
            Text(model.filtered.count == 1 ? "1 item" : "\(model.filtered.count) items")
                .font(.caption)
                .foregroundStyle(Color.zapSecondary)
                .monospacedDigit()
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 12)
    }

    private var emptyState: some View {
        let searching = !model.query.isEmpty
        let title = searching ? "No matches"
            : model.filter == .all ? "Your clipboard, remembered"
            : "No \(model.filter.rawValue.lowercased()) yet"
        let message = searching
            ? "Try another word or switch the content filter."
            : "Copy text or an image to get started.\nYour history stays here for \(model.days) days."
        return ContentUnavailableView {
            Label {
                Text(title).foregroundStyle(Color.primary)
            } icon: {
                if !searching && model.filter == .all {
                    Image(nsImage: NSApp.applicationIconImage).resizable().frame(width: 56, height: 56)
                } else {
                    Image(systemName: searching ? "magnifyingglass" : "clipboard").foregroundStyle(Color.zapSecondary)
                }
            }
        } description: {
            Text(message).foregroundStyle(Color.zapSecondary)
        }
        .frame(maxHeight: .infinity)
    }

    private var clipList: some View {
        ScrollViewReader { proxy in
            List(selection: $model.selection) {
                ForEach(model.filtered) { clip in
                    ClipRow(clip: clip, selected: model.selection == clip.id)
                        .tag(clip.id)
                        .contextMenu {
                            Button("Copy") { model.copy(clip) }
                            Button("Preview") { model.preview = clip }
                            Divider()
                            Button("Delete", role: .destructive) { model.delete(clip) }
                        }
                        .onTapGesture(count: 2) { model.copy(clip, andPaste: true) }
                }
            }
            .listStyle(.inset)
            .onChange(of: model.selection) { _, id in
                if let id { proxy.scrollTo(id) }
            }
        }
    }

    private func errorBanner(_ message: String) -> some View {
        HStack(alignment: .top) {
            Image(systemName: "exclamationmark.circle")
            Text(message).textSelection(.enabled)
            Spacer()
            Button { model.error = nil } label: { Image(systemName: "xmark") }
                .buttonStyle(.plain)
                .accessibilityLabel("Dismiss error")
        }
        .font(.caption)
        .foregroundStyle(.red)
        .padding(12)
        .background(.red.opacity(0.06))
    }

    private var statusBar: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(model.status == "Up to date" ? Color.green : Color.secondary)
                .frame(width: 5, height: 5)
            Text(model.pendingCount > 0 && model.connected ? "\(model.pendingCount) pending" : model.status)
            if model.recovery != nil {
                Button("Some items need recovery") { model.settingsOpen = true }
            }
            Spacer()
            Text("↑↓ navigate    Space preview    ↵ paste").foregroundStyle(Color.zapSecondary)
        }
        .font(.caption)
        .foregroundStyle(Color.zapSecondary)
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
    }
}

struct ClipRow: View {
    let clip: Clip
    var selected = false

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            thumbnail
                .frame(width: 44, height: 44)
                .background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 8))
            VStack(alignment: .leading, spacing: 5) {
                Text(clip.payload.kind == "image" ? "Image" : clip.payload.text ?? "")
                    .font(.body)
                    .lineLimit(3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                HStack(spacing: 6) {
                    Text(clip.payload.source).lineLimit(1)
                    Text("·")
                    Text(clip.date, format: .relative(presentation: .named, unitsStyle: .abbreviated)).lineLimit(1)
                    if clip.pending {
                        Image(systemName: "arrow.triangle.2.circlepath").accessibilityLabel("Pending sync")
                    }
                }
                .font(.caption)
                .foregroundStyle(selected ? Color.primary : Color.zapSecondary)
            }
        }
        .padding(.vertical, 8)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var thumbnail: some View {
        if clip.payload.kind == "image", let image = clipImage(clip) {
            Image(nsImage: image).resizable().aspectRatio(contentMode: .fit)
        } else {
            Image(systemName: "text.alignleft").font(.title3).foregroundStyle(Color.zapSecondary)
        }
    }
}

private let thumbnails: NSCache<NSString, NSImage> = {
    let cache = NSCache<NSString, NSImage>()
    cache.countLimit = 250
    return cache
}()

func clipImage(_ clip: Clip) -> NSImage? {
    let cacheKey = clip.id as NSString
    if let image = thumbnails.object(forKey: cacheKey) { return image }
    let options = [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: 160] as CFDictionary
    guard let data = Data(base64Encoded: clip.payload.png ?? ""),
          let source = CGImageSourceCreateWithData(data as CFData, nil),
          let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, options)
    else { return nil }
    let image = NSImage(cgImage: thumbnail, size: .zero)
    thumbnails.setObject(image, forKey: cacheKey)
    return image
}

struct PreviewView: View {
    let clip: Clip
    @ObservedObject var model: Model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text(clip.payload.kind == "image" ? "Image" : "Text").font(.headline)
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.cancelAction)
            }
            ScrollView {
                if let png = clip.payload.png, let data = Data(base64Encoded: png), let image = NSImage(data: data) {
                    Image(nsImage: image).resizable().aspectRatio(contentMode: .fit)
                } else {
                    Text(clip.payload.text ?? "").textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            HStack {
                Text(clip.date.formatted()).font(.caption).foregroundStyle(Color.zapSecondary)
                Spacer()
                Button("Copy") {
                    model.copy(clip)
                    dismiss()
                }
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(24)
        .frame(width: 540, height: 420)
    }
}

struct SettingsView: View {
    @ObservedObject var model: Model
    @Environment(\.dismiss) private var dismiss
    @State private var url = ""
    @State private var token = ""
    @State private var connecting = false
    @State private var joining = false
    @State private var joinCode = ""
    @State private var disconnecting = false
    @State private var clearing = false
    @State private var discarding = false
    @State private var removing: RemoteDevice?
    @State private var startup = SMAppService.mainApp.status == .enabled
    @State private var shortcut = UserDefaults.standard.string(forKey: "shortcut") ?? "option-space"

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack {
                Text("Settings").font(.title2.weight(.semibold))
                Spacer()
                Button("Done") {
                    model.pairingCode = nil
                    dismiss()
                }
                .keyboardShortcut(.cancelAction)
            }
            Form {
                historySection
                macSection
                Section(model.connected ? "Connected devices" : "Connect your server") {
                    if model.connected {
                        connectedDevices
                    } else {
                        connectForm
                    }
                }
            }
            .formStyle(.grouped)
            if let error = model.error {
                Text(error).font(.caption).foregroundStyle(.red)
            }
        }
        .padding(24)
        .frame(width: 560, height: 570)
        .sheet(isPresented: pairingCodeShown) {
            if let code = model.pairingCode { PairingView(code: code, model: model) }
        }
        .alert("Remove this device?", isPresented: removalShown, presenting: removing) { device in
            Button("Remove", role: .destructive) { Task { await model.removeDevice(device) } }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("It will no longer receive new items. Content already downloaded remains on that device.")
        }
        .confirmationDialog(
            "Disconnect from this server? This Mac's access is revoked. Local history stays here and will upload when you connect again.",
            isPresented: $disconnecting
        ) {
            Button("Disconnect") { Task { await model.disconnect() } }
        }
        .confirmationDialog(
            model.connected ? "Clear history on all connected devices?" : "Clear history on this Mac?",
            isPresented: $clearing
        ) {
            Button("Clear history", role: .destructive) { model.clear() }
        }
        .confirmationDialog(
            model.connected
                ? "Discard unreadable items here and on paired devices when you sync? Healthy items stay."
                : "Permanently discard unreadable items from this Mac? Healthy items stay.",
            isPresented: $discarding
        ) {
            Button("Discard damaged items", role: .destructive) { model.discardDamaged() }
        }
    }

    private var historySection: some View {
        Section("History") {
            Stepper("Keep for \(model.days) days", value: Binding(get: { model.days }, set: { model.setDays($0) }), in: 1...365)
            Button("Clear history…", role: .destructive) { clearing = true }
            if let recovery = model.recovery {
                Text(recovery).font(.caption)
                Button("Retry recovery") { Task { await model.sync(force: true) } }
                if !model.store.damagedIDs.isEmpty {
                    Button("Discard damaged items…", role: .destructive) { discarding = true }
                }
            }
        }
    }

    private var macSection: some View {
        Section("Mac") {
            Toggle("Launch at login", isOn: $startup)
                .onChange(of: startup) { _, enabled in setLaunchAtLogin(enabled) }
            Picker("Shortcut", selection: $shortcut) {
                Text("⌥Space").tag("option-space")
                Text("⌥C").tag("option-c")
                Text("⇧⌘V").tag("v")
                Text("⇧⌘C").tag("c")
                Text("⇧⌘Space").tag("space")
            }
            .onChange(of: shortcut) { _, value in
                UserDefaults.standard.set(value, forKey: "shortcut")
                NotificationCenter.default.post(name: .zapShortcutChanged, object: nil)
            }
        }
    }

    @ViewBuilder private var connectedDevices: some View {
        Text(model.identity.url).font(.caption).foregroundStyle(Color.zapSecondary).textSelection(.enabled)
        ForEach(model.devices) { device in
            let isThisMac = device.id == model.identity.deviceId
            HStack {
                Label(device.name, systemImage: isThisMac ? "desktopcomputer" : "laptopcomputer.and.iphone").lineLimit(1)
                Spacer()
                if isThisMac {
                    Text("This Mac").foregroundStyle(Color.zapSecondary).font(.caption)
                } else {
                    Button("Remove", role: .destructive) { removing = device }
                }
            }
        }
        Button("Pair another device…") { Task { await model.invite() } }
        Button("Sync now") { Task { await model.sync(force: true) } }
        Button("Disconnect…") { disconnecting = true }
    }

    @ViewBuilder private var connectForm: some View {
        Text("For a new server, enter the URL and setup token from the deployment script. Already using Zap? Join your existing history below.")
            .foregroundStyle(Color.zapSecondary)
        TextField("Server URL", text: $url, prompt: Text("https://zap.your-account.workers.dev"))
        SecureField("Setup token", text: $token)
        Button(connecting ? "Connecting…" : "Set up new history") { startSetup() }
            .disabled(connecting || joining || url.isEmpty || token.isEmpty)
        Divider()
        Text("On a Mac or phone that is already connected, open Settings, choose Pair another device, and paste its code below.")
            .foregroundStyle(Color.zapSecondary)
        SecureField("Pairing code", text: $joinCode, prompt: Text("zap://pair#…"))
        Button(joining ? "Joining…" : "Join existing history") { startJoin() }
            .disabled(connecting || joining || joinCode.isEmpty)
    }

    private var pairingCodeShown: Binding<Bool> {
        Binding(get: { model.pairingCode != nil }, set: { if !$0 { model.pairingCode = nil } })
    }

    private var removalShown: Binding<Bool> {
        Binding(get: { removing != nil }, set: { if !$0 { removing = nil } })
    }

    private func startSetup() {
        connecting = true
        Task {
            await model.setup(url: url, token: token)
            connecting = false
            if model.connected { token = "" }
        }
    }

    private func startJoin() {
        joining = true
        Task {
            await model.join(code: joinCode.trimmingCharacters(in: .whitespacesAndNewlines))
            joining = false
            if model.connected { joinCode = "" }
        }
    }

    private func setLaunchAtLogin(_ enabled: Bool) {
        do {
            if enabled {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
            }
        } catch {
            model.error = error.localizedDescription
            startup = SMAppService.mainApp.status == .enabled
        }
    }
}

private struct PairingView: View {
    let code: String
    @ObservedObject var model: Model

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack {
                Text("Pair another device").font(.title2.weight(.semibold))
                Spacer()
                Button("Done") { model.pairingCode = nil }.keyboardShortcut(.cancelAction)
            }
            HStack(spacing: 24) {
                if let image = qrImage(code) {
                    Image(nsImage: image)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: 190, height: 190)
                        .padding(8)
                        .background(.white)
                        .accessibilityLabel("Pairing QR code")
                }
                VStack(alignment: .leading, spacing: 12) {
                    Text("Open Zap on the new device").font(.headline)
                    Text("On Android, open Settings and scan this code. On a Mac, copy the code and paste it into Join existing history.")
                        .foregroundStyle(Color.zapSecondary)
                    Text("The invitation lets one device join within five minutes. The code also contains encryption keys that remain sensitive after expiry. Keep it private.")
                        .font(.caption)
                        .foregroundStyle(Color.zapSecondary)
                    Button("Copy pairing code") { copyCode() }
                }
            }
        }
        .padding(24)
        .frame(width: 500)
    }

    private func copyCode() {
        let board = NSPasteboard.general
        board.clearContents()
        board.setString(code, forType: .string)
        model.ignoreCurrentClipboard()
    }

    private func qrImage(_ text: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "L"
        guard let output = filter.outputImage, let image = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return NSImage(cgImage: image, size: .zero)
    }
}

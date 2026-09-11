import SwiftUI
import AppKit
import CoreImage.CIFilterBuiltins
import ServiceManagement

struct HistoryView: View {
    @ObservedObject var model: Model
    @FocusState private var searchFocused: Bool
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("Search clipboard history", text: $model.query).textFieldStyle(.plain).font(.title3).focused($searchFocused)
                Button { model.settingsOpen = true } label: { Image(systemName: "gearshape") }.buttonStyle(.plain).help("Settings").accessibilityLabel("Settings")
            }.padding(20)
            HStack {
                Picker("Content", selection: $model.filter) { ForEach(["All", "Text", "Images"], id: \.self) { Text($0) } }.pickerStyle(.segmented).labelsHidden().frame(width: 220)
                Spacer()
                Text(model.filtered.count == 1 ? "1 item" : "\(model.filtered.count) items").font(.caption).foregroundStyle(.secondary).monospacedDigit()
            }.padding(.horizontal, 20).padding(.bottom, 12)
            Divider()
            if model.filtered.isEmpty {
                ContentUnavailableView {
                    Label(model.query.isEmpty ? "Your clipboard, remembered" : "No matches", systemImage: model.query.isEmpty ? "clipboard" : "magnifyingglass")
                } description: {
                    Text(model.query.isEmpty ? "Copy text or an image to get started.\nYour history stays here for \(model.days) days." : "Try another word or switch the content filter.")
                }.frame(maxHeight: .infinity)
            } else {
                ScrollViewReader { proxy in
                    List(selection: $model.selection) {
                        ForEach(model.filtered) { clip in
                            ClipRow(clip: clip).tag(clip.id)
                                .contextMenu {
                                    Button("Copy") { model.copy(clip) }
                                    Button("Preview") { model.preview = clip }
                                    Divider()
                                    Button("Delete", role: .destructive) { model.delete(clip) }
                                }
                                .onTapGesture(count: 2) { model.copy(clip, andPaste: true) }
                        }
                    }.listStyle(.inset).onChange(of: model.selection) { _, id in if let id { proxy.scrollTo(id) } }
                }
            }
            if let error = model.error {
                HStack(alignment: .top) {
                    Image(systemName: "exclamationmark.circle")
                    Text(error).textSelection(.enabled)
                    Spacer()
                    Button { model.error = nil } label: { Image(systemName: "xmark") }.buttonStyle(.plain).accessibilityLabel("Dismiss error")
                }.font(.caption).foregroundStyle(.red).padding(12).background(.red.opacity(0.06))
            }
            Divider()
            HStack(spacing: 6) {
                Circle().fill(model.status == "Up to date" ? Color.green : Color.secondary).frame(width: 5, height: 5)
                Text(model.pendingCount > 0 && model.connected ? "\(model.pendingCount) pending" : model.status)
                Spacer()
                Text("↑↓ navigate    Space preview    ↵ paste").foregroundStyle(.secondary)
            }.font(.caption).foregroundStyle(.secondary).padding(.horizontal, 20).padding(.vertical, 12)
        }.frame(minWidth: 580, minHeight: 420).background(.background)
        .onAppear { searchFocused = true }
        .onChange(of: model.query) { _, _ in model.selection = model.filtered.first?.id }
        .onChange(of: model.filter) { _, _ in model.selection = model.filtered.first?.id }
        .sheet(isPresented: $model.settingsOpen) { SettingsView(model: model) }
        .sheet(item: $model.preview) { clip in PreviewView(clip: clip, model: model) }
    }
}
struct ClipRow: View {
    let clip: Clip
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Group {
                if clip.payload.kind == "image", let image = clipImage(clip) {
                    Image(nsImage: image).resizable().aspectRatio(contentMode: .fit)
                } else { Image(systemName: "text.alignleft").font(.title3).foregroundStyle(.secondary) }
            }.frame(width: 44, height: 44).background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 8))
            VStack(alignment: .leading, spacing: 5) {
                Text(clip.payload.kind == "image" ? "Image" : clip.payload.text ?? "").font(.body).lineLimit(3).frame(maxWidth: .infinity, alignment: .leading)
                HStack(spacing: 6) {
                    Text(clip.payload.source)
                    Text("·")
                    Text(clip.date, style: .relative)
                    if clip.pending { Image(systemName: "arrow.triangle.2.circlepath").accessibilityLabel("Pending sync") }
                }.font(.caption).foregroundStyle(.secondary)
            }
        }.padding(.vertical, 8).accessibilityElement(children: .combine)
    }
}
private let thumbnails = NSCache<NSString, NSImage>()
func clipImage(_ clip: Clip) -> NSImage? {
    if let image = thumbnails.object(forKey: clip.id as NSString) { return image }
    guard let data = Data(base64Encoded: clip.payload.png ?? ""), let source = CGImageSourceCreateWithData(data as CFData, nil), let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: 160] as CFDictionary) else { return nil }
    let image = NSImage(cgImage: cg, size: .zero); thumbnails.countLimit = 250; thumbnails.setObject(image, forKey: clip.id as NSString); return image
}
struct PreviewView: View {
    let clip: Clip
    @ObservedObject var model: Model
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack { Text(clip.payload.kind == "image" ? "Image" : "Text").font(.headline); Spacer(); Button("Done") { dismiss() }.keyboardShortcut(.cancelAction) }
            ScrollView {
                if let png = clip.payload.png, let data = Data(base64Encoded: png), let image = NSImage(data: data) {
                    Image(nsImage: image).resizable().aspectRatio(contentMode: .fit)
                } else { Text(clip.payload.text ?? "").textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading) }
            }
            HStack { Text(clip.date.formatted()).font(.caption).foregroundStyle(.secondary); Spacer(); Button("Copy") { model.copy(clip); dismiss() }.keyboardShortcut(.defaultAction) }
        }.padding(24).frame(width: 540, height: 420)
    }
}
struct SettingsView: View {
    @ObservedObject var model: Model
    @Environment(\.dismiss) private var dismiss
    @State private var url = ""
    @State private var token = ""
    @State private var busy = false
    @State private var joinCode = ""
    @State private var disconnecting = false
    @State private var clearing = false
    @State private var startup = SMAppService.mainApp.status == .enabled
    @State private var shortcut = UserDefaults.standard.string(forKey: "shortcut") ?? "v"
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack { Text("Settings").font(.title2.weight(.semibold)); Spacer(); Button("Done") { model.pairingCode = nil; dismiss() }.keyboardShortcut(.cancelAction) }
            Form {
                Section("History") {
                    Stepper("Keep for \(model.days) days", value: Binding(get: { model.days }, set: { model.setDays($0) }), in: 1...365)
                    Button("Clear history…", role: .destructive) { clearing = true }
                }
                Section("Mac") {
                    Toggle("Launch at login", isOn: $startup).onChange(of: startup) { _, value in
                        do { if value { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() } }
                        catch { model.error = error.localizedDescription; startup = SMAppService.mainApp.status == .enabled }
                    }
                    Picker("Shortcut", selection: $shortcut) { Text("⇧⌘V").tag("v"); Text("⇧⌘C").tag("c"); Text("⇧⌘Space").tag("space") }
                        .onChange(of: shortcut) { _, value in UserDefaults.standard.set(value, forKey: "shortcut"); NotificationCenter.default.post(name: .init("ZapShortcutChanged"), object: nil) }
                }
                Section(model.connected ? "Connected devices" : "Connect your server") {
                    if model.connected {
                        Text(model.identity.url).font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
                        ForEach(model.devices) { device in
                            HStack { Label(device.name, systemImage: device.id == model.identity.deviceId ? "desktopcomputer" : "iphone"); Spacer()
                                if device.id != model.identity.deviceId { Button("Remove", role: .destructive) { Task { await model.removeDevice(device) } } }
                            }
                        }
                        Button("Pair Android…") { Task { await model.invite() } }
                        Button("Sync now") { Task { await model.sync(force: true) } }
                        Button("Disconnect…") { disconnecting = true }
                    } else {
                        Text("Deploy Zap to your Cloudflare account, then enter the URL and setup token printed by the deployment script.").foregroundStyle(.secondary)
                        TextField("Server URL", text: $url, prompt: Text("https://zap.your-account.workers.dev"))
                        SecureField("Setup token", text: $token)
                        Button(busy ? "Connecting…" : "Connect") { busy = true; Task { await model.setup(url: url, token: token); busy = false; if model.connected { token = "" } } }.disabled(busy || url.isEmpty || token.isEmpty)
                        Divider()
                        SecureField("Pairing code from another device", text: $joinCode)
                        Button("Join existing history") { busy = true; Task { await model.join(code: joinCode.trimmingCharacters(in: .whitespacesAndNewlines)); busy = false; if model.connected { joinCode = "" } } }.disabled(busy || joinCode.isEmpty)
                    }
                }
            }.formStyle(.grouped)
            if let code = model.pairingCode {
                HStack(spacing: 16) {
                    if let image = qrImage(code) { Image(nsImage: image).interpolation(.none).resizable().frame(width: 190, height: 190).padding(8).background(.white).accessibilityLabel("Android pairing QR code") }
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Open Zap on Android").font(.headline)
                        Text("Choose Scan pairing code.\nThis code expires in five minutes.").foregroundStyle(.secondary)
                        Button("Copy pairing code") {
                            let board = NSPasteboard.general; board.clearContents(); board.setString(code, forType: .string)
                            model.ignoreCurrentClipboard()
                        }
                    }
                }
            }
            if let error = model.error { Text(error).font(.caption).foregroundStyle(.red) }
        }.padding(24).frame(width: 560, height: model.pairingCode == nil ? 570 : 780)
        .confirmationDialog("Disconnect from this server? Local history stays on this Mac and will upload when you connect again.", isPresented: $disconnecting) { Button("Disconnect") { model.disconnect() } }
        .confirmationDialog("Clear history on all connected devices?", isPresented: $clearing) { Button("Clear history", role: .destructive) { model.clear() } }
    }
    func qrImage(_ text: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator(); filter.message = Data(text.utf8); filter.correctionLevel = "L"
        guard let output = filter.outputImage, let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return NSImage(cgImage: cg, size: .zero)
    }
}

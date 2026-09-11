import AppKit
import SwiftUI
import Carbon

@main struct ZapApp {
    @MainActor static func main() {
        let application = NSApplication.shared
        let delegate = AppDelegate()
        application.delegate = delegate
        withExtendedLifetime(delegate) { application.run() }
    }
}
@MainActor final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate {
    private var model: Model!
    private var window: NSPanel!
    private var menuItem: NSStatusItem!
    private var hotkey: EventHotKeyRef?
    private var previousApp: NSRunningApplication?
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        do { model = try Model() } catch {
            let alert = NSAlert(); alert.messageText = "Zap couldn’t open"; alert.informativeText = error.localizedDescription; alert.runModal(); NSApp.terminate(nil); return
        }
        window = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 680, height: 580), styleMask: [.titled, .closable, .resizable, .fullSizeContentView], backing: .buffered, defer: false)
        window.title = "Zap"; window.titleVisibility = .hidden; window.titlebarAppearsTransparent = true
        window.isReleasedWhenClosed = false; window.level = .floating; window.delegate = self
        window.contentView = NSHostingView(rootView: HistoryView(model: model)); window.center()
        menuItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        menuItem.button?.image = NSImage(systemSymbolName: "clipboard", accessibilityDescription: "Zap clipboard history")
        menuItem.button?.target = self; menuItem.button?.action = #selector(toggle)
        menuItem.button?.sendAction(on: [.leftMouseUp, .rightMouseUp])
        model.dismiss = { [weak self] in self?.window.orderOut(nil) }
        model.paste = { [weak self] in self?.pasteSelection() }
        var event = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        InstallEventHandler(GetApplicationEventTarget(), { _, _, ref in
            guard let ref else { return noErr }
            let delegate = Unmanaged<AppDelegate>.fromOpaque(ref).takeUnretainedValue()
            Task { @MainActor in delegate.toggle() }; return noErr
        }, 1, &event, Unmanaged.passUnretained(self).toOpaque(), nil)
        registerShortcut()
        NotificationCenter.default.addObserver(forName: .init("ZapShortcutChanged"), object: nil, queue: .main) { [weak self] _ in guard let self else { return }; Task { @MainActor in self.registerShortcut() } }
        NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard let self, self.window.isKeyWindow, !self.model.settingsOpen, self.model.preview == nil else { return event }
            switch event.keyCode {
            case 53: self.window.orderOut(nil); return nil
            case 125: self.model.move(1); return nil
            case 126: self.model.move(-1); return nil
            case 36: if let clip = self.model.selected { self.model.copy(clip, andPaste: true) }; return nil
            case 49 where self.model.query.isEmpty: self.model.preview = self.model.selected; return nil
            case 8 where event.modifierFlags.contains(.command): if let clip = self.model.selected { self.model.copy(clip) }; return nil
            default: return event
            }
        }
        model.start()
        if !UserDefaults.standard.bool(forKey: "hasOpened") { toggle(); UserDefaults.standard.set(true, forKey: "hasOpened") }
    }
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool { show(); return true }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
    func registerShortcut() {
        if let hotkey { UnregisterEventHotKey(hotkey) }
        let choice = UserDefaults.standard.string(forKey: "shortcut") ?? "v"
        let code = choice == "space" ? 49 : choice == "c" ? 8 : 9
        let result = RegisterEventHotKey(UInt32(code), UInt32(cmdKey | shiftKey), EventHotKeyID(signature: 0x5A415020, id: 1), GetApplicationEventTarget(), 0, &hotkey)
        if result != noErr { model.error = "This shortcut is already in use. Choose another in Settings." }
    }
    @objc func toggle() {
        if NSApp.currentEvent?.type == .rightMouseUp {
            let menu = NSMenu(); let open = menu.addItem(withTitle: "Open history", action: #selector(show), keyEquivalent: ""); open.target = self
            let quit = menu.addItem(withTitle: "Quit Zap", action: #selector(quit), keyEquivalent: ""); quit.target = self
            menuItem.menu = menu; menuItem.button?.performClick(nil); menuItem.menu = nil; return
        }
        if window.isVisible && window.isKeyWindow { window.orderOut(nil) } else { show() }
    }
    @objc func show() {
        if NSWorkspace.shared.frontmostApplication?.bundleIdentifier != Bundle.main.bundleIdentifier { previousApp = NSWorkspace.shared.frontmostApplication }
        model.query = ""; model.selection = model.filtered.first?.id
        NSApp.activate(ignoringOtherApps: true); window.makeKeyAndOrderFront(nil)
        Task { await model.sync(force: true) }
    }
    @objc func quit() { NSApp.terminate(nil) }
    func pasteSelection() {
        window.orderOut(nil); previousApp?.activate(options: [])
        guard AXIsProcessTrusted() else {
            let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
            _ = AXIsProcessTrustedWithOptions(options); model.status = "Copied · enable Accessibility to paste"; return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
            let source = CGEventSource(stateID: .combinedSessionState)
            let down = CGEvent(keyboardEventSource: source, virtualKey: 9, keyDown: true), up = CGEvent(keyboardEventSource: source, virtualKey: 9, keyDown: false)
            down?.flags = .maskCommand; up?.flags = .maskCommand; down?.post(tap: .cghidEventTap); up?.post(tap: .cghidEventTap)
        }
    }
}

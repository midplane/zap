import AppKit
import SwiftUI
import Carbon

extension Notification.Name {
    static let zapShortcutChanged = Notification.Name("ZapShortcutChanged")
}

@main struct ZapApp {
    @MainActor static func main() {
        let application = NSApplication.shared
        let delegate = AppDelegate()
        application.delegate = delegate
        withExtendedLifetime(delegate) { application.run() }
    }
}

/// Borderless windows refuse key status unless they say otherwise, and the panel is keyboard-first.
final class HistoryPanel: NSPanel {
    override var canBecomeKey: Bool { true }
}

@MainActor final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate {
    private var model: Model!
    private var window: HistoryPanel!
    private var menuItem: NSStatusItem!
    private var hotkey: EventHotKeyRef?
    private var previousApp: NSRunningApplication?

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        installMainMenu()
        do {
            model = try Model()
        } catch {
            let alert = NSAlert()
            alert.messageText = "Zap couldn’t open"
            alert.informativeText = error.localizedDescription
            alert.runModal()
            NSApp.terminate(nil)
            return
        }
        window = makePanel()
        installStatusItem()
        model.paste = { [weak self] in self?.pasteSelection() }
        installHotKeyHandler()
        registerShortcut()
        NotificationCenter.default.addObserver(forName: .zapShortcutChanged, object: nil, queue: .main) { [weak self] _ in
            guard let self else { return }
            Task { @MainActor in self.registerShortcut() }
        }
        installKeyboardNavigation()
        model.start()
        if !UserDefaults.standard.bool(forKey: "hasOpened") {
            toggle()
            UserDefaults.standard.set(true, forKey: "hasOpened")
        }
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        show()
        return true
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    /// The menu bar stays hidden for an accessory app, but its key equivalents still drive editing in text fields.
    private func installMainMenu() {
        let mainMenu = NSMenu()
        let appMenu = NSMenu()
        appMenu.addItem(withTitle: "Quit Zap", action: #selector(quit), keyEquivalent: "q").target = self
        mainMenu.addItem(withTitle: "Zap", action: nil, keyEquivalent: "").submenu = appMenu

        let editMenu = NSMenu(title: "Edit")
        let editCommands = [("Undo", "undo:", "z"), ("Cut", "cut:", "x"), ("Copy", "copy:", "c"), ("Paste", "paste:", "v"), ("Select All", "selectAll:", "a")]
        for (title, action, key) in editCommands {
            editMenu.addItem(withTitle: title, action: Selector(action), keyEquivalent: key)
        }
        mainMenu.addItem(withTitle: "Edit", action: nil, keyEquivalent: "").submenu = editMenu
        NSApp.mainMenu = mainMenu
    }

    private func makePanel() -> HistoryPanel {
        let panel = HistoryPanel(
            contentRect: NSRect(x: 0, y: 0, width: 680, height: 580),
            styleMask: [.borderless, .resizable],
            backing: .buffered,
            defer: false
        )
        panel.title = "Zap"
        panel.isReleasedWhenClosed = false
        panel.level = .floating
        panel.delegate = self
        panel.isMovableByWindowBackground = true
        panel.backgroundColor = .clear
        panel.isOpaque = false
        panel.hasShadow = true

        let content = NSHostingView(rootView: HistoryView(model: model))
        content.wantsLayer = true
        content.layer?.cornerRadius = 12
        content.layer?.masksToBounds = true
        panel.contentView = content
        panel.center()
        return panel
    }

    private func installStatusItem() {
        menuItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        guard let button = menuItem.button else { return }
        button.image = NSImage(systemSymbolName: "clipboard", accessibilityDescription: "Zap clipboard history")
        button.target = self
        button.action = #selector(toggle)
        button.sendAction(on: [.leftMouseUp, .rightMouseUp])
    }

    /// Routes Carbon hot key presses to toggle(). The delegate lives as long as the app, so an unretained pointer is safe.
    private func installHotKeyHandler() {
        var eventType = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        InstallEventHandler(GetApplicationEventTarget(), { _, _, userData in
            guard let userData else { return noErr }
            let delegate = Unmanaged<AppDelegate>.fromOpaque(userData).takeUnretainedValue()
            Task { @MainActor in delegate.toggle() }
            return noErr
        }, 1, &eventType, Unmanaged.passUnretained(self).toOpaque(), nil)
    }

    private func installKeyboardNavigation() {
        NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard let self, self.window.isKeyWindow, !self.model.settingsOpen, self.model.preview == nil else { return event }
            return self.handleKey(event) ? nil : event
        }
    }

    private func handleKey(_ event: NSEvent) -> Bool {
        switch Int(event.keyCode) {
        case kVK_Escape:
            window.orderOut(nil)
        case kVK_DownArrow:
            model.move(1)
        case kVK_UpArrow:
            model.move(-1)
        case kVK_Return:
            if let clip = model.selected { model.copy(clip, andPaste: true) }
        case kVK_Space where model.query.isEmpty:
            model.preview = model.selected
        case kVK_ANSI_C where event.modifierFlags.contains(.command):
            if let clip = model.selected { model.copy(clip) }
        default:
            return false
        }
        return true
    }

    func registerShortcut() {
        if let hotkey { UnregisterEventHotKey(hotkey) }
        hotkey = nil

        // Values match the tags of the Shortcut picker in Settings.
        let choice = UserDefaults.standard.string(forKey: "shortcut") ?? "option-space"
        let keyCode: Int
        switch choice {
        case "space", "option-space": keyCode = kVK_Space
        case "v": keyCode = kVK_ANSI_V
        default: keyCode = kVK_ANSI_C
        }
        let modifiers = choice.hasPrefix("option-") ? optionKey : cmdKey | shiftKey
        let hotKeyID = EventHotKeyID(signature: 0x5A415020, id: 1) // "ZAP "
        let result = RegisterEventHotKey(UInt32(keyCode), UInt32(modifiers), hotKeyID, GetApplicationEventTarget(), 0, &hotkey)
        if result != noErr { model.error = "This shortcut is already in use. Choose another in Settings." }
    }

    @objc func toggle() {
        if NSApp.currentEvent?.type == .rightMouseUp {
            showStatusMenu()
            return
        }
        if window.isVisible && window.isKeyWindow {
            window.orderOut(nil)
        } else {
            show()
        }
    }

    /// Attaches the menu only for this click, so left clicks keep toggling the panel.
    private func showStatusMenu() {
        let menu = NSMenu()
        menu.addItem(withTitle: "Open history", action: #selector(show), keyEquivalent: "").target = self
        menu.addItem(withTitle: "Quit Zap", action: #selector(quit), keyEquivalent: "").target = self
        menuItem.menu = menu
        menuItem.button?.performClick(nil)
        menuItem.menu = nil
    }

    @objc func show() {
        let frontmost = NSWorkspace.shared.frontmostApplication
        if frontmost?.bundleIdentifier != Bundle.main.bundleIdentifier { previousApp = frontmost }
        model.query = ""
        model.selection = model.filtered.first?.id
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        Task { await model.sync(force: true) }
    }

    @objc func quit() { NSApp.terminate(nil) }

    func pasteSelection() {
        window.orderOut(nil)
        previousApp?.activate(options: [])
        guard AXIsProcessTrusted() else {
            let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
            _ = AXIsProcessTrustedWithOptions(options)
            model.status = "Copied · enable Accessibility to paste"
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
            let source = CGEventSource(stateID: .combinedSessionState)
            let keyV = CGKeyCode(kVK_ANSI_V)
            let down = CGEvent(keyboardEventSource: source, virtualKey: keyV, keyDown: true)
            let up = CGEvent(keyboardEventSource: source, virtualKey: keyV, keyDown: false)
            down?.flags = .maskCommand
            up?.flags = .maskCommand
            down?.post(tap: .cghidEventTap)
            up?.post(tap: .cghidEventTap)
        }
    }
}

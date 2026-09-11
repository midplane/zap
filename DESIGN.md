---
name: Zap
description: A quiet, compact native clipboard history.
colors:
  android-primary-light: "#245AC5"
  android-primary-dark: "#A8C7FA"
  android-primary-container-light: "#DCE8FF"
  android-primary-container-dark: "#173E78"
  android-on-primary-container-light: "#123A73"
  android-on-primary-container-dark: "#DCE8FF"
  android-secondary-container-light: "#E6EAF1"
  android-secondary-container-dark: "#343D4B"
  android-on-secondary-container-light: "#394453"
  android-on-secondary-container-dark: "#E0E6F0"
  android-surface-light: "#FAFAFC"
  android-surface-dark: "#121419"
rounded:
  mac-thumbnail: "8pt"
  android-search: "16dp"
---

# Zap design

## Overview

An everyday native utility: a quiet, compact reading list with a generous preview. Preserve the approved vertical search panel and native code-first workflow. Content, selection, and keyboard focus carry hierarchy. SwiftUI/AppKit and Jetpack Compose are the visual source of truth.

## Colors

The app mark is two offset paper slips: warm white (`#F2F4E9`) behind lime (`#DFF279`) on ink (`#182C3B`). It appears in the Mac application icon, Android adaptive/themed launcher assets, and the Android history header. Brand artwork does not replace native control colors.

Mac uses semantic system backgrounds, primary text, selection, and control colors. Its explicit `zapSecondary` uses opaque neutral white components of 0.34 in light appearance and 0.75 in dark appearance. Apply it to the search prompt, search icon, metadata, item count, footer hints, and supporting instructions. Selected row metadata uses primary text to remain readable against native selection.

The search prompt is a visible overlay in `zapSecondary`; it ignores pointer events and is hidden from accessibility because the text field already has its own label. Preserve this treatment when editing search.

Android uses the light/dark blue and neutral palette above, selected by system appearance. Background equals surface. Primary containers support the clipboard action; secondary containers distinguish selected filters. Other foreground, error, outline, and surface roles retain Material 3 defaults. Small supporting text uses `onSurfaceVariant` without reduced opacity; only list dividers reduce `outlineVariant` opacity to 0.45.

## Typography

Mac uses SF system styles: `title3` for search and text thumbnails, `body` for excerpts, `caption` for metadata and status, `headline` for preview headings, and semibold `title2` for Settings. Item counts use monospaced digits. Preserve semantic text styles.

Android keeps the Material 3 system type scale and font scaling: `headlineSmall` for the app bar, `titleLarge` for empty-state and preview headings, `titleMedium` for settings sections, `bodyLarge` for preview text, `bodyMedium` for explanations, and `labelMedium` for dates and status. List rows inherit Material typography.

## Layout

Mac starts at 680 × 580 pt and supports a 580 × 420 pt minimum. Search and footer align to 20 pt horizontal insets. Rows use 12 pt between thumbnail and text, 8 pt vertical padding, 5 pt between excerpt and metadata, and 6 pt between metadata elements. Thumbnails occupy 44 × 44 pt. The filter control is 220 pt wide. Preview sheets are 540 × 420 pt with 24 pt padding and 16 pt spacing; Settings uses the same padding.

Android centers history within 760 dp and settings within 680 dp. Search and filters use 20 dp horizontal insets; filters have 8 dp gaps. Settings and preview use 24 dp padding, with 20 dp settings section gaps and 16 dp preview gaps. Other recurring gaps are 4, 8, and 12 dp; empty states use 32 dp outer padding. Thumbnails occupy 48 dp. Lists reserve 100 dp bottom space for the clipboard action. Scaffold and preview respect system bar insets.

## Elevation & Depth

Lists remain flat, with native selection, subtle thumbnail backing on Mac, and inset dividers on Android. Sheets, dialogs, controls, and the Android floating action use platform elevation and presentation. No custom shadows or decorative effects are defined. Keep motion native and respect platform reduced-motion behavior.

## Shapes

Only Mac thumbnails and Android search have custom corner radii, recorded above. List selection, filters, buttons, fields, sheets, and dialogs retain their platform shapes. Images fit their bounds while preserving aspect ratio; previews remain scrollable.

## Components

- **Search and filters:** focused search on Mac; All, Text, and Images use a segmented picker on Mac and filter chips on Android. Query or filter changes select the first Mac result. Android offers a clear-search action.
- **History rows:** three-line text excerpts with source and relative date. Mac supports native selection, a combined accessibility element, pending-sync labels, and a Copy/Preview/Delete context menu. Android opens preview on row tap and exposes a separate labeled Copy action.
- **Preview:** Mac uses a sheet with selectable text, Done, and Copy. Android uses a bottom sheet with selectable text and Copy, Share, and Delete actions.
- **Keyboard and navigation:** Mac shows arrow-key navigation, Space preview, and Return paste hints; double-click also pastes. Sheets use native default and cancel actions. Android Settings supports both toolbar Back and system Back.
- **Identity and settings:** Android's app bar combines a 44 dp brand mark, semibold app name, and a small Clipboard history subtitle. Sync, pairing, and disconnect use full-width native settings rows with leading icons and supporting descriptions. Mac pairing opens a separate compact sheet; settings remain 560 × 570 pt.
- **Feedback:** distinguish first capture, empty search, pending sync, and errors. Mac uses a persistent status footer and dismissible inline errors; Android uses snackbars for actions and errors. Destructive history clearing and disconnecting use native confirmations.

## Do's and Don'ts

- Do preserve compact native list density, readable secondary text, accessible control labels, and system focus/touch behavior.
- Do keep code and native captures authoritative when refreshing this document.
- Don't introduce decorative dashboards, promotional copy, web styling conventions, or a new visual direction.

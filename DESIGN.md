# Zap design

## Direction

An everyday native utility: a quiet, compact reading list with a generous preview. The approved vertical search panel and native code-first workflow govern the design. Content, selection, and keyboard focus carry hierarchy; no decorative dashboard, promotional copy, or visual effects.

## Tokens

- Typography: SF system on Mac; Material 3 system scale on Android.
- Accent: blue, light #245AC5, dark #A8C7FA. Semantic system surfaces and foreground colors adapt to appearance.
- Spacing: 4, 8, 12, 16, 24, 32 points/dp.
- Shape: 12-point preview corners; native field, button, and selection shapes.
- Mac panel: 680 × 580, searchable list with a footer for keyboard hints and connection status.
- Android: inset-aware top bar, search, filter chips, content list, one Add from clipboard action.
- Motion: native selection and presentation; respect platform reduced-motion settings.

## Behavior

Text previews use a readable three-line excerpt. Images retain their aspect ratio. Date and source are secondary. Empty search results, first capture, offline queues, and errors are distinct states. Controls have accessible labels and native focus/touch behavior.

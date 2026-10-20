# AGENTS.md — Repository hard rules

## MUST NOT use WebView

- **ABSOLUTE RULE: Do not use Android `WebView` anywhere in this project.** This is a hard constraint, not a preference.
- Do not add, instantiate, configure, wrap, subclass, or otherwise use `android.webkit.WebView` or any WebView-based component/library.
- Do not build an embedded-browser login flow, load website pages in a WebView, or use WebView JavaScript bridges, injected JavaScript, DOM/localStorage scraping, or WebView cookies to obtain authentication/session tokens.
- Do not reintroduce WebView code as a workaround, fallback, hidden screen, dialog, or dependency.
- Before finishing a change, search the changed code for `WebView`, `android.webkit`, and WebView-based dependencies. Remove any prohibited use introduced by the change.
- For website/API access, use the app's native HTTP(S) networking and supported API endpoints. If authentication is required, use a documented native authentication/API flow; do not extract credentials from an embedded browser. If no compliant authentication flow is available, report the limitation instead of using WebView.

These rules take precedence over convenience and must be followed for all future changes in this repository.

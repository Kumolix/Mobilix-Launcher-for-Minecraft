version: 1.9.8
What's new
- Online over LAN: ZeroTier is built into the launcher (no extra app): collapsible cards (turn on, connected networks, my networks via ZeroTier Central with a Legacy-account token, connection); auto-join / auto-authorize; only the status, the on/off button and the "online" counters refresh on the page (no rebuilding); friends' IPs can be copied; in game a ↘ "Share IP" control button and the [ZeroTier] IP: line in chat
- Launcher updates (Settings → Launcher): Off / Notify / Download
- Control macros, new launcher icon, links to the website, GitHub (Android / Windows), Telegram and TikTok
- Minecraft 26.1 / 26.2: LWJGL 3.4.x natives (first from yuyi2439/AndroidLWJGL), built-in OpenAL, tinyfd, JNA from the app, silent JNA fallback, Amethyst-style JVM flags
- Fixed: games with LWJGL 3.3.x (1.19–1.20.x, Forge 1.20.1: "Failed to locate library: liblwjgl.so") now use the working LWJGL 3.3.3
- Corelix: optimization loader (latest release is downloaded and cached automatically, sha256 checked) as a separate "Corelix" section in the version list
- Crash auto-fix: Fabric messages like "Mod X requires any 0.9.x version of mod Y" are recognized; the launcher installs the matching version of Y, or disables X if there is none
- Minecraft 26.3+ is hidden from the version lists

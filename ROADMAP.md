# Roadmap

Planned work that isn't built yet. Each entry says what it is, what's still to find out, and where it would fit.

## Dispatcharr playlist source

**Status:** done (Add playlist › DISPATCHARR). Checked against Dispatcharr 0.34.

How it works, and what was found out:

1. **Login.** Dispatcharr's API uses SimpleJWT: `POST /api/accounts/token/` with username and password gives an
   access token (30 minutes) and a refresh token (`POST /api/accounts/token/refresh/`). API keys also work, in an
   `X-API-Key` header (generated in Dispatcharr's user settings). IMD TV signs in once, when the playlist is added or
   edited, and keeps no token, so there is nothing to refresh.
2. **Interface: the Xtream Codes-compatible API.** It serves categories, channels (number, group, logo, catch-up),
   movies, series and `xmltv.php`, so the playlist runs on `XtreamSource` and gets everything Xtream playlists have.
   Its catch: it doesn't take the login password but the user's **XC password**, which an admin sets on the user in
   Dispatcharr (Users › edit user). `GET /api/accounts/users/me/` returns it to the signed-in user, so the sign-in
   reads it and the playlist saves username + XC password (encrypted, like Xtream logins; never in backups).
   A user without an XC password gets a message saying how to set one. If an admin changes it, Edit playlist signs in again.
   Logos (`/api/channels/logos/<id>/cache/`) need no login.
3. **Fit.** `ProviderType.DISPATCHARR` (`xtream` covers both it and XTREAM), `DispatcharrAuth` in
   `data/source/Dispatcharr.kt`, built by `AppContainer.sourceFor` as an `XtreamSource`. Backups ask for the Dispatcharr
   login on restore and sign in again; device sync passes the saved XC login like other playlists.
4. **Tests.** `DispatcharrTest`: sign-in by password, API key, API key in the password box, wrong login, missing XC
   password, blocked by network settings, not Dispatcharr, addresses copied from the browser, and channels through its
   Xtream API, against a MockWebServer copy of Dispatcharr's responses.

Possible later: Dispatcharr's REST API (`/api/channels/...`) for channel profiles or stream stats, if wanted.

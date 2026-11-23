# Roadmap

Planned work that isn't built yet. Each entry says what it is, what's still to find out, and where it would fit.

## Dispatcharr playlist source

**Status:** planned, not started.

[Dispatcharr](https://github.com/Dispatcharr/Dispatcharr) is a self-hosted IPTV stream and EPG manager. Users who run
one should be able to add it like any other playlist:

- Settings › Playlists › Add playlist › **Dispatcharr**: server URL plus username and password (or an API key, if
  Dispatcharr offers one).
- IMD TV logs in through the Dispatcharr API and adds the server as a playlist source: channels, groups (categories),
  channel logos and the TV guide (EPG).
- It syncs on the usual schedule (Update playlists every) and on demand, like Xtream and M3U playlists.

### To look into before building

1. **Login and token refresh.** How Dispatcharr authenticates API clients: which endpoint issues a token, what kind
   (for example JWT access + refresh), how long they last, and how to refresh one without asking for the password
   again. Whether there are API keys or per-user tokens that suit a TV better than a stored password.
   Wherever it lands, the password or token is stored encrypted (`data/security/Secrets`) and never backed up, as
   Xtream logins are.
2. **Which interface to use.** Dispatcharr can serve:
   - an **Xtream Codes-compatible** API: we could reuse `XtreamSource` almost as is (login, categories, streams,
     `xmltv.php`), with the least new code;
   - **M3U and XMLTV (EPG) URLs**: reuse `M3uSource` plus the EPG importer; simple, but it has no categories beyond
     `group-title` and may need the login in the URL;
   - **its own REST API**: the most detail (channel numbers, groups, logo ids, stream profiles), but a new source and
     more to maintain as Dispatcharr changes.

   Compare them on: login handling (the token in a header vs in the URL), how much a sync downloads, channel numbers
   and group order, logos, catch-up, and how stable each interface is between Dispatcharr versions.
   A likely first step is the Xtream-compatible route behind a "Dispatcharr" form, which only needs the server URL and
   login, then the REST API if it turns out to give something the others can't.
3. **Fitting the playlist source setup.** A new `ProviderType.DISPATCHARR` and a `PlaylistSource` implementation
   (`data/source/`), chosen in `AppContainer.sourceFor`. Its `epgUrls()` should feed the existing EPG importer, so the
   guide, programme images and matching work unchanged. Also check: the add/edit form (`ui/ProviderFormScreen.kt`),
   `CatalogueSync`, backup/restore (`data/backup/Backup.kt`, without secrets), and the Xtream refusal logging for
   login failures.
4. **Testing.** A `DispatcharrSourceTest` against a MockWebServer copy of real responses, like `XtreamSourceTest`.

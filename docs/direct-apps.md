# Direct apps

Bots reach your apps without a broker in between. This server signs in to each app once and hands
its tools to the bots you switch it on for. Anything that changes data waits for your approval.

## Where the apps come from

| Source | What you get | Setup |
|---|---|---|
| Hermes MCP catalog | Every vendor-hosted app in `optional-mcps/` of the Hermes install on this machine (Plaid, Notion, Stripe, Linear and more) | None when Hermes lives under `~/.hermes/hermes-agent` or `~/hermes-agent`; otherwise set `CONNECTOR_CATALOG_DIR` |
| Google Workspace | Gmail, Calendar and Sheets | Your own Google Cloud OAuth client, below |
| Add your own | Any MCP server with an `https://` address | Connect Apps, "Add your own" |

## Connecting

Open Connect Apps and press Connect. Depending on the app this opens its sign-in page, asks for an
API key in place, or connects at once. A connected app shows each bot's avatar: click one to let
that bot use the app, click again to take it away. An app a bot asked for in chat is switched on
for that bot when you connect it.

The sign-in page sends you back to `SERVER_URL/api/apps/oauth/callback`, so `SERVER_URL` must be
the address your browser uses for this server. Most apps, Google included, only accept `https://`
addresses or `http://localhost`. On a home machine, Tailscale Serve gives you an `https://` address.

## Google Workspace

Google has no hosted server for Gmail, Calendar and Sheets that an app like this can sign in to, so
you bring your own OAuth client:

1. In the Google Cloud console, create a project and enable the Gmail, Google Calendar and Google
   Sheets APIs.
2. Set up the OAuth consent screen (External) and add yourself as a test user.
3. Create an OAuth client of type "Web application" with the authorised redirect URI
   `<SERVER_URL>/api/apps/oauth/callback`.
4. Put its id and secret in `.env` as `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`, restart, and
   connect Google Workspace in Connect Apps.

While the consent screen is in "Testing", Google ends the sign-in after seven days; publish the
app (it can stay unverified for your own use) to keep it.

## What is stored

Tokens, client registrations and API keys are kept in the app's SQLite database, encrypted with a
key derived from `SESSION_SECRET`. Changing `SESSION_SECRET` means signing in to each app again.

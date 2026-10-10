# VLogin

Dialog login/register for Paper 1.21.7+ (clients 1.21.6+) with IP sessions, Discord verification,
premium + Bedrock auto login, Discord commands, social links and webhook logs. Optional Velocity plugin.

## Build
    mvn package      # Java 21 + Maven
    paper/target/VLogin-Paper-1.0.0.jar
    velocity/target/VLogin-Velocity-1.0.0.jar
(or push to GitHub, the included workflow builds both)

## Standalone server (no proxy)
1. Put VLogin-Paper in plugins/, start once, stop.
2. plugins/VLogin/discord.yml -> bot token. plugins/VLogin/config.yml -> webhook.
3. Leave `velocity: false`.

## Behind Velocity
1. velocity.toml: online-mode = false, player-info-forwarding-mode = "modern".
   Paper: config/paper-global.yml -> proxies.velocity: enabled: true, online-mode: true, secret: <forwarding.secret>.
   Backend server.properties: online-mode=false.
2. Put VLogin-Velocity in the proxy's plugins/ and VLogin-Paper on the LOGIN server only.
3. plugins/vlogin/config.properties (proxy): auth-server=<login server name>, gate=true.
4. VLogin config.yml on the login server: velocity: true.

## Who skips the login
- Verified players (linked Discord) on their last known IP.
- Premium players (/premium, needs Velocity) - Mojang verifies them at the proxy.
- Bedrock players (Geyser + Floodgate), automatically.
- Everyone on a standalone online-mode=true server.
- Anyone from the same IP within 5 hours of their last login.

## In game
/login /register (fallback) | /freemium | /unfreemium [discord user] | /premium | /unpremium | /ping [player] | /playersocial [player]

## Discord
/freemium code | /unfreemium | /password | /ping | /account | /kick | /premium on|off | /mc <command>
/linkyoutube url | /linktiktok url | /linkinstagram url   (use "remove" as the url to clear)
Commands that act on an account have an optional `account` option if you linked several.
DM the bot "+spawn" to run /spawn in game (allowed commands are set in discord.yml).

## Troubleshooting: "I log in but can't join other servers"
1. Hub config.yml must have `velocity: true` (restart after changing).
2. The proxy console should print "<name> is logged in, gate released" right after a login. If it doesn't,
   the Paper plugin can't reach the proxy plugin: check `velocity plugins` lists VLogin and the hub log has no
   "proxy never registered" warning.
3. `auth-server` in the proxy's plugins/vlogin/config.properties must match the server name in velocity.toml.
4. Temporary workaround while testing: set `gate=false` in that file (players can then skip the login by switching server).

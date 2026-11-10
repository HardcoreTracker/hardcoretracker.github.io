# Hardcore Tracker server mod

A small **server-side** Fabric mod for Minecraft **26.3**. It sends deaths and Wilderness Bound quest progress to
[hardcoretracker.github.io](https://hardcoretracker.github.io) automatically. Players don't install anything.

## What it does

- **Deaths:** when a player dies, it logs who died, the cause ("Shot by Skeleton") and the time, works out the points for the current age (plus 1 if the totem farm is on), and updates the site. The death log, points, punishments, chopping block, Hall of Shame and "Survived" timer all update about a minute later.
- **Shared life:** after a death is logged, any other deaths in the next 30 seconds are ignored, so the shared-life chain only counts once.
- **Quest progress:** every 5 minutes it checks how many Wilderness Bound quests are done and updates the age and current quest on the site.
- **Never loses a death:** updates wait in a queue saved on the server (`config/hardcoretracker-queue.json`). If GitHub is down or the server stops, they're sent later.

It only ever talks to GitHub. It doesn't collect anything about players besides their name and how they died.

Things it does **not** do: the totem farm setting, reverted deaths, punishments and the current punishment are still updated by hand.

## Install (server owner)

1. **Download** `hardcore-tracker.jar` from the
   [latest release](https://github.com/HardcoreTracker/hardcoretracker.github.io/releases/tag/mod-latest).
2. **Stop the server**, put the jar in the server's `mods` folder (next to Fabric API and Wilderness Bound), and start it once.
   This creates `config/hardcoretracker.json`.
3. **Stop the server again** and open `config/hardcoretracker.json`. Paste the GitHub token (see below) between the quotes:
   ```json
   "githubToken": "github_pat_...",
   ```
   Leave everything else as it is.
4. **Start the server.** The console should say `[HardcoreTracker] Ready. Sending updates to HardcoreTracker/hardcoretracker.github.io.`

## Making the GitHub token (site owner)

The token lets the mod edit the website's data file and nothing else.

1. On GitHub, go to **Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token**.
2. **Resource owner:** `HardcoreTracker`. **Repository access:** *Only select repositories* → `hardcoretracker.github.io`.
3. **Permissions → Repository permissions → Contents:** *Read and write*. Leave everything else as *No access*.
4. Pick an expiration (up to a year), generate it, and copy it.
5. Send it to the server owner **privately**. Never post it in a public channel or put it on the website.

If `HardcoreTracker` doesn't show up as a resource owner, open the organization's **Settings → Personal access tokens** and allow fine-grained tokens first.

If the token ever leaks, delete it on GitHub and make a new one.

## Settings (`config/hardcoretracker.json`)

| Setting | Default | What it does |
|---|---|---|
| `enabled` | `true` | Set to `false` to pause all updates. |
| `githubToken` | `""` | The token from above. |
| `repository` | `HardcoreTracker/hardcoretracker.github.io` | The website repository. |
| `ignoreDeathsWithinSeconds` | `30` | Deaths this soon after a logged one are ignored (shared life). |
| `syncQuestProgress` | `true` | Keep the site's age and quest in sync with Wilderness Bound. |
| `questSyncMinutes` | `5` | How often to check quest progress. |

## Troubleshooting

- **"GitHub refused access (HTTP 401/403/404)"**: the token is wrong, expired, or doesn't have *Contents: Read and write* on the repository.
- **A death was logged that shouldn't count:** tell the site owner to mark it as reverted.
- **Building it yourself:** `cd mod && gradle build` with Java 25 and Gradle 9.8. GitHub builds it automatically on every change.

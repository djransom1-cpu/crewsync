# Crewsync MCP connector

`mcp.js` + `oauth.js` are the server that lets Claude (or any MCP client) read and write
Crewsync data. It is deployed as the `crewsyncMcp` Cloud Function:

    https://crewsyncmcp-z6f43rxwha-uc.a.run.app

That URL is what the Crewsync custom connector in Claude points at. Redeploying keeps the URL,
so existing connector sign-ins keep working.

## Deploy

From the repo root, in a terminal on the PC:

    cd functions
    npm install
    npm test                       # local smoke test, no Firebase needed
    cd ..
    firebase deploy --only functions:crewsyncMcp

(`firebase deploy --only functions` deploys everything in `functions/`, which is also fine.)

Then in Claude: open any chat that uses the Crewsync connector and start a **new** conversation —
tool lists are read once per conversation. If the new tools don't show, disconnect and reconnect
the connector in Settings → Connectors; on the authorization page enter your access key.

## Access keys (one per person)

Each person who connects an AI gets their own key, so every task, appointment and contact they
create is attributed to their account and scoped to their workspace.

- Admin asks their AI: *"create an access key for cole@example.com, label 'Cole - Claude'"* →
  `create_access_key` returns the key **once**. Pass it on privately.
- The person adds the Crewsync connector (same URL as above) and types the key on the
  authorization page.
- `list_access_keys` / `revoke_access_key` manage them. Revoking also kills any logins made
  with that key.
- The original shared secret (Firebase secret `MCP_AUTH_TOKEN`) still works and maps to the
  owner account, so Dan's existing connector sign-in is unaffected.

Keys are stored hashed in `mcp_access_keys`; logins in `mcp_oauth_tokens`.

## Tools

| Area | Tools |
|---|---|
| Session | `whoami` (who/which workspace/timezone/today) |
| Projects | `list_projects`, `get_project`, `create_project`, `update_project`, `summarize_project` |
| Tasks | `list_tasks`, `get_task`, `create_task`, `update_task`, `delete_task` |
| Checklists | `add_checklist_items` (appends, never duplicates), `set_checklist_item`, `remove_checklist_item` |
| Calendar | `list_appointments` (expands Daily/Weekly/Monthly), `create_appointment`, `update_appointment`, `delete_appointment` |
| Schedule | `list_schedule`, `get_phase`, `create_phase`, `update_phase`, `delete_phase`, `link_to_phase`, `unlink_from_phase`, `import_schedule` (with `dryRun`), `set_baseline`, `shift_phase` (with `cascade`, `dryRun`), `look_ahead` |
| Contacts | `list_contacts`, `create_contact`, `update_contact`, `delete_contact` |
| Files | `list_files` (names, folders, download URLs) |
| Crew | `list_team`, `create_broadcast` |
| Admin | `create_access_key`, `list_access_keys`, `revoke_access_key` |

Behaviors worth knowing:

- Every write reads the record back and returns what was actually saved.
- Project/task/contact lookups accept a name or an ID; matching trims whitespace and ignores
  case, and errors out with the candidates when a name is ambiguous (e.g. `test` vs `Test`).
- `create_task` / `update_task` require the status to be one of the project's buckets, because
  the planner only shows tasks whose status matches a bucket. Unknown statuses used to create
  tasks that silently never appeared.
- Dates: `YYYY-MM-DD`, `YYYY-MM-DDTHH:MM` (local time in the key's timezone, default
  America/Detroit), `today`/`tomorrow`, or ISO with an offset. Output times are shown in that
  timezone. Recurring appointments step in local time, so a 9 AM meeting stays 9 AM across DST.
- Tasks still carrying the legacy flat `checklist` field are read correctly and the legacy field
  is never written back (same rule as the app).
- Schedule phases live at `projects/{id}/schedulePhases/{id}`; the math is in `schedule.js`, the
  twin of the app's `ScheduleDates.kt`. A phase holds the ids of its linked tasks and appointments
  (`taskIds`, `appointmentIds`); task and appointment documents are never written by the schedule
  tools. `get_task` and `list_appointments` look the phase up and return `phaseId` and `phase`.
- Phase dates are whole days (`YYYY-MM-DD`). Working days skip weekends and the project's
  holidays; set those with `update_project` (`scheduleHolidays`, `workWeek`).
- `set_baseline` leaves an existing baseline alone unless `overwrite` is true.
- `shift_phase` moves a phase by working days and, by default, everything that follows it through
  the predecessors; `update_phase` does the same when given `cascade: true`. Both take `dryRun`.
  The rule (planCascade in `schedule.js`, twin of the app's `ScheduleShift.kt`): a follower keeps
  its place relative to the earliest day its predecessors allow. The baseline never moves.
- Everything is scoped to the signed-in user's active workspace (Environment).

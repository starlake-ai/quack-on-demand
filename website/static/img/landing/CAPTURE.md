# Landing screenshots to capture

Boot: NUKE=1 DEMO=minimal LOAD_TPCH=1 ./scripts/run-jar.sh
Browser at ~1400px wide, light theme, crop to the content card.

- pools.jpg      Manager UI pool page for acme with the bi pool SUSPENDED
                 (suspend it via the UI button first) so the Hibernated badge
                 and Wake button are visible.
- timetravel.jpg Snapshot browser on a demo table showing the history
                 timeline, at least one tag, and the Restore action.
- usage.jpg      Usage (or Audit) page after running a few queries via
                 qod sql. Until this file exists the site shows the Grafana
                 dashboard image instead.

Drop the JPGs into this directory; no code change needed.

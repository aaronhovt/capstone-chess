#!/usr/bin/env bash
# Reports the mean nodes per second of the searches logged by match workers 1 to 7, and the
# SPRT command whose move time gives each search 100,000 nodes at that rate.
# Run from the repo root in Git Bash after a move time match at --concurrency 7:
#   bash match-nps.sh [ref-b] [log-directory]
ref="${1:-411c875}"
dir="${2:-out/match-logs}"

awk -F'|' -v ref="$ref" '
  / depth = / {
    split($2, d, "= "); split($3, n, "= "); split($4, t, "= ")
    if (d[2] + 0 == 1 && have) { nodes += last_nodes; secs += last_secs; searches++ }
    last_nodes = n[2] + 0; last_secs = t[2] + 0; have = 1
  }
  ENDFILE { if (have) { nodes += last_nodes; secs += last_secs; searches++ } have = 0 }
  END {
    if (secs == 0) { print "No searches found."; exit 1 }
    nps = nodes / secs
    printf "%d searches, %.0f nodes per second\n\n", searches, nps
    printf "build compare working %s --sprt --pairs 33398 --elo0 0 --elo1 5 --alpha 0.05 --beta 0.05 --movetime %.0f --concurrency 7\n", ref, 1e8 / nps
  }' "$dir"/engine-?-[1-7].log

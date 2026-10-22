#!/bin/sh
PORT=$1; OUT=$2; shift 2; mkdir -p "$OUT"; M="$(dirname $0)/mc.sh"; SECS=${SECS:-8}
set_rx() { $M $PORT reflexium "{\"key\":\"$1\",\"value\":\"$2\"}" >/dev/null; }
for mode in "$@"; do
  up=${mode%%:*}; rest=${mode#*:}; q=${rest%%:*}; mdl=${rest#*:}
  set_rx upscaler $up; if [ "$up" != off ]; then set_rx quality $q; set_rx dlss_model $mdl; fi
  $M $PORT wait '{"ms":6000}' >/dev/null
  f=$($M $PORT reflexium '{"frame_mark":"1"}' | sed -n 's/.*"frames":\([0-9]*\).*/\1/p')
  sleep $SECS
  st=$($M $PORT reflexium "{\"frame_stats_from\":\"$f\"}" | grep -o '"avg_fps":[0-9.]*\|"low_1pct_fps":[0-9.]*' | tr '\n' ' ')
  line=$($M $PORT reflexium '{}' | grep -o '"status_line":"[^"]*"\|"reflex_latency":"[^"]*"\|"fg_status":"[^"]*"' | tr '\n' ' ')
  name=$(echo $mode | tr ':' '_'); $M $PORT screenshot "{\"path\":\"$(cygpath -m $PWD/$OUT)/$name.png\"}" >/dev/null
  echo "$mode | $st | $line"
done

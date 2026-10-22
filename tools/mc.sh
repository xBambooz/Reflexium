#!/bin/sh
# usage: mc.sh PORT endpoint 'json'
PORT=$1; EP=$2; BODY=${3:-'{}'}
TOK=$(sed -n 's/.*"token": *"\([^"]*\)".*/\1/p' /c/Users/Ryan/source/repos/mc-agent-harness/instances/$PORT/session.json)
curl -s -m 200 -X POST -H "Authorization: Bearer $TOK" -H "Content-Type: application/json" -d "$BODY" http://127.0.0.1:$PORT/$EP

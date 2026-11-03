#!/bin/sh
set -eu

test -x /opt/taixu/bin/rtk || { echo "rtk binary is missing or not executable" >&2; exit 1; }
/opt/taixu/bin/rtk --version || exit 1

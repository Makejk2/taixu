#!/bin/sh
set -eu

TOOL_DIR="${TAIXU_TOOL_DIR:?missing TAIXU_TOOL_DIR}"

rm -f /opt/taixu/bin/rtk
rm -f "$TOOL_DIR/bin/rtk"
rm -rf /opt/taixu/data/rtk
echo "RTK 终端命令优化插件已卸载"

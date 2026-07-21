#!/bin/bash
SCRATCHPAD_DIR=".dev/scratchpad"

if [ -d "$SCRATCHPAD_DIR" ] && [ -n "$(ls -A "$SCRATCHPAD_DIR" 2>/dev/null)" ]; then
    echo "⚠️  .dev/scratchpad/ 에 파일이 남아있습니다. 작업 종료 전 정리하세요." >&2
fi

exit 0

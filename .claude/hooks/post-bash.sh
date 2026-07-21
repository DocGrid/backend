#!/bin/bash
INPUT=$(cat)
COMMAND=$(echo "$INPUT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('command',''))" 2>/dev/null || echo "")

# git commit 후 메시지 형식 검사
if echo "$COMMAND" | grep -qE "^git commit" && ! echo "$COMMAND" | grep -q "\-\-amend"; then
    LAST_MSG=$(git log -1 --format='%s' 2>/dev/null || echo "")
    if [ -n "$LAST_MSG" ]; then
        if ! echo "$LAST_MSG" | grep -qE "^(feat|fix|chore|refactor|docs|test|style|perf):"; then
            echo "⚠️  커밋 메시지 형식 오류: feat: / fix: / chore: / refactor: / docs: 접두사가 필요합니다." >&2
            echo "   현재 메시지: $LAST_MSG" >&2
        fi
    fi
fi

exit 0

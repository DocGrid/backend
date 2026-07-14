#!/bin/bash
INPUT=$(cat)
COMMAND=$(echo "$INPUT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('command',''))" 2>/dev/null || echo "")

# git push --force 차단
if echo "$COMMAND" | grep -qE "git push.*(--force|-f\b)"; then
    echo "🚫 git push --force 는 금지되어 있습니다." >&2
    exit 2
fi

# main 브랜치 직접 push 차단
if echo "$COMMAND" | grep -qE "git push (origin )?main"; then
    echo "🚫 main 브랜치 직접 push 는 금지되어 있습니다. PR을 통해 merge하세요." >&2
    exit 2
fi

exit 0

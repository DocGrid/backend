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
    echo "🚫 main 브랜치 직접 push 는 금지됩니다. PR을 통해 merge하세요." >&2
    exit 2
fi

# develop 브랜치 직접 push 차단
if echo "$COMMAND" | grep -qE "git push (origin )?develop"; then
    echo "🚫 develop 브랜치 직접 push 는 금지됩니다. feature 브랜치에서 PR을 통해 merge하세요." >&2
    exit 2
fi

# git add -A / git add . 차단
if echo "$COMMAND" | grep -qE "git add (-A|\.)(\s|$)"; then
    echo "🚫 git add -A / git add . 는 금지됩니다. 파일을 명시적으로 지정하세요." >&2
    exit 2
fi

# git commit --amend 차단
if echo "$COMMAND" | grep -qE "git commit.*--amend"; then
    echo "🚫 git commit --amend 는 금지됩니다. 새 커밋을 생성하세요." >&2
    exit 2
fi

# git push --no-verify 차단
if echo "$COMMAND" | grep -qE "git push.*--no-verify"; then
    echo "🚫 git push --no-verify 는 금지됩니다. 훅을 우회하지 마세요." >&2
    exit 2
fi

exit 0

---
globs: "backend/src/main/resources/db/migration/**"
---

# DB 마이그레이션 규칙

- 스키마 변경은 반드시 Flyway 마이그레이션 파일로 관리
- 파일명: `V{버전}__{설명}.sql` (예: `V1__create_user_table.sql`)
- 마이그레이션 파일은 한 번 적용 후 수정 금지 — 새 파일 추가
- `spring.jpa.hibernate.ddl-auto=validate` 유지 (Flyway가 스키마 관리)

## 트러블슈팅
- Flyway 마이그레이션 실패: `flyway_schema_history` 테이블 확인, 중복 버전 체크

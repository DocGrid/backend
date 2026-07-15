---
globs: "docker-compose*.yml, deploy.sh, .github/workflows/**, Dockerfile"
---

# 배포 규칙

## Git 전략
- 기본 브랜치: `develop`
- 브랜치: `feature/{이슈번호}`, `fix/{이슈번호}`, `hotfix/{내용}`
- `develop`에서 브랜치 생성 → 작업 완료 후 `develop`으로 PR
- PR은 이슈 단위로 생성, 리뷰어 지정 필수
- `develop` 직접 push 금지 — PR + 리뷰 후 merge
- 커밋 메시지: `feat:`, `fix:`, `chore:`, `refactor:`, `docs:` 접두사

## 커밋 단위 규칙
- 프로덕션 코드와 테스트 코드는 반드시 **별도 커밋**으로 분리
- 이슈 하나도 논리적 단위별로 나눠서 커밋 (뭉쳐서 1~2개로 끝내지 않는다)
- 권장 분리 기준: `Repository 쿼리 추가` → `Service 구현` → `TODO 교체` → `테스트`
- 작업 시작 전 커밋 계획을 먼저 제시하고 단계마다 커밋

## 환경 설정
- `application.yml` — 공통 설정
- `application-local.yml` — 로컬 전용 (DB 접속정보 환경변수로 주입)
- `application-prod.yml` — 프로덕션 (환경변수로 시크릿 주입)

## DB 마이그레이션
- 스키마 변경은 반드시 Flyway 마이그레이션 파일로 관리
- 파일명: `V{버전}__{설명}.sql` (예: `V1__create_user_table.sql`)
- 마이그레이션 파일은 한 번 적용 후 수정 금지 — 새 파일 추가
- `spring.jpa.hibernate.ddl-auto=validate` 유지 (Flyway가 스키마 관리)

## 빌드
```bash
./gradlew build -x test   # CI용 (테스트 제외)
./gradlew build            # 전체 빌드 + 테스트
```

## 트러블슈팅
- LazyInitializationException: 트랜잭션 범위 밖 연관관계 접근, JOIN FETCH 추가
- PostgreSQL 연결 실패: `application-local.yml` DB 설정 및 PostgreSQL 실행 여부 확인
- Flyway 마이그레이션 실패: `flyway_schema_history` 테이블 확인, 중복 버전 체크
- 빌드 실패: `./gradlew clean build` 후 재시도

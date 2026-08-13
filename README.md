# DocGrid

DocGrid는 문서 업로드·인덱싱·검색과 웹 인터페이스를 하나의 저장소에서 관리하는 모노레포입니다.

## 저장소 구조

```text
.
├── backend/          # Spring Boot API와 임베딩 서버
├── frontend/         # DocGrid 웹 애플리케이션
├── docs/             # 설계 문서와 실행된 테스트 결과
├── docker/           # 로컬 인프라 초기화 파일
├── scripts/          # 프로젝트 공용 검증·보고 스크립트
└── docker-compose.yml
```

## 빠른 시작

### 로컬 인프라

```bash
cp .env.example .env
docker compose pull postgres
docker compose up -d --wait --wait-timeout 60 postgres
```

### 백엔드

```bash
./backend/gradlew -p backend bootRun --args='--spring.profiles.active=local'
```

자세한 백엔드 실행 방법은 [backend/README.md](backend/README.md)를 참고하세요.

### 프론트엔드

Node.js 22.13.0 이상이 필요합니다.

```bash
npm --prefix frontend install
npm --prefix frontend run dev
```

자세한 프론트엔드 실행 방법은 [frontend/README.md](frontend/README.md)를 참고하세요.

## 검증

```bash
./backend/gradlew -p backend test
npm --prefix frontend test
```

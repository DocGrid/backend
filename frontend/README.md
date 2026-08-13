# DocGrid Frontend

DocGrid 백엔드 API를 사용하는 실제 웹 클라이언트입니다. 기존 화면 프로토타입의 보라색 워크스페이스 디자인을 유지하면서 검색, 문서, 컬렉션, 권한, MCP 토큰, 계정, RAGOps 관리 화면을 API에 연결합니다.

## 실행

Node.js 22.13 이상이 필요합니다.

```bash
cp .env.example .env.local
npm install
npm run dev
```

`.env.local`에서 백엔드 주소를 지정합니다.

```dotenv
BACKEND_API_URL=http://localhost:8080
```

브라우저는 `/api/backend/*` 같은 출처 프록시만 호출합니다. 프록시가 `BACKEND_API_URL`로 요청을 전달하므로 별도 프론트엔드 CORS 허용 목록 없이 로컬·배포 환경을 동일하게 사용할 수 있습니다.

## 인증

- ChatGPT 로그인은 사용하지 않습니다.
- `/auth/login`, `/auth/signup`, `/auth/me`를 사용합니다.
- JWT는 탭이 닫히면 사라지는 `sessionStorage`에만 보관합니다.
- 백엔드가 `401`을 반환하면 세션을 지우고 로그인 화면으로 이동합니다.

## 검증

```bash
npm run lint
npm run build
npm test
```

## API 제공 범위

문서 상세·추출 본문·원본 미리보기·다운로드, 컬렉션 소속 문서, 직접 권한 목록, 관리자 사용자 목록은 현재 모노레포의 백엔드 계약에 연결되어 있습니다. 배포된 백엔드가 해당 조회 API보다 오래된 버전이면 화면에서 버전 불일치를 안내합니다.

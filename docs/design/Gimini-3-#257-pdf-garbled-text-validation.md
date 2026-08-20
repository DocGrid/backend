# #257 PDF 텍스트 추출 시 깨진 문자(�) 검증 누락 수정

closes #257

---

## 배경

`PdfDocumentParser`는 암호화·빈 문서·OCR 필요·파싱 실패 4가지만 구분한다. 텍스트가 추출은 되지만 PDF 폰트의 ToUnicode CMap이 깨져 대체 문자(U+FFFD, `�`)가 섞여 나오는 경우는 어떤 단계에서도 걸러내지 않았다. 이런 손상 텍스트는 검증 없이 그대로 Chunk → Embedding → RAG 검색까지 전달된다.

에러코드·테스트를 직접 확인해 갭을 검증했다 — `DOCUMENT_CONTENT_INVALID` 류 에러코드도, `�` 비율 검사 로직도 코드베이스 어디에도 없었다.

## 설계 결정

- **검사 단위는 문서 전체 통합.** 페이지별로 개별 차단하면 일부 페이지만 약간 깨진 정상 문서를 과도하게 차단할 수 있어, 모든 Segment의 Text를 합쳐 문서 전체 대비 `�` 비율로 판단한다.
- **임계치 5%는 코드 상수로 하드코딩.** 이번 최소 구현 범위에서는 `application.yml` 설정으로 노출하지 않는다. 운영 중 오탐/미탐 사례가 쌓이면 조정한다.
- **비율 계산은 `PdfDocumentParser` 내부 package-private static 메서드(`isGarbled`)로 분리.** PDFBox로 실제 손상 PDF를 인위적으로 재현하기 어려워(아래 테스트 항목 참고), 문자열을 직접 넣어 검증 로직만 단위 테스트할 수 있게 했다.
- **신규 `ErrorCode.DOCUMENT_CONTENT_GARBLED` (DOCUMENT-PARSING-008, 422)** — 기존 `DOCUMENT_PDF_ENCRYPTED`/`DOCUMENT_OCR_REQUIRED`/`DOCUMENT_PARSING_FAILED`와 동일하게 `WorkerIndexingFailureClassifier`의 `DOCUMENT_CONTENT_ERRORS` 집합에 등록해 `IndexingFailureType.DOCUMENT_CONTENT_INVALID`로 분류되고, 재처리 불가 정책을 그대로 상속받는다. 새 `IndexingFailureType`은 만들지 않았다.
- **스코프에서 제외한 것**: OCR 등 대체 추출 경로 도입, 임계치의 `application.yml` 설정화. 둘 다 이번 최소 구현 범위를 벗어난다고 판단해 뺐다.

## API 명세

문서 업로드/버전 등록 시 PDF 파싱 단계에서 발생하는 에러 케이스가 하나 추가된다 (엔드포인트 자체는 변경 없음).

**에러 케이스**

| 상황 | 응답 |
| --- | --- |
| 문서 전체 대비 `�` 비율이 5% 초과 | `422 DOCUMENT_CONTENT_GARBLED` (DOCUMENT-PARSING-008) |
| (기존, 변경 없음) 암호화된 PDF | `422 DOCUMENT_PDF_ENCRYPTED` |
| (기존, 변경 없음) 텍스트를 찾을 수 없음 | `422 DOCUMENT_OCR_REQUIRED` |

Worker 인덱싱 파이프라인에서는 `WorkerIndexingFailureClassifier`가 이 에러를 기존 `DOCUMENT_CONTENT_INVALID` 실패 유형으로 분류해, 관리자 화면에 다른 문서 내용 오류와 동일하게 노출된다.

## 변경 파일

- `ErrorCode.java` — `DOCUMENT_CONTENT_GARBLED` (DOCUMENT-PARSING-008) 추가
- `PdfDocumentParser.java` — `isGarbled()` 비율 검사 추가, Segment 추출 후 임계치 초과 시 차단
- `WorkerIndexingFailureClassifier.java` — `DOCUMENT_CONTENT_ERRORS` 집합에 새 에러코드 등록

## 테스트

- `PdfDocumentParserTest` — `isGarbled()` 임계치 이하/초과/빈 리스트 3케이스, `parseDocument()` 정상 텍스트 오탐 방지 회귀 1케이스 추가
- `WorkerIndexingFailureClassifierTest` — 새 에러코드가 `DOCUMENT_CONTENT_INVALID`로 분류되는지 검증 추가
- `./backend/gradlew -p backend test --tests "*PdfDocumentParserTest*" --tests "*WorkerIndexingFailureClassifierTest*"` 통과

**알려진 한계**: PDFBox가 실제로 `�`를 출력하는 상황은 폰트 ToUnicode CMap이 깨진 손상 PDF에서만 발생하는데, 기존 테스트 헬퍼(Standard14 Helvetica + WinAnsiEncoding)로는 이런 손상을 인위적으로 만들 수 없다(WinAnsiEncoding이 U+FFFD 자체를 인코딩하지 못해 PDF 생성 단계에서 예외가 난다). 그래서 `parseDocument()` 레벨의 "실제 손상 PDF → `DOCUMENT_CONTENT_GARBLED` 발생" 통합 테스트는 이번에 포함하지 않았다. 실제로 문제가 발생했던 PDF 파일을 확보하면 fixture로 추가해 통합 테스트를 보강하는 것을 후속 작업으로 남긴다.

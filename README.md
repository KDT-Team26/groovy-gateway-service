# groovy-gateway-service

## 1. 이 레포는 무엇인가

**Groovy**는 태그 기반으로 스터디 그룹을 매칭하고, 참여 신청/승인, 캘린더 일정 관리, 회고록 공유,
실시간 알림까지 지원하는 스터디 커뮤니티 플랫폼입니다. 백엔드는 하나의 Spring Boot 모놀리스에서
도메인별 마이크로서비스로 전환되었고, 지금은 서비스별 폴리레포로 분리되는 중입니다.

`groovy-gateway-service`는 그중 **모든 외부 요청의 단일 진입점(API Gateway)**입니다.
도메인 로직이나 DB 없이 경로 기반 라우팅만 수행하며, 5개 도메인 서비스가 폴리레포로 갈라진
뒤에도 프론트엔드가 여러 백엔드 주소를 알 필요 없이 이 서비스 하나만 바라보게 해줍니다.

## 2. 주요 기능

- **경로 기반 라우팅**: 요청 경로에 따라 5개 도메인 서비스 중 하나로 프록시
- **단일 진입점**: 프론트엔드는 오직 이 서비스의 주소(`VITE_API_BASE_URL`)만 알면 됨
- **매칭 실패 시 404**: 정의된 라우트에 매칭되지 않는 경로는 그대로 404 반환(다른 서비스로 흘러가지 않음)
- 그 외 인증/인가, 캐싱, 요청 변형 등 도메인 로직은 전혀 갖지 않음 — 순수 라우팅 계층

### 라우팅 테이블

YAML 리스트 순서가 매칭 우선순위입니다(구체적인 경로가 먼저 옵니다).

| 우선순위 | Path 패턴 | 대상 서비스 |
| :--- | :--- | :--- |
| 1 | `/api/notifications/**` | notification-service `:8085` |
| 2 | `/api/auth/**` | identity-service `:8081` |
| 3 | `/api/users/me` (exact) | identity-service `:8081` |
| 4 | `/api/studies/**` | study-service `:8082` |
| 5 | `/api/users/me/studies` | study-service `:8082` |
| 6 | `/api/users/me/applications` | study-service `:8082` |
| 7 | `/api/calendars/**` | calendar-service `:8084` |
| 8 | `/api/memoirs/**` | content-service `:8083` |
| 9 | `/api/tags/**` | identity-service `:8081` |

`/api/studies/**` 같은 와일드카드 라우트가 걸려 있어, `/api/studies/{id}/members`처럼 원래
"서비스 간 전용" 목적의 엔드포인트도 게이트웨이를 거쳐 외부에서 직접 호출 가능한 상태입니다
(알려진 심층방어 이슈로 문서화되어 있음).

## 3. 시스템 아키텍처

```
브라우저/클라이언트
   │
   ▼
api-gateway :8080 (관리 포트 :8090)
   ├─ /api/notifications/**            → notification-service :8085
   ├─ /api/auth/**, /api/users/me(exact), /api/tags/**
   │                                   → identity-service :8081
   ├─ /api/studies/**, /api/users/me/studies, /api/users/me/applications
   │                                   → study-service :8082
   ├─ /api/calendars/**                → calendar-service :8084
   └─ /api/memoirs/**                  → content-service :8083
```

이 서비스는 **DB를 갖지 않습니다.** 도메인 로직/데이터가 없는 순수 라우팅 계층이기 때문에,
5개 서비스처럼 전용 스키마·계정을 발급받지 않습니다. `actuator`도 라우팅 대상 포트(`8080`)와
섞이지 않도록 별도 관리 포트(`8090`)로 분리되어 있습니다.

## 4. 기술 스택

| 카테고리 | 기술 |
| :--- | :--- |
| Language | Java 21 |
| Framework | **Spring Boot 4.0.7** (다른 5개 서비스는 4.1.0 — Spring Cloud 2025.1.2가 검증한 버전에 맞춰 이 서비스만 예외적으로 고정) |
| Gateway | `spring-cloud-starter-gateway-server-webmvc` (서블릿/MVC 기반 — 나머지 서비스와 스택을 통일하기 위해 WebFlux 버전 대신 선택) |
| Build Tool | Gradle (멀티모듈) |
| Observability | Actuator + Micrometer Tracing(OTLP → Tempo) + Micrometer Prometheus |
| Logging | `libs:observability`의 `logback-json.xml`을 리소스로 include하여 JSON 구조화 로그 → Loki |

이 서비스는 Java 코드에서 실제로 import하는 공유 라이브러리가 없습니다 — `libs:observability`를
의존하지만 리소스 include(`<include resource="observability/logback-json.xml"/>`) 방식으로만
쓰이며, `event-contract`/`security-common`/DB 관련 의존성은 전혀 없습니다. Java 코드는
`ApiGatewayApplication`, `TracingConfig`, `CorsConfig` 3개뿐입니다.

## 5. 다른 MSA 서비스와의 네트워크 호출 관계

| 방향 | 상대 서비스 | 용도 |
| :--- | :--- | :--- |
| 아웃바운드(프록시) | identity-service, study-service, content-service, calendar-service, notification-service | 경로에 매칭되는 요청을 그대로 전달(라우팅) |
| 인바운드 | 프론트엔드 / 외부 클라이언트 | 모든 API 요청의 유일한 진입점 |

이 서비스는 JWT를 직접 검증하지 않습니다 — `Authorization` 헤더를 그대로 전달(forward)하고,
실제 토큰 검증은 각 도메인 서비스가 identity-service의 JWKS로 직접 수행합니다. 게이트웨이는
CORS 처리(`CorsConfig`)와 분산 트레이싱 스팬 시작만 담당합니다.

## 6. 로컬 실행 방법

이 레포는 Gradle 멀티모듈 프로젝트이며(`libs/observability` + `services/api-gateway`), 별도
docker-compose 파일은 포함하지 않습니다. 라우팅 대상 서비스가 없어도 게이트웨이 자체는
기동되지만, 실제 요청은 대상 서비스가 떠 있어야 응답을 받습니다.

```bash
./gradlew :services:api-gateway:bootRun

# 또는 Docker 이미지 빌드 (빌드 컨텍스트 = 이 레포 루트, libs/observability를 함께 봐야 함)
docker build -t groovy-gateway-service .
docker run -p 8080:8080 -p 8090:8090 groovy-gateway-service
```

기본 포트는 `8080`(라우팅), `8090`(actuator/health/prometheus)입니다.

> 게이트웨이 단독으로는 대부분의 요청이 대상 서비스 부재로 실패합니다. 5개 도메인 서비스까지
> 포함한 전체 스택은 원본 `Groovy` 레포의 `docker-compose.local.yml` 사용을 권장합니다.

## 7. 기존 모노레포에서 뗀 부분

1. **레거시 단일 모놀리스** (`groovy/`, 삭제됨): 게이트웨이라는 개념 자체가 없었습니다 — 프론트가
   단일 백엔드(`groovy` 애플리케이션)를 직접 호출했습니다.
2. **모듈러 모놀리스 → MSA** (`backend/`, Gradle 멀티모듈 `groovy-backend-msa`): 5개 도메인이
   독립 서비스로 흩어지면서, 프론트가 여러 주소를 알 필요 없게 해줄 단일 진입점으로
   `backend/services/api-gateway/`가 신설되었습니다(레거시에 대응하는 원본 코드가 없는 **신규
   서비스**).
3. **폴리레포 분리** (지금 이 레포): 도메인 로직/DB가 없는 가장 단순한 구조 덕분에,
   notification-service와 함께 **물리 레포 분리의 파일럿**으로 가장 먼저 이관되었습니다.
   `backend/services/api-gateway/`와 `libs/observability`만 복사하면 되어 격리 난이도가
   가장 낮았습니다. 격리 판단 근거는 원본 `Groovy` 레포의
   `docs/transfer/groovy-gateway-service.md`에 기록되어 있습니다.

## 8. 모니터링 스택에서 관측되는 부분

- **Prometheus**: `job_name: api-gateway`가 라우팅 포트(`8080`)가 아닌 **관리 포트
  `api-gateway:8090`**의 `/actuator/prometheus`를 15초 주기로 스크래핑합니다. JVM, HTTP 요청
  지연(모든 외부 요청이 이 서비스를 거치므로 전체 트래픽의 진입 지연을 대표) 지표를 수집합니다.
  DB가 없어 HikariCP 지표는 없습니다.
- **Alertmanager**: `BackendMemoryUsageTooHigh`(JVM 힙 40% 초과), `BackendCpuSpikeDetected`
  (CPU 95% 초과)가 `job="api-gateway"` 라벨로 적용됩니다(HikariCP 관련 알림은 해당 없음).
- **Tempo**: 모든 외부 요청의 트레이스가 **이 서비스에서 시작**됩니다. W3C `traceparent` 헤더로
  하위 라우트(각 도메인 서비스)까지 전파되어, 요청 하나가 여러 서비스를 거치는 흐름 전체를
  하나의 트레이스로 확인할 수 있습니다.
- **Grafana**: `springboot-dashboard.json`(JVM), `backend-app-logs-dashboard.json`(Loki 로그)에서
  `application="api-gateway"`로 필터링 가능.
- **Loki + Alloy**: 컨테이너 stdout(JSON 구조화 로그)을 코드 수정 없이 자동 수집.

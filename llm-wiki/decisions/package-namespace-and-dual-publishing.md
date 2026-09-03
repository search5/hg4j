---
updated: 2026-09-03
status: current — 2026-09-03에 아래 "재통합" 절의 결정으로 패키지/그룹이 다시 합쳐짐
---

# 결정: 패키지 네임스페이스 & Maven/Gradle Plugin Portal 이중 배포

## 배경
hg4j는 라이브러리(Maven Central 배포)이면서 동시에 **Gradle 플러그인**(Gradle Plugin
Portal 배포)이기도 하다. 두 배포처가 서로 다른 네임스페이스 규칙을 요구해 충돌이 있었다.

## 타임라인 (git log 기반)
1. `f96db1f` — 애초 코드 패키지는 `com.github.search5.hg4j`였음.
2. `74a4d55` — Maven Central 등록 규칙에 맞춰 `io.github.search5.hg4j`로 **전체 이동**을 시도.
3. `f96db1f`(리버트) — **패키지 네임스페이스는 다시 `com.github.search5.hg4j`로 롤백**하고,
   대신 `build.gradle`의 `group`만 `io.github.search5.hg4j`로 유지 + `java-gradle-plugin`
   통합 설정 추가.
4. `64b1521` — Gradle Plugin Portal이 요구하는 네임스페이스 제약(플러그인 id와 소스 패키지
   구조 간 정합성)까지 마저 정리.

## 왜 전체 이동이 아니라 롤백했는가
Maven Central은 `group`(POM coordinate)만 도메인 소유권 검증을 요구하고, 실제 Java 패키지명과
반드시 일치할 필요는 없다. 반면 패키지명을 통째로 바꾸면:
- 이미 존재하는 대량의 테스트/문서(`src/test/java/com/github/search5/hg4j/...`)를 전부
  이동해야 함.
- Gradle Plugin Portal 쪽은 `implementationClass`가 특정 패키지 경로를 가리키는데, 두 배포
  채널의 요구사항이 서로 다른 이동을 요구할 위험이 있었음.

**결론**: `group`(퍼블리싱 좌표)과 `package`(소스 코드 네임스페이스)를 분리해서, 코드는
안정적인 `com.github.search5.hg4j`를 유지하고 배포 좌표만 `io.github.search5.hg4j`로 맞췄다.

## 2026-08-31~2026-09-03 상태 (폐기됨 — 아래 "2026-09-03 재통합" 참고)
```groovy
group = 'io.github.search5.hg4j'   // Maven 좌표
// 실제 소스 패키지는 com.github.search5.hg4j.* 그대로
```
```groovy
gradlePlugin {
    plugins {
        hg4jPlugin {
            id = 'io.github.search5.hg4j'
            implementationClass = 'com.github.search5.hg4j.HgPlugin'
        }
    }
}
```

## 2026-09-03 업데이트: 패키지 네임스페이스 재통합

사용자가 실제 배포(Maven Central)를 준비하며 다시 지시: "자바 패키지명도
`io.github.search5.hg4j`로 바꿔줘." 위 "왜 전체 이동이 아니라 롤백했는가" 절의
우려사항 2가지를 실제로 재검증했다:

1. **대량 파일 이동 비용**: `git mv`로 디렉터리 트리 자체를 옮기고
   (`src/main/java/com/github/search5/hg4j` → `src/main/java/io/github/search5/hg4j`,
   test도 동일) `sed`로 `package`/`import` 선언을 일괄 치환하는 기계적 작업이라
   실제로는 우려했던 것만큼 위험하지 않았다 — 프로덕션 164개 + 테스트 232개
   파일, 컴파일/전체 테스트 스위트 재실행으로 즉시 검증 가능.
2. **Gradle Plugin Portal 요구사항 충돌**: 재검증 결과 **실제 제약이 아니었다** —
   `id`와 `implementationClass`가 같은 패키지를 가리키는 건 오히려 Gradle 플러그인의
   흔한 표준 형태이고, `validatePlugins`/`generatePomFileFor*Publication` 태스크
   둘 다 이 구성으로 정상 통과했다. 2026-08-31 당시엔 "위험 가능성"으로만 적어뒀지
   실제로 검증해본 적은 없었던 것으로 보인다.

**새 결정**: `group`과 `package`를 다시 하나로 합쳐 둘 다 `io.github.search5.hg4j`.
`com.jcraft.jsch` 테스트 픽스처(JSch 자체 패키지에 얹혀 있는 `ThrowingSession`)는
hg4j 패키지가 아니므로 이동 대상에서 제외, 내부 주석의 클래스명 참조만 갱신.

```groovy
group = 'io.github.search5.hg4j'
// 소스 패키지도 동일: io.github.search5.hg4j.*
```
```groovy
gradlePlugin {
    plugins {
        hg4jPlugin {
            id = 'io.github.search5.hg4j'
            implementationClass = 'io.github.search5.hg4j.HgPlugin'   // id와 동일 패키지
        }
    }
}
```

## 함께 처리된 부수 결정
- **signing 필수화 조건화** (`9879ab0`): `publish`/`publishToMavenLocal` 태스크가 실제로
  실행될 때만 GPG 서명을 강제 → 로컬 단순 빌드(`./gradlew build`)가 서명 키 부재로 깨지는
  것을 방지.
- **Gradle 9 태스크 순서 충돌 해소** (`27299fc`): `PublishToMavenRepository`/
  `PublishToMavenLocal` 태스크가 `Sign` 태스크보다 반드시 나중에 실행되도록
  `mustRunAfter` 명시 — Gradle 9에서 암시적 태스크 의존성 추론이 사라지며 발생한 충돌.

## 관련 페이지
- [[module-info-disabled]] — 같은 배포 파이프라인 정비 과정에서 함께 다룬 이슈

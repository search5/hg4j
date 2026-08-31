---
updated: 2026-08-31
status: current
---

# 결정: JPMS(module-info.java) 비활성화

## 현재 상태
`src/main/java/module-info.java.bak` — 확장자가 `.bak`이라 컴파일 대상에서 제외된다.
`build.gradle`에도 명시적으로 방어선이 걸려 있다:
```groovy
compileJava {
    exclude 'module-info.java'
}
```

## 왜 완전히 삭제하지 않고 `.bak`으로 남겼는가 (추정 — 코드에서 직접 확인된 사실 아님)
파일이 존재한다는 것은 JPMS 모듈화를 시도했던 이력이 있고, 완전 폐기가 아니라 **보류**
상태로 보존해둔 것으로 보인다. 의존 라이브러리(`jsch`, `bouncycastle`, `commons-compress`,
`zstd-jni`, `sshd-core` 등) 중 일부가 명시적 모듈 디스크립터를 제공하지 않아 JPMS 강제 시
classpath 해석 문제가 발생했을 가능성이 높다.

> ⚠️ 이 페이지의 "왜"는 git log/커밋 메시지에 명시적 근거가 없어 추정임을 표시함
> (status는 여전히 current로 두되, 근거를 찾으면 이 문단을 갱신할 것).

## 실무 영향
- 신규 클래스를 추가할 때 `module-info.java.bak`을 신경 쓸 필요 없음 — 빌드에서 완전히
  제외되어 있다.
- 향후 JPMS를 다시 시도한다면 `.bak` 확장자를 떼기 전에, 위 의존성들의 Automatic-Module-Name
  존재 여부부터 확인해야 한다.

## 관련 페이지
- [[package-namespace-and-dual-publishing]]

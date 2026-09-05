---
updated: 2026-09-04
status: completed
---

# 백로그 29: requires 파일 세부 문자열 커버리지 재검증

`Hg.open()`의 `SUPPORTED` 허용목록이 낡아 있어서 `sparserevlog`(모든 real hg 7.2 기본
저장소에 있음)가 빠져 있고 `revlog-compression-zstd`를 잘못된 문자열로 갖고 있던 버그
— 설정 없이 그냥 `hg init`한 완전히 평범한 저장소조차 거부되고 있었다.

## 원문
29. ~~**`requires` 파일 세부 문자열 커버리지 재검증**~~ — ✅ **완료(2026-09-04)**.
    조사 결과 `HgRepository.loadRequires()` 자체는 8개 특수 문자열을 정확히
    인식/무시하고 있어 문제가 없었지만, **완전히 별도의, 서로 동기화되지 않은
    두 번째 검증 게이트**가 `Hg.open()`(`api/Hg.java`)에 이미 존재했고 그게
    심각하게 낡아 있었다 — 이 게이트의 `SUPPORTED` 허용목록이 (1)
    `revlog-compression-zstd`(real hg 7.2 기본 압축 엔진이 남기는 실제 문자열)를
    `revlog-compression`(접미사 없는 잘못된 값)으로만 갖고 있었고, (2)
    `sparserevlog`(모든 real hg 7.2 저장소에 기본으로 존재)가 아예 빠져 있었고,
    (3) `narrowspec`(narrowspec 데이터 파일의 온디스크 **파일명**을 requirement
    문자열로 착각한 값)을 갖고 있으면서 실제 문자열인 `narrowhg-experimental`은
    없었고, (4) `HgRepository`가 이미 완전히 지원하는 6개 고급 포맷 문자열
    (`exp-changelog-v2`/`exp-revlogv2.2`/`persistent-nodemap`/`fileindex-v1`/
    `treemanifest`/`exp-copies-sidedata-changeset`)이 전부 빠져 있었다. **실제
    영향(real hg CLI로 직접 재현 확인)**: 설정 하나 없이 그냥 `hg init`한
    완전히 평범한 real hg 7.2 저장소조차 `Hg.open()`으로 열면
    `HgValidationException: unsupported repository requirement: sparserevlog`로
    거부됐다 — `new HgRepository(dir)`로 그 게이트를 우회하는 기존 테스트들만
    이 사실을 가려온 것. `Hg.java`의 `SUPPORTED` 목록을 실측된 정확한 문자열로
    동기화해 수정, 신규 `HgOpenRequirementValidationTest`(5개 테스트: 평범한
    저장소/압축+sparserevlog 개별 확인/6개 고급 포맷 각각/narrow 문자열/여전히
    진짜 미지의 requirement는 거부)로 검증, 기존
    `HgPorcelainAndExceptionsTest`의 관련 테스트도 회귀 없이 통과.


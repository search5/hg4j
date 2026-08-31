실제 `hg` CLI(Mercurial 7.2)로
`hg --config experimental.evolution=all --config experimental.evolution.createmarkers=true`
설정 후 `hg commit --amend`를 실행해 얻은 실제 FM1(version=1) obsstore 파일.

`mercurial.obsolete._readmarkers()`로 직접 디코드해 확인된 내용:
- predecessor: 3eb3e84d83a814e381a70541fe7eedc8655fa277
- successor:   9bbd73f724f5f43fdf7bc19fb5bc225db637768d
- flags: 0
- metadata: {'ef1': '9', 'operation': 'amend', 'user': 'test <test@example.com>'}

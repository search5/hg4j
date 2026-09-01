실제 `hg` CLI(Mercurial 7.2)로 `--record-parents` 옵션을 사용해 `hg debugobsolete`로 생성한
실제 FM1(version=1) obsstore 파일들. `numpar != 3(_fm1parentnone)`인 경우(부모 정보가 함께
기록된 prune류 마커)를 커버한다 — `mercurial/obsolete.py`의 `createmarkers()`를 보면, 후속
리비전(successor)이 없는 경우(prune) `npare = tuple(p.node() for p in prec.parents())`로 부모
노드를 기록한다.

## obsstore-single-parent

루트 커밋(부모 없음)을 prune. 명령:
```
hg init repo && cd repo
echo one > a.txt && hg add a.txt
hg commit -u "T <t@example.com>" -m c1
hg --config experimental.evolution.createmarkers=true debugobsolete --record-parents <node0>
```
`mercurial.obsolete._fm1purereadmarkers()`로 직접 디코드해 확인된 내용:
- predecessor: 5cf8ae3e0524261c722dc44fd837cc8d9ebf9b5d
- successors: 0개 (prune)
- numpar: 1, parent: 0000000000000000000000000000000000000000 (널 노드 — 루트 커밋의 부모)
- flags: 0
- metadata: {'user': 'jiho@jiho-asus'}

## obsstore-merge-parents

머지 커밋(부모 2개)을 prune. 명령:
```
hg merge <rev1> && hg commit -m merge   # rev3 = merge, parents = rev1, rev2
hg --config experimental.evolution.createmarkers=true debugobsolete --record-parents <mergenode>
```
디코드 확인:
- predecessor: e24d603786b1ab4c567a47625d2e0e1c6d435187
- successors: 0개 (prune)
- numpar: 2, parents: 8f1b07dea837dc770e82c62856ae3f72b1a9933b, 7b14dbaeab1dfdecdc34b0199c69f71759c3eaec
- flags: 0
- metadata: {'user': 'jiho@jiho-asus'}

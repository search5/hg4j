실제 `hg` CLI(Mercurial 7.2, 시스템 설치본)로
`hg --config format.exp-use-changelog-v2=enable-unstable-format-and-corrupt-my-data init`
후 2회 커밋해서 얻은 실제 changelog-v2(docket 기반) 파일 3종.
- docket.i: 원본 파일명 00changelog.i (실제 docket, 59바이트 S_HEADER + 3개 UUID)
- data.idx: 원본 파일명 00changelog-4ac9cebf.idx (INDEX_ENTRY_CL_V2, 96바이트/레코드, 2개 레코드)
- data.dat: 원본 파일명 00changelog-b2964fda.dat (zstd 압축된 changelog 텍스트, 레코드별 독립 프레임)
- data.sda: 원본 파일명 00changelog-61b7777d.sda (sidedata, 이 저장소에서는 비어있음)

rev0 node: 78b00ae5215f47873d210b4c43e9d9adad40e2fb
rev1 node: a982e222c0e75569b3b55869ec11c16a5944543b
rev0 content(zstd 해제 후): "c180980ee057d123b053b78589cb4040f93d2c97\ntest <test@example.com>\n1788187249 -32400\nfile1.txt\n\nfirst commit"
rev1 content(zstd 해제 후): "e9b7326956ae0392ad5a69e01504f914788de885\ntest <test@example.com>\n1788187396 -32400\nfile1.txt\n\nsecond commit"

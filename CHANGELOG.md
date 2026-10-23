# Changelog

## [4.7.0](https://github.com/yschimke/compose-preview-mcp/compare/v4.6.0...v4.7.0) (2026-10-23)


### Bug Fixes

* **deps:** update dependency ee.schimke.composeai:compose-ai-tools-bom to v2.72.0 ([#69](https://github.com/yschimke/compose-preview-mcp/issues/69)) ([f0e61ca](https://github.com/yschimke/compose-preview-mcp/commit/f0e61cac6f2b4a6c13d3b28098fb15f285dd3d7c))
* **deps:** update dependency ee.schimke.composeai:compose-preview-contracts-bom to v3.39.0 ([#65](https://github.com/yschimke/compose-preview-mcp/issues/65)) ([45ab8cb](https://github.com/yschimke/compose-preview-mcp/commit/45ab8cb426f3fa82a116144f7e06897d98065e70))
* keep dependency edits and drop superseded source roots ([#70](https://github.com/yschimke/compose-preview-mcp/issues/70)) ([e5e31e2](https://github.com/yschimke/compose-preview-mcp/commit/e5e31e236e046cc200770b5143ff7dd54c5dbde2))
* key source-watch modules by module path and keep in-process compiles complete ([#68](https://github.com/yschimke/compose-preview-mcp/issues/68)) ([0d89df4](https://github.com/yschimke/compose-preview-mcp/commit/0d89df4494a1a0a91e3bd3da2f4da077cd599e97))

## [4.6.0](https://github.com/yschimke/compose-preview-mcp/compare/v4.5.0...v4.6.0) (2026-10-23)


### Features

* ask the daemon to warm its compiler after the first render ([#62](https://github.com/yschimke/compose-preview-mcp/issues/62)) ([7ba5043](https://github.com/yschimke/compose-preview-mcp/commit/7ba504324d3af3dd1e8516f1a59ecff41b4c7769))
* compile edits in the daemon by default ([#55](https://github.com/yschimke/compose-preview-mcp/issues/55)) ([d76d623](https://github.com/yschimke/compose-preview-mcp/commit/d76d62344d338f8eea46cb07468cd7cecb250943))
* watch where the build says sources are, and let agents narrow it ([#56](https://github.com/yschimke/compose-preview-mcp/issues/56)) ([fce9036](https://github.com/yschimke/compose-preview-mcp/commit/fce90364293a49d994a17723868b11d758ac144b))


### Bug Fixes

* compile each edit with its own module's sources, once ([#58](https://github.com/yschimke/compose-preview-mcp/issues/58)) ([22c4aa9](https://github.com/yschimke/compose-preview-mcp/commit/22c4aa99211df394e47476796d2dc8ec7e339b70))
* **deps:** update dependency ee.schimke.composeai:compose-ai-tools-bom to v2.70.5 ([#48](https://github.com/yschimke/compose-preview-mcp/issues/48)) ([78253a5](https://github.com/yschimke/compose-preview-mcp/commit/78253a50703bb217ff3e93a9b6f17d62c3765aaa))
* **deps:** update dependency ee.schimke.composeai:compose-ai-tools-bom to v2.70.7 ([#51](https://github.com/yschimke/compose-preview-mcp/issues/51)) ([99e5d2a](https://github.com/yschimke/compose-preview-mcp/commit/99e5d2a05c0cd8dc427ad1dfcace1b82fb422955))
* **deps:** update dependency ee.schimke.composeai:compose-ai-tools-bom to v2.71.0 ([#60](https://github.com/yschimke/compose-preview-mcp/issues/60)) ([140e7c4](https://github.com/yschimke/compose-preview-mcp/commit/140e7c4abc0a0249f139228feddf3fcdf28ec51b))
* **deps:** update dependency ee.schimke.composeai:compose-preview-daemon-bom to v3.21.3 ([#49](https://github.com/yschimke/compose-preview-mcp/issues/49)) ([dc0da20](https://github.com/yschimke/compose-preview-mcp/commit/dc0da20e7aca64628141cd69d836ff76532c56d8))
* **deps:** update dependency ee.schimke.composeai:compose-preview-daemon-bom to v3.21.4 ([#52](https://github.com/yschimke/compose-preview-mcp/issues/52)) ([3aec768](https://github.com/yschimke/compose-preview-mcp/commit/3aec768be5c27efafee38f704a21c58c8014f73d))
* **deps:** update dependency ee.schimke.composeai:compose-preview-daemon-bom to v3.22.0 ([#59](https://github.com/yschimke/compose-preview-mcp/issues/59)) ([897fffa](https://github.com/yschimke/compose-preview-mcp/commit/897fffa848f884b03cdd9099c423cbc551d6d9aa))
* send the edited files as a change set with compileSources ([#54](https://github.com/yschimke/compose-preview-mcp/issues/54)) ([1508536](https://github.com/yschimke/compose-preview-mcp/commit/150853676e9692e46a0661bed91e12774c7e1faa))

## [4.5.0](https://github.com/yschimke/compose-preview-mcp/compare/v4.4.0...v4.5.0) (2026-10-20)


### Bug Fixes

* **deps:** update dependency ee.schimke.composeai:compose-ai-tools-bom to v2.70.2 ([#41](https://github.com/yschimke/compose-preview-mcp/issues/41)) ([664bd8e](https://github.com/yschimke/compose-preview-mcp/commit/664bd8e0096f0175bcdb4c1114a75c99648bbdd3))
* **deps:** update dependency ee.schimke.composeai:compose-preview-contracts-bom to v3.37.0 ([#42](https://github.com/yschimke/compose-preview-mcp/issues/42)) ([2fc1493](https://github.com/yschimke/compose-preview-mcp/commit/2fc1493988e841e27ee862090c4cdbf905b727d2))
* **deps:** update dependency ee.schimke.composeai:compose-preview-daemon-bom to v3.21.1 ([#43](https://github.com/yschimke/compose-preview-mcp/issues/43)) ([ca9f724](https://github.com/yschimke/compose-preview-mcp/commit/ca9f724b3abcbad67ee57f6893dd3998496ecae0))
* **deps:** update dependency ee.schimke.composeai:remote-compose-player-js-dist to v2.17.0 ([#44](https://github.com/yschimke/compose-preview-mcp/issues/44)) ([ff57574](https://github.com/yschimke/compose-preview-mcp/commit/ff575742eab3b4704e70dd52031d871b69c1df10))
* **deps:** update dependency org.jetbrains.kotlinx:kotlinx-serialization-json to v1.12.0 ([#39](https://github.com/yschimke/compose-preview-mcp/issues/39)) ([d0b403a](https://github.com/yschimke/compose-preview-mcp/commit/d0b403a064fa995e2222a4175052916378ea70fb))
* keep a worktree under a staged origin routed as its own build ([#47](https://github.com/yschimke/compose-preview-mcp/issues/47)) ([505762c](https://github.com/yschimke/compose-preview-mcp/commit/505762cc5cd1a3993aeb386680d2378b9160bd13))
* route edits in a staged directory's origin workspace to the staged project ([#46](https://github.com/yschimke/compose-preview-mcp/issues/46)) ([bab714b](https://github.com/yschimke/compose-preview-mcp/commit/bab714b222ab32f888c739b17e9fd8c76ac1b355))
* treat a staged bazel prepare --out directory as a Bazel-family build ([#45](https://github.com/yschimke/compose-preview-mcp/issues/45)) ([e9711fd](https://github.com/yschimke/compose-preview-mcp/commit/e9711fd89f822a24c3ee06657367c4e25ad1ca3d))

## [4.4.0](https://github.com/yschimke/compose-preview-mcp/compare/v4.3.0...v4.4.0) (2026-10-19)


### Bug Fixes

* **deps:** update dependency ee.schimke.composeai:compose-ai-tools-bom to v2.68.2 ([#19](https://github.com/yschimke/compose-preview-mcp/issues/19)) ([0984a47](https://github.com/yschimke/compose-preview-mcp/commit/0984a477222fb2a662e9ee53f6dedb282e6869c4))
* **deps:** update dependency ee.schimke.composeai:compose-ai-tools-bom to v2.68.6 ([#26](https://github.com/yschimke/compose-preview-mcp/issues/26)) ([6a9a1e1](https://github.com/yschimke/compose-preview-mcp/commit/6a9a1e1497333e187a48c2483cd94d10118c99f4))
* **deps:** update dependency ee.schimke.composeai:compose-ai-tools-bom to v2.68.7 ([#31](https://github.com/yschimke/compose-preview-mcp/issues/31)) ([e1d1cce](https://github.com/yschimke/compose-preview-mcp/commit/e1d1cce546dbde3b6d6986e0ae82c8f77c598591))
* **deps:** update dependency ee.schimke.composeai:compose-ai-tools-bom to v2.69.0 ([#33](https://github.com/yschimke/compose-preview-mcp/issues/33)) ([5a4e19f](https://github.com/yschimke/compose-preview-mcp/commit/5a4e19f7a2ad60b5a0655850926e6d16b29e4b93))
* **deps:** update dependency ee.schimke.composeai:compose-ai-tools-bom to v2.69.1 ([#37](https://github.com/yschimke/compose-preview-mcp/issues/37)) ([6fc6e19](https://github.com/yschimke/compose-preview-mcp/commit/6fc6e1945c936bffffac6e03f66c63f072eec68c))
* **deps:** update dependency ee.schimke.composeai:compose-preview-contracts-bom to v3.36.0 ([#20](https://github.com/yschimke/compose-preview-mcp/issues/20)) ([5edf41a](https://github.com/yschimke/compose-preview-mcp/commit/5edf41a67427b40d3be3fc4eb6a1a49cb861823c))
* **deps:** update dependency ee.schimke.composeai:compose-preview-daemon-bom to v3.18.6 ([#17](https://github.com/yschimke/compose-preview-mcp/issues/17)) ([364890b](https://github.com/yschimke/compose-preview-mcp/commit/364890b3130d13082e2763ccef1d757d0864837b))
* **deps:** update dependency ee.schimke.composeai:compose-preview-daemon-bom to v3.18.8 ([#27](https://github.com/yschimke/compose-preview-mcp/issues/27)) ([19957d0](https://github.com/yschimke/compose-preview-mcp/commit/19957d0b6b9bc86fb833f9936ad5328019c43eb4))
* **deps:** update dependency ee.schimke.composeai:compose-preview-daemon-bom to v3.18.9 ([#32](https://github.com/yschimke/compose-preview-mcp/issues/32)) ([ca19a4c](https://github.com/yschimke/compose-preview-mcp/commit/ca19a4c7e0f2e08c0ec0b09af1a7b92e05d00b11))
* **deps:** update dependency ee.schimke.composeai:compose-preview-daemon-bom to v3.20.0 ([#34](https://github.com/yschimke/compose-preview-mcp/issues/34)) ([3d264d5](https://github.com/yschimke/compose-preview-mcp/commit/3d264d5bdd3715d2ddd2ece712b06300781354ef))
* **deps:** update dependency ee.schimke.composeai:remote-compose-player-js-dist to v2.13.1 ([#24](https://github.com/yschimke/compose-preview-mcp/issues/24)) ([61318dc](https://github.com/yschimke/compose-preview-mcp/commit/61318dc6a20f6653c89ccbeeffed93ddf19589ce))
* **deps:** update dependency ee.schimke.composeai:remote-compose-player-js-dist to v2.16.1 ([#28](https://github.com/yschimke/compose-preview-mcp/issues/28)) ([b26f7b8](https://github.com/yschimke/compose-preview-mcp/commit/b26f7b8f56cec44c7937abcd26bffc2f17716e65))
* **mcp-app:** take the viewer and preview library from compose-preview-server v3.142.0 ([#14](https://github.com/yschimke/compose-preview-mcp/issues/14)) ([13a1125](https://github.com/yschimke/compose-preview-mcp/commit/13a1125a63fa38d3ae3d2a5fe243da3af0475bc7))
* **mcp-app:** take the viewer and preview library from compose-preview-server v3.143.0 ([#22](https://github.com/yschimke/compose-preview-mcp/issues/22)) ([f8db5d6](https://github.com/yschimke/compose-preview-mcp/commit/f8db5d646267cd6fefec2eed7b72a71e9ad62542))
* never read a checkout of builds as a staged bazel --out directory ([#38](https://github.com/yschimke/compose-preview-mcp/issues/38)) ([7ec26c0](https://github.com/yschimke/compose-preview-mcp/commit/7ec26c00b2937f61f36b14476d990d6bc650d4e4))
* reject a staged bazel --out directory in register_project ([#35](https://github.com/yschimke/compose-preview-mcp/issues/35)) ([0e2e295](https://github.com/yschimke/compose-preview-mcp/commit/0e2e295809769c12d0453b7fda6e364ce6f8d25f))
* tolerate malformed cached workspace fields ([#30](https://github.com/yschimke/compose-preview-mcp/issues/30)) ([4285ad9](https://github.com/yschimke/compose-preview-mcp/commit/4285ad908c65c45f60678aa04a342d46e4016efa))

## [4.3.0](https://github.com/yschimke/compose-preview-mcp/compare/v4.2.0...v4.3.0) (2026-10-16)


### Features

* prepare and rebuild Buck2 projects through the compose-preview CLI ([#12](https://github.com/yschimke/compose-preview-mcp/issues/12)) ([be415ac](https://github.com/yschimke/compose-preview-mcp/commit/be415ac3a4dcbc725ba3cdeb2d688095b59380f9))

## [4.2.0](https://github.com/yschimke/compose-preview-mcp/compare/v4.1.0...v4.2.0) (2026-10-16)


### Bug Fixes

* enable record_preview's script extensions and consume compose-ai-tools 2.64.x ([#10](https://github.com/yschimke/compose-preview-mcp/issues/10)) ([f67efb8](https://github.com/yschimke/compose-preview-mcp/commit/f67efb8dc0a69cf3dd25374abc9c17deedc8aac9))

## [4.1.0](https://github.com/yschimke/compose-preview-mcp/compare/v4.0.0...v4.1.0) (2026-10-14)


### Features

* accept a packed preview bundle as a project ([#8](https://github.com/yschimke/compose-preview-mcp/issues/8)) ([0b0a539](https://github.com/yschimke/compose-preview-mcp/commit/0b0a539f0240edd938bfb340cfab4de3352ee359))
* prepare Bazel workspaces through the compose-preview CLI ([#7](https://github.com/yschimke/compose-preview-mcp/issues/7)) ([8d65ec7](https://github.com/yschimke/compose-preview-mcp/commit/8d65ec709eeb229ed1ff3ab26344d3294e86914f))

## [4.0.0](https://github.com/yschimke/compose-preview-mcp/compare/v3.132.0...v4.0.0) (2026-10-12)


### Features

* adopt UI Builder 3.71.0 — design branch MCP tools and the focused editor layout setting ([#1268](https://github.com/yschimke/compose-preview-mcp/issues/1268)) ([ab0b9fe](https://github.com/yschimke/compose-preview-mcp/commit/ab0b9fe7cccb1f96cdc9a6574852893b91c670d7))
* **deps:** take every upstream line by its BOM, and move to the daemon's builders ([#915](https://github.com/yschimke/compose-preview-mcp/issues/915)) ([9670b4a](https://github.com/yschimke/compose-preview-mcp/commit/9670b4af52690348ae8e2164b84fafc94a5bd0e7))
* **dist:** state the distribution's Java floor for a launcher to preflight ([#369](https://github.com/yschimke/compose-preview-mcp/issues/369)) ([7b6154e](https://github.com/yschimke/compose-preview-mcp/commit/7b6154e4ad6d46e52a1a5db4a74143fba1d1d376))
* **guidelines:** preview_guidelines_prompt and check_preview_guidelines MCP tools ([#1483](https://github.com/yschimke/compose-preview-mcp/issues/1483)) ([459da15](https://github.com/yschimke/compose-preview-mcp/commit/459da1547ffed1ffe0e73392ff4996699d221917))
* **mcp-app:** live viewer actions over the MCP Apps bridge ([#1177](https://github.com/yschimke/compose-preview-mcp/issues/1177)) ([d92c2bd](https://github.com/yschimke/compose-preview-mcp/commit/d92c2bd1d075b65a264b57f84b4006d6528abedb))
* **mcp-app:** number layout boxes with a property legend and add an Audit mode to the render card ([#1615](https://github.com/yschimke/compose-preview-mcp/issues/1615)) ([ffce95c](https://github.com/yschimke/compose-preview-mcp/commit/ffce95cb24f9f596f79d1b9b80cfd8bb7a635dd3))
* **mcp:** .rc Remote Compose file viewer (OpenAI file entrypoint) ([#1244](https://github.com/yschimke/compose-preview-mcp/issues/1244)) ([6f654c4](https://github.com/yschimke/compose-preview-mcp/commit/6f654c40f4defbd92946acc70952a513d29273f8))
* **mcp:** add interactive workflow elicitation ([#1128](https://github.com/yschimke/compose-preview-mcp/issues/1128)) ([5a9f62e](https://github.com/yschimke/compose-preview-mcp/commit/5a9f62efbec8d2bd8e421221fc20baf8c8ca14ac))
* **mcp:** add local preview file fallbacks ([#1124](https://github.com/yschimke/compose-preview-mcp/issues/1124)) ([37d155a](https://github.com/yschimke/compose-preview-mcp/commit/37d155a579adbbbc6e602be9c327afdb25f2fc5d))
* **mcp:** add portable MCP App preview viewer ([#1156](https://github.com/yschimke/compose-preview-mcp/issues/1156)) ([da16d3a](https://github.com/yschimke/compose-preview-mcp/commit/da16d3a0b7f0f5608c80c3a2b13e3b18d868b5e4))
* **mcp:** ask default guideline packs when a module publishes none, and show thin previews' widget source ([#1617](https://github.com/yschimke/compose-preview-mcp/issues/1617)) ([5bf5321](https://github.com/yschimke/compose-preview-mcp/commit/5bf5321eb5b2eae73b7fd5937708a3e5c777b2dc))
* **mcp:** check_preview_guidelines uses the stored key and OpenAI-compatible endpoints ([#1565](https://github.com/yschimke/compose-preview-mcp/issues/1565)) ([9a1c8f5](https://github.com/yschimke/compose-preview-mcp/commit/9a1c8f5ce0907497fc1ce52021caa898e95fbd58))
* **mcp:** declare tool output schemas ([#1142](https://github.com/yschimke/compose-preview-mcp/issues/1142)) ([28f1898](https://github.com/yschimke/compose-preview-mcp/commit/28f1898f2d9f0d5cd44d08b3a62d47d78ff8ae21))
* **mcp:** design_open opens .uid designs in the UI Builder MCP App ([#1252](https://github.com/yschimke/compose-preview-mcp/issues/1252)) ([2f5287b](https://github.com/yschimke/compose-preview-mcp/commit/2f5287b26681ec50060da280ee93fec81314ec10))
* **mcp:** elicitation and prompts with text fallbacks ([#1179](https://github.com/yschimke/compose-preview-mcp/issues/1179)) ([a39e45d](https://github.com/yschimke/compose-preview-mcp/commit/a39e45d65eecf00d4988ede2795049195aea3346))
* **mcp:** find, register and prepare any project automatically ([#1191](https://github.com/yschimke/compose-preview-mcp/issues/1191)) ([55b16d7](https://github.com/yschimke/compose-preview-mcp/commit/55b16d70dffd2e3724b2ab106dc1b03ebe552e90))
* **mcp:** move the MCP server into this repository ([#308](https://github.com/yschimke/compose-preview-mcp/issues/308)) ([05ed503](https://github.com/yschimke/compose-preview-mcp/commit/05ed50328f4aab58e7e5a2453a34d325b3c3c1cc))
* **mcp:** one-call preview render for agents ([#1167](https://github.com/yschimke/compose-preview-mcp/issues/1167)) ([a55109a](https://github.com/yschimke/compose-preview-mcp/commit/a55109a35c1b9be2135144575b8b50aec5f71312))
* **mcp:** OpenAI MCP Extensions entrypoint plumbing and opt-in probe ([#1243](https://github.com/yschimke/compose-preview-mcp/issues/1243)) ([fc1a050](https://github.com/yschimke/compose-preview-mcp/commit/fc1a0509651d644196b026bf4bd18dbb65e594e8))
* **mcp:** opt-in a11y and layout details in the render card ([#1175](https://github.com/yschimke/compose-preview-mcp/issues/1175)) ([4ce8052](https://github.com/yschimke/compose-preview-mcp/commit/4ce805248771e27885b52f0bf1cae99101741c11))
* **mcp:** per-client defaults and instructions, and opt in to semantics on demand ([#1231](https://github.com/yschimke/compose-preview-mcp/issues/1231)) ([e8eb089](https://github.com/yschimke/compose-preview-mcp/commit/e8eb08910a5a132084eac0221b61d0c403df6cb4))
* **mcp:** render several preview matches as a grid and size the model's image ([#1199](https://github.com/yschimke/compose-preview-mcp/issues/1199)) ([f113d0c](https://github.com/yschimke/compose-preview-mcp/commit/f113d0ce8faac428e4902ed4694612de4d4d7a91))
* **mcp:** require the daemon's per-render work trace in the edit loop ([#1257](https://github.com/yschimke/compose-preview-mcp/issues/1257)) ([195c737](https://github.com/yschimke/compose-preview-mcp/commit/195c73737f76e824e7a89f97bfbd71aa31b1ec9b))
* **mcp:** sidebar library apps with deep links, and structured settings ([#1246](https://github.com/yschimke/compose-preview-mcp/issues/1246)) ([448cca5](https://github.com/yschimke/compose-preview-mcp/commit/448cca55cac870455cb9af35f6b2e754cd945ab9))
* **mcp:** surface UI Builder designs from active sessions ([#1356](https://github.com/yschimke/compose-preview-mcp/issues/1356)) ([c257602](https://github.com/yschimke/compose-preview-mcp/commit/c2576025ed26b9d3e841b0ee50ba65505730ff9f))
* **mcp:** thread Previews tab with point-at context, and composer @-mentions ([#1245](https://github.com/yschimke/compose-preview-mcp/issues/1245)) ([88af7a8](https://github.com/yschimke/compose-preview-mcp/commit/88af7a800d837486ebf9fa4d7eaf4580e32780ac))
* **mcp:** thumbnail pickers for visual choices (OpenAI form elicitation) ([#1253](https://github.com/yschimke/compose-preview-mcp/issues/1253)) ([06247c7](https://github.com/yschimke/compose-preview-mcp/commit/06247c75f19c3619953ed08312e5c2534ea5ba45))
* **mcp:** warm the project's daemon on register_project ([#1214](https://github.com/yschimke/compose-preview-mcp/issues/1214)) ([31dbdcd](https://github.com/yschimke/compose-preview-mcp/commit/31dbdcd9636a4ade51adde59c87b7b7e2d588ea1))
* move compose-preview-mcp here from compose-preview-server, with history ([8ec1c59](https://github.com/yschimke/compose-preview-mcp/commit/8ec1c5962d90885d752c5d2cd57f80bd4fd52fee))
* **serve:** simplify design card actions ([#1357](https://github.com/yschimke/compose-preview-mcp/issues/1357)) ([82e9e78](https://github.com/yschimke/compose-preview-mcp/commit/82e9e78c95574c02f1e64f3461369c2778849ec7))
* stop publishing to Maven Central, and ship only the release archives (breaking) ([#794](https://github.com/yschimke/compose-preview-mcp/issues/794)) ([d9a6c77](https://github.com/yschimke/compose-preview-mcp/commit/d9a6c771ed300aa055811e00fc67d6ddfc8be0b5))
* **ui-builder:** export Remote Compose source for remote-m3 designs ([#364](https://github.com/yschimke/compose-preview-mcp/issues/364)) ([a9ada49](https://github.com/yschimke/compose-preview-mcp/commit/a9ada4930e57545c38831197fa2b5356e27b46a3))
* **ui-builder:** gate stateful Remote authoring behind a build flag ([#708](https://github.com/yschimke/compose-preview-mcp/issues/708)) ([ee4fed6](https://github.com/yschimke/compose-preview-mcp/commit/ee4fed6856398d77f681f834aeaba58ccf901a27))


### Bug Fixes

* carry the supplied-document test's fixtures and format the build script ([d913a15](https://github.com/yschimke/compose-preview-mcp/commit/d913a154fb62d283cebb2c3fc204a63bcb0d4963))
* **ci:** pin the edit-loop fixture's Compose BOM and keep Renovate out of it ([#1208](https://github.com/yschimke/compose-preview-mcp/issues/1208)) ([c1ba471](https://github.com/yschimke/compose-preview-mcp/commit/c1ba471f6934596cba982439764bf767c25fdf1f))
* **deploy:** stop forcing Maven Local ([#868](https://github.com/yschimke/compose-preview-mcp/issues/868)) ([6be3e26](https://github.com/yschimke/compose-preview-mcp/commit/6be3e26f7bbe0497dc1722439bbbcbfdfdc9a1f9))
* **deps:** update dependency androidx.compose:compose-bom to v2026.09.00 ([#1204](https://github.com/yschimke/compose-preview-mcp/issues/1204)) ([35340d7](https://github.com/yschimke/compose-preview-mcp/commit/35340d7b5cfc45fb978bad3007a9fce087a7562e))
* **guidelines:** review findings from [#1424](https://github.com/yschimke/compose-preview-mcp/issues/1424), [#1432](https://github.com/yschimke/compose-preview-mcp/issues/1432), [#1468](https://github.com/yschimke/compose-preview-mcp/issues/1468), [#1471](https://github.com/yschimke/compose-preview-mcp/issues/1471), [#1482](https://github.com/yschimke/compose-preview-mcp/issues/1482) and [#1483](https://github.com/yschimke/compose-preview-mcp/issues/1483) ([#1486](https://github.com/yschimke/compose-preview-mcp/issues/1486)) ([49021ad](https://github.com/yschimke/compose-preview-mcp/commit/49021ad5f7fe9088486ec48b2494aeac30cf3016))
* **mcp:** clear error state in the viewer and suggestions on preview misses ([#1195](https://github.com/yschimke/compose-preview-mcp/issues/1195)) ([b6dc504](https://github.com/yschimke/compose-preview-mcp/commit/b6dc5042551336d06e4203d78349b170463059a5))
* **mcp:** Codex chooser, viewer and status fixes from the compose-ag-plugin[#76](https://github.com/yschimke/compose-preview-mcp/issues/76) smoke run ([#1286](https://github.com/yschimke/compose-preview-mcp/issues/1286)) ([28cbbc6](https://github.com/yschimke/compose-preview-mcp/commit/28cbbc6b18acb42009d3ed81eac7e8e6cb87e536))
* **mcp:** construct RenderResult with the artifact daemon 3.2.0 expects ([#724](https://github.com/yschimke/compose-preview-mcp/issues/724)) ([f802378](https://github.com/yschimke/compose-preview-mcp/commit/f802378f49ec25a9d3c9450155f93d9083791818))
* **mcp:** enable the a11y and layout extensions render_preview details need ([#1212](https://github.com/yschimke/compose-preview-mcp/issues/1212)) ([c97d7e3](https://github.com/yschimke/compose-preview-mcp/commit/c97d7e3f650cb90569c10607c1024382f43a487e))
* **mcp:** follow-ups to the auto-registration, grid and call-budget changes ([#1223](https://github.com/yschimke/compose-preview-mcp/issues/1223)) ([9a136c3](https://github.com/yschimke/compose-preview-mcp/commit/9a136c3569140d44b720dc877697d25eb742c117))
* **mcp:** keep workspace registrations consistent across failures and restarts ([#1188](https://github.com/yschimke/compose-preview-mcp/issues/1188)) ([ce09ad0](https://github.com/yschimke/compose-preview-mcp/commit/ce09ad08df4bca855126971683eeccc0af9ba23f))
* **mcp:** never fake a preview render with a hand-built mock ([#1209](https://github.com/yschimke/compose-preview-mcp/issues/1209)) ([5e30dc9](https://github.com/yschimke/compose-preview-mcp/commit/5e30dc9289666edbf2d2c83418fa3ccbff149c70))
* **mcp:** read a preview's guidelines surface and profile from its manifest entry ([#1500](https://github.com/yschimke/compose-preview-mcp/issues/1500)) ([db93098](https://github.com/yschimke/compose-preview-mcp/commit/db93098dce329c24dbd91aa232729f4aec173bd0))
* **mcp:** recompile CLI-injected projects through the CLI's init script ([#1178](https://github.com/yschimke/compose-preview-mcp/issues/1178)) ([9c3819a](https://github.com/yschimke/compose-preview-mcp/commit/9c3819a339444500db748a10b03ca122c9b02f26))
* **mcp:** recompile edited sources before the daemon swaps classes ([#1171](https://github.com/yschimke/compose-preview-mcp/issues/1171)) ([8801e15](https://github.com/yschimke/compose-preview-mcp/commit/8801e152234f49c2b5c7c69963b000e122c8b69d))
* **mcp:** recompile on render when sources changed ([#1186](https://github.com/yschimke/compose-preview-mcp/issues/1186)) ([ace17e9](https://github.com/yschimke/compose-preview-mcp/commit/ace17e91106c86dcd95ee6b268d99685740a1e9f))
* **mcp:** recover from rejected renders, rediscover new previews, and bootstrap via the CLI ([#1251](https://github.com/yschimke/compose-preview-mcp/issues/1251)) ([e5abd80](https://github.com/yschimke/compose-preview-mcp/commit/e5abd8006072c1bdca55b3a5efeb90f0546ef5b9))
* **mcp:** recover preview library registrations and retain renders ([#1348](https://github.com/yschimke/compose-preview-mcp/issues/1348)) ([aed6d3c](https://github.com/yschimke/compose-preview-mcp/commit/aed6d3c02559ff497c9953561f88391fba7adc69))
* **mcp:** render the first match when the variant chooser is cancelled, and infer notify_file_changed's workspace ([#1263](https://github.com/yschimke/compose-preview-mcp/issues/1263)) ([a6b13ea](https://github.com/yschimke/compose-preview-mcp/commit/a6b13ea982223814963c264ea4d34ccd1574302b))
* **mcp:** render_matrix resolves multipreview names and renders their variants ([#1198](https://github.com/yschimke/compose-preview-mcp/issues/1198)) ([50c1e66](https://github.com/yschimke/compose-preview-mcp/commit/50c1e6636a6b68a211d5b722ba20a78eb8b0363a))
* **mcp:** report the release version in serverInfo ([#1183](https://github.com/yschimke/compose-preview-mcp/issues/1183)) ([326eb69](https://github.com/yschimke/compose-preview-mcp/commit/326eb690caeb0e91ded102fdb8137695f1441848))
* **mcp:** return a pending result before the client timeout on cold renders ([#1210](https://github.com/yschimke/compose-preview-mcp/issues/1210)) ([fd4163a](https://github.com/yschimke/compose-preview-mcp/commit/fd4163aaa8e9e3374bad37954af0dcabb7898ea5))
* **mcp:** route design files and refresh projects ([#1395](https://github.com/yschimke/compose-preview-mcp/issues/1395)) ([3f9084b](https://github.com/yschimke/compose-preview-mcp/commit/3f9084b2d2b5121a206f4b5f1e9df2391540184b))
* **mcp:** scope design roots and bound discovery ([#1358](https://github.com/yschimke/compose-preview-mcp/issues/1358)) ([fe86365](https://github.com/yschimke/compose-preview-mcp/commit/fe86365800b01518a5ef091f90254d9653b8db05))
* **mcp:** smoke-run failures from Claude Code, Antigravity and Codex ([#1340](https://github.com/yschimke/compose-preview-mcp/issues/1340)) ([9b6cf0d](https://github.com/yschimke/compose-preview-mcp/commit/9b6cf0d6222ad91e2bf9db328e65e2c938f8b32a))
* **rc-player:** resolve the TS player from Central and keep its custom host inert ([#1465](https://github.com/yschimke/compose-preview-mcp/issues/1465)) ([550db8f](https://github.com/yschimke/compose-preview-mcp/commit/550db8fcf1543ac718450aa795ba8d841dc03603))
* scope design discovery to the session and route prefixed primary modules ([#1374](https://github.com/yschimke/compose-preview-mcp/issues/1374)) ([8b7b2af](https://github.com/yschimke/compose-preview-mcp/commit/8b7b2afa0056b0a4ed169beb1992e52264a14590))
* **security:** sandbox catalog app pages and keep the guidelines key out of build children ([#1491](https://github.com/yschimke/compose-preview-mcp/issues/1491)) ([e06a550](https://github.com/yschimke/compose-preview-mcp/commit/e06a550170821ed45d875a44a625c859155fc96f))
* **serve:** name the capability ceiling where an agent's ask is narrowed away ([#953](https://github.com/yschimke/compose-preview-mcp/issues/953)) ([a2091ea](https://github.com/yschimke/compose-preview-mcp/commit/a2091ea3c22d6768f6e16716050c3b48c61ffd0e))


### Performance Improvements

* **mcp:** compile Kotlin edits in the daemon before falling back to Gradle ([#1197](https://github.com/yschimke/compose-preview-mcp/issues/1197)) ([34340d7](https://github.com/yschimke/compose-preview-mcp/commit/34340d7ecfb86f1442902f4ae09c0760cfbded2f))
* **mcp:** make the in-process compile opt-in until it beats Gradle ([#1200](https://github.com/yschimke/compose-preview-mcp/issues/1200)) ([f07f956](https://github.com/yschimke/compose-preview-mcp/commit/f07f956ff8aeccd3b000e8832d1ee1a49dbb8f7c))
* **mcp:** start one module of one build, not everything in reach ([#1342](https://github.com/yschimke/compose-preview-mcp/issues/1342)) ([5acf62d](https://github.com/yschimke/compose-preview-mcp/commit/5acf62da013e55e3d4a1157235a53fda24409498))

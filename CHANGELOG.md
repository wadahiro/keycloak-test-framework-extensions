## [0.3.0](https://github.com/wadahiro/keycloak-test-framework-extensions/compare/v0.2.0...v0.3.0) (2026-11-24)

### Features

* **snapshot:** add class-level state reset annotation ([87bf1fb](https://github.com/wadahiro/keycloak-test-framework-extensions/commit/87bf1fb9c0f7ce202904b628428839db849b62f0))
* **snapshot:** unify snapshot API across runtimes ([b501916](https://github.com/wadahiro/keycloak-test-framework-extensions/commit/b5019166f081c4e44bb9c1fea9ad1abdee3bc77e))

### Bug Fixes

* **snapshot:** reject global stateful resources ([9e4df9d](https://github.com/wadahiro/keycloak-test-framework-extensions/commit/9e4df9dfc6cb2184e892bdd2833f8776f42784e1))

## [0.2.0](https://github.com/wadahiro/keycloak-test-framework-extensions/compare/v0.1.0...v0.2.0) (2026-11-24)

### ⚠ BREAKING CHANGES

* **runtime:** Keycloak 20–26 now use the official Quarkus bootstrap through the Quarkus extension and matching runtime artifacts. The runtime Maven plugin selects kc.test.runtime.version, and Keycloak 15.0.2 is removed from the default build and CI matrix.

### Features

* **extensions:** verify custom SPI loading across runtimes ([a98107a](https://github.com/wadahiro/keycloak-test-framework-extensions/commit/a98107a4d8b93931fc48ebf7e43dd6c5859d0987))
* **runtime:** replace modern Undertow bootstrap with Quarkus ([2fc14d8](https://github.com/wadahiro/keycloak-test-framework-extensions/commit/2fc14d8ee84de9a374d85c7e389b84344c5bfbda))

### Bug Fixes

* **ci:** prefetch JUnit platform launcher ([71418b1](https://github.com/wadahiro/keycloak-test-framework-extensions/commit/71418b1854a2801d60a866c989aeaaf2b0597ca7))
* **ci:** prefetch JUnit platform provider ([ad69898](https://github.com/wadahiro/keycloak-test-framework-extensions/commit/ad6989840be997873ad0390beef1b4c9a7add2b0))
* **quarkus:** stabilize integration test startup ([c898386](https://github.com/wadahiro/keycloak-test-framework-extensions/commit/c8983862d03777e33697d192298dad3fe81c9cd8))

## [0.1.0](https://github.com/wadahiro/keycloak-test-framework-extensions/compare/v0.0.0...v0.1.0) (2026-11-21)

### ⚠ BREAKING CHANGES

* **api:** Artifacts now use groupId io.github.wadahiro.keycloak.testframework and Java packages under io.github.wadahiro.keycloak.testframework.*. Update dependency coordinates and imports.

### Features

* **broker:** add OIDC and SAML broker testing support ([63abe00](https://github.com/wadahiro/keycloak-test-framework-extensions/commit/63abe0051ae73352b7f1d8b8b0beaf034a11ed13))
* **ldap:** add in-memory federation test support ([091249d](https://github.com/wadahiro/keycloak-test-framework-extensions/commit/091249dfa4045dd177f36c22ddb38faf044276b2))
* **testing:** add isolated Keycloak integration testing ([4737570](https://github.com/wadahiro/keycloak-test-framework-extensions/commit/4737570d332868ef7a436849ab5fbea7fce6f6d6))

### Bug Fixes

* **runtime:** restore legacy Keycloak integration tests ([76564ad](https://github.com/wadahiro/keycloak-test-framework-extensions/commit/76564ad4b538bb8f36fa05fe8d305903cc6ed647))

### Code Refactoring

* **api:** align Maven and Java namespaces ([a3dd977](https://github.com/wadahiro/keycloak-test-framework-extensions/commit/a3dd977d81a985914a2e694e83c2180ef7885a0a))

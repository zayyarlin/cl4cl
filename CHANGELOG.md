# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](https://keepachangelog.com/).

## [Unreleased]

## [0.1.1] - 2026-11-20
### Added
- `cl4cl.core/guide`: the usage guide, shipped in the jar as `cl4cl/guide.md`, for coding agents and new users.

## 0.1.0 - 2026-11-20
### Added
- `cl4cl.core`: `client`, `request`, `response`, `execute`, `lines`, `create`, `stream`, `final-message`, `count-tokens`.
- `cl4cl.batches`: `create`, `retrieve`, `list`, `cancel`, `delete`, `results`.
- `cl4cl.stream/accumulate`, `cl4cl.stream/payloads` and the `cl4cl.stream/apply-delta` multimethod for working with stream events.
- `cl4cl.error`: the error hierarchy and `retryable?`.

[Unreleased]: https://github.com/zayyarlin/cl4cl/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/zayyarlin/cl4cl/compare/v0.1.0...v0.1.1

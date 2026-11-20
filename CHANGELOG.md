# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](https://keepachangelog.com/).

## [Unreleased]
### Fixed
- A `retry-after` header is obeyed only when it is a number of seconds from zero up to the retry policy's new optional `:max-retry-after-ms` (default 60000). Before, a large value made the caller sleep for as long as it said, a negative one threw from `Thread/sleep`, and `Infinity` replaced the API's error with an `IllegalArgumentException`.

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

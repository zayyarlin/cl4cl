# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](https://keepachangelog.com/).

## [Unreleased]
### Added
- `cl4cl.tools`, the tool loop: `run` calls the API, runs the tools Claude asks for and sends the results back until it answers; `turns` gives every message of the loop; `step`, `pending?`, `results` and `call` are the layers underneath, for a caller who needs a different loop. The options `:create` and `:results` replace how a message is fetched and how tool calls are answered.
- `cl4cl.message`, pure functions over messages and request bodies: `text`, `tool-uses`, `data`, `reply`, `say`, `image` and `document`.
- `cl4cl.stream/text-deltas`, a transducer from stream events to their pieces of text.
- Client option `:idle-timeout-ms` (default 600000): the longest a streaming call, `stream` or `batches/results`, waits for the reply to start or to continue. A stream that is silent for longer throws a `:cl4cl.error/connection` error. Before, a connection that went silent blocked the reducing thread for ever. `nil` restores that.
- `cl4cl.http/idle-timeout`, which does the above for any open `InputStream`.
### Fixed
- JSON nested too deeply for the stack, which a model can produce as a tool input, throws an `ex-info` of type `:cl4cl.error/unknown` instead of a `StackOverflowError`. In a stream the block is treated like a tool call that was cut short: `:input` is left alone and the text stays on the block's metadata.
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

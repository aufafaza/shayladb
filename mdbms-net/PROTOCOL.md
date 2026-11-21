# ShaylaDB network protocol

The server accepts interactive telnet clients and programmatic raw-TCP clients
on the same listener. A valid telnet negotiation selects the interactive CLI;
all other connections use the framed protocol below.

## Raw TCP framing

Each message is a four-byte unsigned big-endian payload length followed by that
many UTF-8 bytes. Frames are limited to 1,048,576 bytes. A client must finish
the complete header and payload within 30 seconds. The server applies the same
deadline to telnet input and to response writes. Telnet queries are limited to
the same 1,048,576-byte UTF-8 size. A timeout, incomplete frame, or oversized
frame closes the connection after an error response when one can be written.

Each request is one JSON object:

```json
{"sql":"SELECT 'café; 東京'"}
```

The frame is the request boundary. Semicolons, quotes, escaped quotes, and
newlines are SQL text; raw-TCP clients send separate frames for separate
execution attempts.

The server produces exactly one JSON response frame for each complete request.
Every response has a `status` and non-negative `elapsedMs`.

| Status | Required fields |
| --- | --- |
| `RESULT` | `columns`, `rows`; optional `message` |
| `EMPTY` | `columns: []`, `rows: []` |
| `WRITE` | numeric `affectedRows`, including `0`; optional `message` |
| `OK` | optional `message` |
| `ERROR` | stable `code` and non-empty `message` |

Database nulls are JSON `null` inside `rows`. `ERROR` codes include
`INVALID_REQUEST`, `INVALID_UTF8`, `INCOMPLETE_REQUEST`, `REQUEST_TIMEOUT`,
`REQUEST_TOO_LARGE`, `RESPONSE_TOO_LARGE`, `ADMISSION_REJECTED`,
`TRANSACTION_STATE`, and `EXECUTION_ERROR`.

Clean EOF before a frame starts ends the session. EOF during a frame is an
`INCOMPLETE_REQUEST`. On any disconnect or timeout, an active transaction is
aborted before the session is removed.
